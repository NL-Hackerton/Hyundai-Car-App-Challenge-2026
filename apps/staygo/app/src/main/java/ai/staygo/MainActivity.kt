package ai.staygo

import ai.staygo.decision.Candidate
import ai.staygo.decision.ChargingStatus
import ai.staygo.decision.Decision
import ai.staygo.decision.DecisionModel
import ai.staygo.decision.Emphasis
import ai.staygo.decision.Inputs
import ai.staygo.decision.Params
import ai.staygo.navi.NaviClient
import ai.staygo.signal.PowerEstimator
import ai.staygo.ui.HeroPresenter
import ai.staygo.ui.PowerChart
import ai.staygo.ui.SocBar
import ai.staygo.vehicle.VehicleSignals
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlin.math.floor
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), VehicleSignals.Listener, NaviClient.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val params = Params()
    private lateinit var vehicle: VehicleSignals
    private lateinit var navi: NaviClient
    private lateinit var bands: BandHistory
    private val estimator = PowerEstimator()

    // Vehicle state
    private var soc: Int? = null
    private var levelWh: Double? = null
    private var capacityWh = 77_400.0
    private var chargeState: Int? = null
    private var instantaneousKw: Double? = null
    private var requiresDo = false
    private val powerHistory = ArrayList<Pair<Long, Double>>()
    private var chargingSinceMs: Long? = null
    private var wasCharging = false

    // Navigation state
    private var destination: NaviClient.Destination? = null
    private var destinationTrip: NaviClient.DestinationTrip? = null
    private var stopArrival: Pair<NaviClient.ChargingStop, Int>? = null
    private var activeWaypoints: List<NaviClient.ActiveWaypoint> = emptyList()
    private var lastTripSoc: Int? = null

    private var lastDecision: Decision? = null
    private var narrow = false
    /** The Go toast is shown once per charging session (the verdict can flicker while the navi re-routes). */
    private var goToastShown = false
    private var toastHide: Runnable? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startSignals() }

    private val tick = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 1_000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inflate()
        bands = BandHistory(this)
        vehicle = VehicleSignals(this, this)
        navi = NaviClient(this, this)
        val needed = listOf("pleos.car.permission.CAR_ENERGY", "android.car.permission.CAR_ENERGY", "pleos.car.permission.CAR_INFO", "android.car.permission.CAR_INFO")
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startSignals() else permissionLauncher.launch(needed.toTypedArray())
        render()
    }

    /** Picks the layout for the current window (narrow/portrait vs wide) and day/night colors. */
    private fun inflate() {
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.goButton).setOnClickListener { onGo() }
        findViewById<View>(R.id.stayButton).setOnClickListener { onStay() }
        // Narrow windows (three tiles share the width): no tile notes or "추정" tag; the hero carries the reason.
        // Short narrow windows (e.g. Connect-W wide window 1040x850dp) also drop the tile visuals, the route
        // line and the footer, and keep the reason to one line, so the hero is never squeezed.
        val c = resources.configuration
        narrow = c.screenWidthDp < 1100
        val compact = narrow && c.screenHeightDp < 1000
        fun show(id: Int, on: Boolean) { findViewById<View>(id).visibility = if (on) View.VISIBLE else View.GONE }
        listOf(R.id.socNote, R.id.powerNote, R.id.arrivalNote).forEach { show(it, !narrow) }
        listOf(R.id.socBar, R.id.powerChart, R.id.arrivalBar).forEach { show(it, !compact) }
        (findViewById<View>(R.id.routeText).parent as View).visibility = if (compact) View.GONE else View.VISIBLE
        (findViewById<View>(R.id.footText).parent as View).visibility = if (compact) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.reasonText).maxLines = if (compact) 1 else 2
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        lastDecision?.let { Log.i(TAG, "config ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp night=${newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK}") }
        inflate()
        render()
    }

    private fun startSignals() {
        vehicle.start()
        navi.start()
        main.post(tick)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        vehicle.stop()
        navi.release()
        super.onDestroy()
    }

    // ---- VehicleSignals.Listener ----
    override fun onSoc(soc: Int) = runOnUiThread {
        this.soc = soc
        val last = lastTripSoc
        if (last == null || soc != last) requestTrip()
    }

    override fun onBatteryLevelWh(wh: Double) = runOnUiThread {
        val now = SystemClock.elapsedRealtime()
        levelWh = wh
        estimator.addLevel(now, wh)
        if (chargingStatus() != ChargingStatus.CHARGING) return@runOnUiThread
        currentPowerKw()?.let { p ->
            powerHistory += now to p
            while (powerHistory.size > 900) powerHistory.removeAt(0)
            soc?.let { bands.record(it, p) }
        }
    }

    override fun onChargeState(state: Int) = runOnUiThread { chargeState = state }
    override fun onInstantaneousRateKw(kw: Double) = runOnUiThread { instantaneousKw = kw }
    override fun onCapacityWh(wh: Double) = runOnUiThread { if (wh > 0) capacityWh = wh }
    override fun onRequiresDistractionOptimization(required: Boolean) = runOnUiThread {
        requiresDo = required
        Log.i(TAG, "UX requiresDO=$required")
        render()
    }
    override fun onSignalError(source: String, message: String) { Log.w(TAG, "signal $source: $message") }

    // ---- NaviClient.Listener ----
    override fun onNaviReady() { Log.i(TAG, "navi ready") }

    override fun onDestination(destination: NaviClient.Destination?) {
        Log.i(TAG, "destination ${destination?.name} ${destination?.remainDistanceM}")
        val changed = destination?.name != this.destination?.name
        this.destination = destination
        if (changed) { destinationTrip = null; stopArrival = null; lastTripSoc = null }
        requestTrip()
        render()
    }

    override fun onDestinationTrip(trip: NaviClient.DestinationTrip) {
        Log.i(TAG, "trip estSoc=${trip.estimatedSoc} energyWh=${trip.energyWh.roundToInt()} stop=${trip.chargingStop?.name}")
        destinationTrip = trip
        if (trip.chargingStop == null || trip.chargingStop.name != stopArrival?.first?.name) stopArrival = null
        render()
    }

    override fun onChargingStopTrip(stop: NaviClient.ChargingStop, estimatedSoc: Int) {
        Log.i(TAG, "stop trip ${stop.name} estSoc=$estimatedSoc")
        stopArrival = stop to estimatedSoc
        render()
    }

    override fun onActiveWaypoints(waypoints: List<NaviClient.ActiveWaypoint>) {
        if (waypoints.isNotEmpty()) Log.i(TAG, "active waypoints ${waypoints.joinToString { "${it.name}/${it.maxPowerKw}kW/${it.chargerCount}" }}")
        activeWaypoints = waypoints
        render()
    }

    override fun onRouteSent(viaStop: NaviClient.ChargingStop?) {
        Log.i(TAG, "route sent via=${viaStop?.name}")
        showToast(if (viaStop == null) "목적지 경로를 내비에 전달했습니다" else "${shortName(viaStop.name)}을 거치는 경로를 내비에 전달했습니다")
    }

    override fun onNaviError(message: String) { Log.w(TAG, "navi $message") }

    // ---- Actions ----
    private fun onGo() {
        if (destination == null) return showToast("목적지를 먼저 설정해 주세요")
        val d = lastDecision
        val via = if (d?.rule == 5) destinationTrip?.chargingStop else null
        Log.i(TAG, "GO pressed rule=${d?.rule} via=${via?.name}")
        navi.go(via)
    }

    private fun onStay() {
        Log.i(TAG, "STAY pressed rule=${lastDecision?.rule}")
        showToast("계속 충전합니다 · SOC가 바뀔 때마다 다시 판단합니다")
    }

    private fun requestTrip() {
        if (destination == null) return
        lastTripSoc = soc
        navi.requestDestinationTrip()
    }

    // ---- Derived state ----
    private fun currentPowerKw(): Double? = instantaneousKw?.takeIf { it > 0.5 } ?: estimator.powerKw
    private fun powerEstimated(): Boolean = instantaneousKw?.let { it <= 0.5 } ?: true

    private fun chargingStatus(): ChargingStatus {
        val now = SystemClock.elapsedRealtime()
        val rising = estimator.isRising(now)
        val status = when {
            rising -> ChargingStatus.CHARGING
            chargeState == VehicleSignals.CHARGE_STATE_CHARGING -> ChargingStatus.CHARGING
            wasCharging -> ChargingStatus.FINISHED
            else -> ChargingStatus.NOT_CHARGING
        }
        if (status == ChargingStatus.CHARGING) {
            if (chargingSinceMs == null) chargingSinceMs = now
            wasCharging = true
        }
        return status
    }

    /** A: arrival SOC without further charging. With an inserted charging stop the navigation's SOC is after charging, so use energy. */
    private fun arrivalSoc(): Double? {
        val t = destinationTrip ?: return null
        if (t.chargingStop == null) return t.estimatedSoc.toDouble()
        val level = levelWh ?: return null
        return (level - t.energyWh) / capacityWh * 100.0
    }

    private fun candidate(): Candidate? {
        val stop = destinationTrip?.chargingStop ?: return null
        val ac = stopArrival?.takeIf { it.first.name == stop.name }?.second
        val wp = activeWaypoints.firstOrNull { it.name.contains(stop.name.take(4)) || stop.name.contains(it.name.take(4)) }
        return Candidate(stop.name, ac?.toDouble(), wp?.maxPowerKw?.toDouble()?.takeIf { it > 0 }, wp?.chargerCount)
    }

    // ---- Rendering ----
    private fun render() {
        if (!::vehicle.isInitialized) return
        val charging = chargingStatus()
        // Power is shown and used only once charging is recognized; before that the 10 s window still spans the idle level.
        val power = if (charging == ChargingStatus.CHARGING) currentPowerKw() else null
        val pmax = estimator.sessionMaxKw
        val a = arrivalSoc()
        val cand = candidate()
        val inputs = Inputs(
            charging = charging, soc = soc, powerKw = power, pmaxKw = pmax, pmaxTrusted = estimator.pmaxTrusted,
            hasDestination = destination != null, arrivalSoc = a, candidate = cand,
            bandPowerKw = cand?.arrivalSoc?.let { bands.max(it.toInt()) }, capacityKwh = capacityWh / 1000.0, params = params,
        )
        val d = DecisionModel.decide(inputs)
        val previous = lastDecision
        if (d.rule != previous?.rule || d.emphasis != previous?.emphasis) {
            Log.i(TAG, "DECISION rule=${d.rule} emphasis=${d.emphasis} state=${d.state} soc=$soc A=${a?.let { "%.1f".format(it) }} P=${power?.let { "%.1f".format(it) }} Pmax=${pmax?.let { "%.1f".format(it) }} cand=${cand?.name}/${cand?.arrivalSoc}/${cand?.maxPowerKw}")
        }
        lastDecision = d
        val hero = HeroPresenter.present(d, a, soc, params)

        findViewById<View>(R.id.drivingOverlay).visibility = if (requiresDo) View.VISIBLE else View.GONE
        text(R.id.drivingSummary, a?.let { "목적지 도착 예상 SOC ${it.roundToInt()}% · ${if (destinationTrip?.chargingStop == null) "충전 경유 없음" else "경유 충전 포함 경로"}" } ?: "Stay·Go 판단은 정차 중에 보여 줍니다")

        renderTopBar(charging)
        renderHero(hero, d, cand)
        renderMetrics(charging, power, pmax, a)
        renderButtons(d, hero, power)
        renderFooter(d, charging)

        // Optional Go toast: once, when the recommendation turns to Go while charging (not while driving).
        if (charging == ChargingStatus.NOT_CHARGING) goToastShown = false
        if (!goToastShown && hero.verdict == HeroPresenter.Verdict.GO && hero.strong && previous != null && previous.emphasis != Emphasis.GO && !requiresDo) {
            goToastShown = true
            showToast("지금 출발해도 됩니다 · ${a?.let { "목적지 ${it.roundToInt()}% 도착" } ?: "충전 후반"}", R.drawable.ic_navigate)
        }
    }

    private fun renderTopBar(charging: ChargingStatus) {
        val dot = findViewById<View>(R.id.statusDot)
        (dot.background.mutate() as GradientDrawable).setColor(color(when (charging) {
            ChargingStatus.CHARGING -> R.color.sg_stay
            ChargingStatus.FINISHED -> R.color.sg_go
            else -> R.color.sg_text_3
        }))
        text(R.id.statusText, when (charging) {
            ChargingStatus.CHARGING -> "급속충전 중 · 정차"
            ChargingStatus.FINISHED -> "충전 종료 · 정차"
            else -> "충전 중 아님 · 정차"
        })
        text(R.id.destChip, destination?.let { "${it.name} · ${(it.remainDistanceM / 1000.0).roundToInt()} km" } ?: "목적지 없음")
    }

    private fun renderHero(hero: HeroPresenter.Hero, d: Decision, cand: Candidate?) {
        val pill = findViewById<TextView>(R.id.verdictPill)
        val (label, bg, fg) = when (hero.verdict) {
            HeroPresenter.Verdict.GO -> Triple("GO", if (hero.strong) R.color.sg_go else R.color.sg_card_high, if (hero.strong) R.color.sg_on_go else R.color.sg_go_text)
            HeroPresenter.Verdict.STAY -> Triple("STAY", if (hero.strong) R.color.sg_stay else R.color.sg_card_high, if (hero.strong) R.color.sg_on_stay else R.color.sg_text)
            HeroPresenter.Verdict.WAIT -> Triple("대기", R.color.sg_card_high, R.color.sg_text_2)
        }
        pill.text = label
        pill.background = pillDrawable(color(bg))
        pill.setTextColor(color(fg))
        text(R.id.headlineText, hero.headline)
        text(R.id.reasonText, hero.reason)
        val chip = findViewById<TextView>(R.id.heroChip)
        if (hero.chip == null) chip.visibility = View.GONE else {
            chip.visibility = View.VISIBLE
            chip.text = hero.chip
            val (cbg, cfg) = when (hero.chipTone) {
                HeroPresenter.Tone.WARNING -> R.color.sg_warn to R.color.sg_on_warn
                HeroPresenter.Tone.POSITIVE -> R.color.sg_stay to R.color.sg_on_stay
                HeroPresenter.Tone.NEUTRAL -> R.color.sg_card_high to R.color.sg_text_2
            }
            chip.background = pillDrawable(color(cbg))
            chip.setTextColor(color(cfg))
        }
        text(R.id.routeText, routeLine(d, cand))
    }

    /** One line about the navi route (charging stop or direct), replacing the former route card. */
    private fun routeLine(d: Decision, cand: Candidate?): String {
        val trip = destinationTrip
        return when {
            destination == null || trip == null -> "내비 경로 정보를 기다리는 중"
            trip.chargingStop == null -> "내비 경로 · 충전 경유 없이 목적지 직행"
            else -> {
                val name = shortName(trip.chargingStop.name)
                val c = d.comparison
                when {
                    cand?.arrivalSoc == null -> "내비 경로 · 경유 충전 $name · 경유지 도착 SOC 계산 중"
                    c != null && c.gainMinutes > 0 -> "내비 경로 · $name 에서 채우면 약 ${"%.1f".format(c.gainMinutes)}분 빠름"
                    c != null -> "내비 경로 · 여기서 ${"%.1f".format(c.hereMinutes)}분 / $name ${"%.1f".format(c.candidateMinutes)}분"
                    else -> "내비 경로 · 경유 충전 $name (도착 SOC ${cand.arrivalSoc.roundToInt()}%)"
                }
            }
        }
    }

    private fun renderMetrics(charging: ChargingStatus, power: Double?, pmax: Double?, a: Double?) {
        text(R.id.socText, soc?.let { "$it%" } ?: "--%")
        findViewById<SocBar>(R.id.socBar).apply { socPercent = soc; this.charging = charging == ChargingStatus.CHARGING }
        text(R.id.socNote, when (charging) {
            ChargingStatus.CHARGING -> "${levelWh?.let { "%.1f".format(it / 1000) } ?: "--"} / ${(capacityWh / 1000).roundToInt()} kWh"
            ChargingStatus.FINISHED -> "충전이 끝났습니다"
            else -> "충전을 시작하면 판단합니다"
        })

        text(R.id.powerText, power?.roundToInt()?.toString() ?: "--")
        val trend = if (power != null) HeroPresenter.trend(powerHistory) else null
        text(R.id.powerTrend, when (trend) { HeroPresenter.Trend.DOWN -> "↓"; HeroPresenter.Trend.UP -> "↑"; HeroPresenter.Trend.FLAT -> "→"; null -> " " })
        findViewById<View>(R.id.powerTag).visibility = if (!narrow && power != null && powerEstimated()) View.VISIBLE else View.GONE
        text(R.id.powerNote, when {
            power == null || pmax == null -> "배터리 잔량 변화로 전력을 계산합니다"
            !estimator.pmaxTrusted -> "세션 최대를 믿을 수 없어 SOC 기준으로 판단"
            else -> "최대 ${pmax.roundToInt()} kW의 ${(power / pmax * 100).roundToInt()}%"
        })
        findViewById<PowerChart>(R.id.powerChart).update(powerHistory, pmax, params.lateRatio)

        val reserve = params.reserveSocPct.roundToInt()
        text(R.id.arrivalText, a?.let { "${it.roundToInt()}%" } ?: "--%")
        findViewById<SocBar>(R.id.arrivalBar).apply {
            socPercent = a?.roundToInt()
            markerPercent = reserve
            fillColor = color(if (a != null && a >= params.reserveSocPct) R.color.sg_stay else R.color.sg_warn)
        }
        text(R.id.arrivalNote, when {
            destination == null -> "내비에 목적지를 설정하면 계산합니다"
            a == null -> "내비 계산을 기다리는 중"
            a >= params.reserveSocPct -> "예비 $reserve% 충족"
            else -> "예비 $reserve%까지 ${"%.1f".format(params.reserveSocPct - a)}%p"
        })
    }

    private fun renderButtons(d: Decision, hero: HeroPresenter.Hero, power: Double?) {
        val stayPrimary = hero.verdict == HeroPresenter.Verdict.STAY && hero.strong
        val goPrimary = hero.verdict == HeroPresenter.Verdict.GO && hero.strong
        styleButton(R.id.stayButton, R.id.stayIcon, R.id.stayTitle, R.id.staySub, stayPrimary, R.color.sg_stay, R.color.sg_on_stay)
        styleButton(R.id.goButton, R.id.goIcon, R.id.goTitle, R.id.goSub, goPrimary, R.color.sg_go, R.color.sg_on_go)
        text(R.id.staySub, power?.let { "1%p당 약 ${DecisionModel.secondsPerPercent(capacityWh / 1000.0, it).roundToInt()}초" } ?: "현재 전력으로 계속 충전")
        text(R.id.goSub, when {
            destination == null -> "목적지가 없어 잠김"
            d.rule == 5 -> "${shortName(destinationTrip?.chargingStop?.name ?: "")} 경유 경로를 내비로"
            else -> "목적지 경로를 내비로"
        })
        findViewById<View>(R.id.goButton).apply { isEnabled = destination != null; alpha = if (destination == null) 0.4f else 1f }
    }

    private fun renderFooter(d: Decision, charging: ChargingStatus) {
        val since = chargingSinceMs
        val foot = findViewById<TextView>(R.id.footText)
        if (since == null || charging != ChargingStatus.CHARGING) {
            foot.text = "앱은 충전 중에만 Stay·Go를 판단합니다"
            foot.setTextColor(color(R.color.sg_text_3))
        } else {
            val min = ((SystemClock.elapsedRealtime() - since) / 60_000L).toInt()
            val warn = min >= 50
            foot.text = if (warn) "충전 ${min}분 · 급속충전구역 1시간 점유 제한까지 ${60 - min}분" else "충전 확인 후 ${min}분 · 급속충전구역 1시간 점유 제한 안내"
            foot.setTextColor(color(if (warn) R.color.sg_text else R.color.sg_text_3))
        }
        text(R.id.ruleText, when (d.rule) {
            1 -> "판단 규칙 1 · 충전 상태"
            2 -> "판단 규칙 2 · 예비 SOC 부족"
            3 -> "판단 규칙 3 · 전력 아직 높음"
            4 -> "판단 규칙 4 · 충전 후반"
            5 -> "판단 규칙 5 · 경유 충전 비교"
            6 -> "판단 규칙 6 · SOC 기준"
            else -> " "
        })
    }

    /** Pleos basic (tonal) vs filled button; ripple gives pressed feedback well under the 0.25 s response rule. */
    private fun styleButton(container: Int, icon: Int, title: Int, sub: Int, primary: Boolean, fill: Int, onFill: Int) {
        val radius = resources.getDimension(R.dimen.btn_radius)
        val shape = GradientDrawable().apply {
            cornerRadius = radius
            setColor(color(if (primary) fill else R.color.sg_basic))
            if (!primary) setStroke(dp(2), color(R.color.sg_line))
        }
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
        val ripple = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(color(R.color.sg_basic_pressed)), shape, mask,
        )
        findViewById<LinearLayout>(container).background = ripple
        val fg = color(if (primary) onFill else R.color.sg_text)
        findViewById<TextView>(title).setTextColor(fg)
        findViewById<TextView>(sub).setTextColor(if (primary) fg else color(R.color.sg_text_3))
        findViewById<ImageView>(icon).imageTintList = android.content.res.ColorStateList.valueOf(if (primary) fg else color(if (fill == R.color.sg_go) R.color.sg_go_text else R.color.sg_text_2))
    }

    private fun pillDrawable(fill: Int) = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); setColor(fill) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun showToast(message: String, icon: Int? = null) {
        val t = findViewById<TextView>(R.id.toast)
        t.text = message
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(icon ?: 0, 0, 0, 0)
        t.visibility = View.VISIBLE
        toastHide?.let { main.removeCallbacks(it) }
        toastHide = Runnable { t.visibility = View.GONE }.also { main.postDelayed(it, 4_000L) }
    }
    private fun text(id: Int, value: CharSequence) { findViewById<TextView>(id).text = value }
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    /** POI names arrive as "청도새마을휴게소(부산방향) 전기차충전소"; the screen shows the rest area only. */
    private fun shortName(poi: String) = poi.replace("전기차충전소", "").replace(Regex("\\(.*?\\)"), "").trim()

    companion object { const val TAG = "StayGo" }
}

/** Max charging power this car received per 5 %p SOC band. Stored on the device only; no location, time, or station. */
class BandHistory(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("band_power", android.content.Context.MODE_PRIVATE)
    private fun key(soc: Int) = "band_${floor(soc / 5.0).toInt() * 5}"
    fun record(soc: Int, kw: Double) {
        if (kw > (prefs.getFloat(key(soc), 0f))) prefs.edit().putFloat(key(soc), kw.toFloat()).apply()
    }
    fun max(soc: Int): Double? = prefs.getFloat(key(soc), 0f).toDouble().takeIf { it > 0.0 }
}
