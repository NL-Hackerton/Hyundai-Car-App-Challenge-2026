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
import android.text.Html
import android.util.Log
import android.view.View
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
    private var toastHide: Runnable? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startSignals() }

    private val tick = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 1_000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bands = BandHistory(this)
        vehicle = VehicleSignals(this, this)
        navi = NaviClient(this, this)
        findViewById<View>(R.id.goButton).setOnClickListener { onGo() }
        findViewById<View>(R.id.stayButton).setOnClickListener { onStay() }
        val needed = listOf("pleos.car.permission.CAR_ENERGY", "android.car.permission.CAR_ENERGY", "pleos.car.permission.CAR_INFO", "android.car.permission.CAR_INFO")
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startSignals() else permissionLauncher.launch(needed.toTypedArray())
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
        if (d.rule != lastDecision?.rule || d.emphasis != lastDecision?.emphasis) {
            Log.i(TAG, "DECISION rule=${d.rule} emphasis=${d.emphasis} state=${d.state} soc=$soc A=${a?.let { "%.1f".format(it) }} P=${power?.let { "%.1f".format(it) }} Pmax=${pmax?.let { "%.1f".format(it) }} cand=${cand?.name}/${cand?.arrivalSoc}/${cand?.maxPowerKw}")
        }
        lastDecision = d

        findViewById<View>(R.id.drivingOverlay).visibility = if (requiresDo) View.VISIBLE else View.GONE
        text(R.id.drivingSummary, a?.let { "목적지 도착 예상 SOC ${it.roundToInt()}% · ${if (destinationTrip?.chargingStop == null) "충전 경유 없음" else "경유 충전 포함 경로"}" } ?: "Stay·Go 판단은 정차 중에 보여 줍니다")

        val dot = findViewById<View>(R.id.statusDot)
        (dot.background.mutate() as GradientDrawable).setColor(color(when (charging) { ChargingStatus.CHARGING -> R.color.green; ChargingStatus.FINISHED -> R.color.blue; else -> R.color.muted }))
        text(R.id.statusText, when (charging) {
            ChargingStatus.CHARGING -> "급속충전 중 · 정차(조작 가능)"
            ChargingStatus.FINISHED -> "충전 종료 · 정차"
            else -> "충전 중이 아닙니다 · 정차"
        })
        text(R.id.destChip, destination?.let { "목적지 ${it.name} · ${(it.remainDistanceM / 1000.0).roundToInt()} km" } ?: "목적지 없음")

        findViewById<TextView>(R.id.socText).text = soc?.let { "$it%" } ?: "--%"
        findViewById<SocBar>(R.id.socBar).apply { socPercent = soc; this.charging = charging == ChargingStatus.CHARGING }
        text(R.id.powerText, power?.let { "충전전력 ${it.roundToInt()} kW" } ?: "충전전력 확인 중")
        findViewById<View>(R.id.powerTag).visibility = if (power != null && powerEstimated()) View.VISIBLE else View.GONE
        text(R.id.powerNote, when {
            power == null || pmax == null -> "배터리 잔량 변화로 전력을 계산합니다"
            !estimator.pmaxTrusted -> "세션 최대를 믿을 수 없어 SOC 기준으로 판단"
            else -> "세션 최대 ${pmax.roundToInt()} kW의 ${(power / pmax * 100).roundToInt()}%"
        })
        findViewById<PowerChart>(R.id.powerChart).update(powerHistory, pmax, params.lateRatio)

        renderArrival(d, a)
        renderRoute(d, cand)
        renderButtons(d, power)
        renderFooter(d, charging)
    }

    private fun renderArrival(d: Decision, a: Double?) {
        val arrival = findViewById<TextView>(R.id.arrivalText)
        val note = findViewById<TextView>(R.id.arrivalNote)
        when {
            destination == null -> { arrival.text = "목적지를 설정하면 계산합니다"; note.text = "내비에 목적지가 없어 Go를 잠갔습니다"; note.setTextColor(color(R.color.muted)) }
            a == null -> { arrival.text = "내비 계산을 기다리는 중"; note.text = " " }
            else -> {
                val ok = a >= params.reserveSocPct
                val hex = if (ok) "#1F6FD1" else "#B26A00"
                arrival.text = Html.fromHtml("목적지에 <b><font color='$hex'>${a.roundToInt()}%</font></b>로 도착", Html.FROM_HTML_MODE_LEGACY)
                val basis = if (destinationTrip?.chargingStop != null) "충전 경유 없이 갈 때 · " else ""
                if (ok) {
                    note.text = "${basis}예비 SOC ${params.reserveSocPct.roundToInt()}%p 충족"
                    note.setTextColor(color(R.color.green_text))
                } else {
                    val wait = d.extraWaitMinutes?.let { " · 현재 전력으로 약 ${"%.1f".format(it)}분 더" } ?: ""
                    note.text = "${basis}예비 SOC까지 ${"%.1f".format(params.reserveSocPct - a)}%p 부족$wait"
                    note.setTextColor(color(R.color.amber))
                }
            }
        }
    }

    private fun renderRoute(d: Decision, cand: Candidate?) {
        val title = findViewById<TextView>(R.id.routeText)
        val note = findViewById<TextView>(R.id.routeNote)
        val trip = destinationTrip
        when {
            destination == null || trip == null -> { title.text = " "; note.text = " " }
            trip.chargingStop == null -> {
                title.text = "충전 경유 없이 목적지 직행"
                note.text = "직행 도착 SOC가 10% 이상이라 경유 충전안이 없습니다"
                note.setTextColor(color(R.color.muted))
            }
            else -> {
                title.text = "경유 충전 · ${shortName(trip.chargingStop.name)}"
                val c = d.comparison
                note.setTextColor(color(if (c != null && c.gainMinutes > 0) R.color.blue else R.color.muted))
                note.text = when {
                    cand?.arrivalSoc == null -> "경유지 도착 SOC를 계산하는 중"
                    c != null && c.gainMinutes > 0 -> "지금 출발해 경유지에서 채우면 약 ${"%.1f".format(c.gainMinutes)}분 빠름 (최대 ${cand.maxPowerKw?.roundToInt()} kW · ${cand.chargerCount}기)"
                    c != null -> "여기서 채우면 ${"%.1f".format(c.hereMinutes)}분, 경유지는 ${"%.1f".format(c.candidateMinutes)}분 (정차 ${params.stopOverheadMin.roundToInt()}분 포함)"
                    cand.maxPowerKw == null -> "경유지 도착 SOC ${cand.arrivalSoc.roundToInt()}% · 최대 출력 미확인으로 비교 생략"
                    else -> "경유지 도착 SOC ${cand.arrivalSoc.roundToInt()}% · 예비 SOC 미만이라 비교 생략"
                }
            }
        }
    }

    private fun renderButtons(d: Decision, power: Double?) {
        val stayFilled = d.emphasis == Emphasis.STAY || d.emphasis == Emphasis.BOTH_STAY
        val goFilled = d.emphasis == Emphasis.GO
        styleButton(R.id.stayButton, R.id.stayTitle, R.id.staySub, stayFilled, R.color.green)
        styleButton(R.id.goButton, R.id.goTitle, R.id.goSub, goFilled, R.color.blue)
        text(R.id.stayTitle, if (stayFilled) "Stay · 더 충전  권장" else "Stay · 더 충전")
        text(R.id.goTitle, if (goFilled) "Go · 지금 출발  권장" else "Go · 지금 출발")
        text(R.id.staySub, power?.let { "1%p당 약 ${DecisionModel.secondsPerPercent(capacityWh / 1000.0, it).roundToInt()}초 (현재 전력 기준)" } ?: " ")
        text(R.id.goSub, when {
            destination == null -> "목적지가 없어 잠김"
            d.rule == 5 -> "${shortName(destinationTrip?.chargingStop?.name ?: "")}을 거치는 경로를 내비에 전달"
            else -> "목적지 경로를 내비에 전달"
        })
        findViewById<View>(R.id.goButton).alpha = if (destination == null) 0.45f else 1f
    }

    private fun renderFooter(d: Decision, charging: ChargingStatus) {
        val since = chargingSinceMs
        val foot = findViewById<TextView>(R.id.footText)
        if (since == null || charging != ChargingStatus.CHARGING) {
            foot.text = "앱은 충전 중에만 Stay·Go를 판단합니다"
            foot.setTextColor(color(R.color.muted))
        } else {
            val min = ((SystemClock.elapsedRealtime() - since) / 60_000L).toInt()
            val warn = min >= 50
            foot.text = if (warn) "충전 확인 후 ${min}분 · 급속충전구역 1시간 점유 제한까지 ${60 - min}분" else "충전 확인 후 ${min}분 경과 · 급속충전구역 1시간 점유 제한 안내"
            foot.setTextColor(color(if (warn) R.color.amber else R.color.muted))
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

    private fun styleButton(container: Int, title: Int, sub: Int, filled: Boolean, accent: Int) {
        val bg = GradientDrawable().apply {
            cornerRadius = 32f
            if (filled) { setColor(color(accent)); setStroke(4, color(accent)) } else { setColor(Color.WHITE); setStroke(4, color(R.color.line)) }
        }
        findViewById<LinearLayout>(container).background = bg
        findViewById<TextView>(title).setTextColor(if (filled) Color.WHITE else color(R.color.ink))
        findViewById<TextView>(sub).setTextColor(if (filled) 0xE6FFFFFF.toInt() else color(R.color.ink_soft))
    }

    private fun showToast(message: String) {
        val t = findViewById<TextView>(R.id.toast)
        t.text = message
        t.visibility = View.VISIBLE
        toastHide?.let { main.removeCallbacks(it) }
        toastHide = Runnable { t.visibility = View.GONE }.also { main.postDelayed(it, 2_500L) }
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
