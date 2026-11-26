package ai.staygo

import ai.staygo.core.ArrivalBasis
import ai.staygo.core.GoAvailability
import ai.staygo.core.OutputSource
import ai.staygo.core.RouteState
import ai.staygo.core.Screen
import ai.staygo.core.StayGoUiState
import ai.staygo.core.UiNotice
import ai.staygo.decision.ChargingStatus
import ai.staygo.ui.HeroPresenter
import ai.staygo.ui.PowerChart
import ai.staygo.ui.SocBar
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Renders [StayGoUiState] into the layout for the current window and forwards taps to [StayGoViewModel].
 * The activity keeps no app state: window size, orientation and day/night changes re-inflate the layout in
 * place and draw the same state again.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: StayGoViewModel by viewModels()
    private val main = Handler(Looper.getMainLooper())
    private var narrow = false
    private var compact = false
    private var toastHide: Runnable? = null
    private var heroAction: HeroPresenter.Action? = null
    private var infoShown = false
    /** The system no longer shows the permission dialog: the only way left is the app's settings page. */
    private var permissionDialogBlocked = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val missing = missingPermissions()
        permissionDialogBlocked = missing.any { !shouldShowRequestPermissionRationale(it) }
        viewModel.onPermissions(missing.isEmpty())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SourceProvider.prepare(this)
        super.onCreate(savedInstanceState)
        inflate()
        // Missing permissions are explained on the screen first; the system dialog opens from its button.
        if (viewModel.permissionMode == PermissionMode.REQUEST) viewModel.onPermissions(missingPermissions().isEmpty())
        // Back hides the app instead of finishing it, so the charging session is still there on return.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (infoShown) showInfo(false) else moveTaskToBack(true)
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.notices.collect(::showNotice) }
            }
        }
    }

    /** Picks the layout for the current window (narrow/portrait vs wide) and day/night colors. */
    private fun inflate() {
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.infoButton).setOnClickListener { showInfo(true) }
        findViewById<View>(R.id.infoClose).setOnClickListener { showInfo(false) }
        show(R.id.infoOverlay, infoShown)
        findViewById<View>(R.id.goButton).setOnClickListener { viewModel.go() }
        findViewById<View>(R.id.stayButton).setOnClickListener { viewModel.stay() }
        findViewById<View>(R.id.stepMinus).setOnClickListener { viewModel.adjustTargetSoc(-STEP_PCT) }
        findViewById<View>(R.id.stepPlus).setOnClickListener { viewModel.adjustTargetSoc(STEP_PCT) }
        findViewById<View>(R.id.reserveMinus).setOnClickListener { viewModel.adjustReserveSoc(-STEP_PCT) }
        findViewById<View>(R.id.reservePlus).setOnClickListener { viewModel.adjustReserveSoc(STEP_PCT) }
        // The arrival tile shows the reserve it is judged against; tapping it opens the setting.
        findViewById<View>(R.id.tileArrival).setOnClickListener { showInfo(true) }
        findViewById<View>(R.id.heroAction).setOnClickListener {
            when (heroAction) {
                HeroPresenter.Action.GRANT_PERMISSION ->
                    if (permissionDialogBlocked) openPermissionSettings() else permissionLauncher.launch(missingPermissions().toTypedArray())
                HeroPresenter.Action.CONFIRM_CHARGING -> viewModel.confirmCharging()
                null -> Unit
            }
        }
        // Narrow windows (three tiles share the width): no tile notes or "추정" tag; the hero carries the reason.
        // Short narrow windows (e.g. Connect-W wide window 1040x850dp) also drop the tile visuals, the route
        // line and the footer, and keep the reason to one line, so the hero is never squeezed.
        val c = resources.configuration
        narrow = c.screenWidthDp < 1100
        compact = narrow && c.screenHeightDp < 1000
        listOf(R.id.socNote, R.id.powerNote, R.id.arrivalNote).forEach { show(it, !narrow) }
        listOf(R.id.socBar, R.id.powerChart, R.id.arrivalBar).forEach { show(it, !compact) }
        (findViewById<View>(R.id.routeText).parent as View).visibility = if (compact) View.GONE else View.VISIBLE
        (findViewById<View>(R.id.footText).parent as View).visibility = if (compact) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.reasonText).maxLines = if (compact) 1 else 2
        // Wide windows: the toast sits over the hero's route line, clear of the key numbers and the buttons.
        // Narrow windows have no such room, so it covers the top bar instead.
        (findViewById<View>(R.id.toast).layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (narrow) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.BOTTOM or Gravity.START
            topMargin = if (narrow) dp(16) else 0
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        inflate()
        render(viewModel.state.value)
    }

    override fun onStart() {
        super.onStart()
        // Returning from the permission settings with the permissions granted starts the vehicle link.
        if (viewModel.permissionMode == PermissionMode.REQUEST && missingPermissions().isEmpty()) viewModel.onPermissions(true)
        viewModel.setVisible(true)
    }

    override fun onStop() {
        // Not visible: the view model drops the vehicle and navigation listeners until the screen is back.
        if (!isChangingConfigurations) viewModel.setVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun showInfo(on: Boolean) {
        infoShown = on
        show(R.id.infoOverlay, on)
    }

    private fun missingPermissions(): List<String> =
        VEHICLE_PERMISSIONS.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun StayGoUiState.usesTargetSoc() = screen == Screen.TARGET_SOC_NEEDED || arrival?.basis == ArrivalBasis.DRIVER_TARGET

    // ---- Rendering ----

    private fun render(s: StayGoUiState) {
        val driving = s.screen == Screen.DRIVING
        show(R.id.drivingOverlay, driving)
        if (driving) {
            show(R.id.toast, false)
            showInfo(false)
        }
        text(R.id.drivingSummary, s.arrival?.socPct?.let {
            "목적지 도착 예상 SOC ${it.roundToInt()}% · ${if (s.route is RouteState.ViaStop) "경유 충전 포함 경로" else "충전 경유 없음"}"
        } ?: "Stay·Go 판단은 정차 중에 보여 줍니다")

        val hero = HeroPresenter.present(s)
        renderTopBar(s)
        renderHero(s, hero)
        renderMetrics(s)
        renderButtons(s, hero)
        renderFooter(s)
        // Narrow windows: a blocked screen has no numbers to show, and its hero needs the room for the action.
        if (narrow) {
            val blocked = s.screen == Screen.VEHICLE_LINK_UNAVAILABLE || s.screen == Screen.CONNECTING
            (findViewById<View>(R.id.tileSoc).parent as View).visibility = if (blocked) View.GONE else View.VISIBLE
        }
    }

    private fun renderTopBar(s: StayGoUiState) {
        val linked = s.vehicleLinkFailure == null
        (findViewById<View>(R.id.statusDot).background.mutate() as GradientDrawable).setColor(color(when {
            !linked -> R.color.sg_warn
            s.charging == ChargingStatus.CHARGING -> R.color.sg_stay
            s.charging == ChargingStatus.FINISHED -> R.color.sg_go
            else -> R.color.sg_text_3
        }))
        text(R.id.statusText, when {
            !linked -> "차량 연동 불가"
            s.charging == ChargingStatus.CHARGING -> "급속충전 중 · 정차"
            s.charging == ChargingStatus.FINISHED -> "충전 종료 · 정차"
            s.charging == ChargingStatus.UNKNOWN -> "충전 상태 확인 중"
            else -> "충전 중 아님 · 정차"
        })
        text(R.id.destChip, s.destination?.let { "${it.name} · ${(it.remainDistanceM / 1000.0).roundToInt()} km" } ?: "목적지 없음")
    }

    private fun renderHero(s: StayGoUiState, hero: HeroPresenter.Hero) {
        val pill = findViewById<TextView>(R.id.verdictPill)
        val (label, bg, fg) = when (hero.verdict) {
            HeroPresenter.Verdict.GO -> Triple("GO", if (hero.strong) R.color.sg_go else R.color.sg_card_high, if (hero.strong) R.color.sg_on_go else R.color.sg_go_text)
            HeroPresenter.Verdict.STAY -> Triple("STAY", if (hero.strong) R.color.sg_stay else R.color.sg_card_high, if (hero.strong) R.color.sg_on_stay else R.color.sg_text)
            HeroPresenter.Verdict.WAIT -> Triple(if (hero.chipTone == HeroPresenter.Tone.WARNING) "확인" else "대기", R.color.sg_card_high, R.color.sg_text_2)
        }
        pill.text = label
        setPill(pill, color(bg))
        pill.setTextColor(color(fg))
        text(R.id.headlineText, hero.headline)
        text(R.id.reasonText, hero.reason)
        val chip = findViewById<TextView>(R.id.heroChip)
        // Short windows: the chip gives way to the action or the stepper the driver needs.
        show(R.id.heroChip, hero.chip != null && !(narrow && (hero.action != null || s.usesTargetSoc())))
        if (hero.chip != null) {
            chip.text = hero.chip
            val (cbg, cfg) = when (hero.chipTone) {
                HeroPresenter.Tone.WARNING -> R.color.sg_warn to R.color.sg_on_warn
                HeroPresenter.Tone.POSITIVE -> R.color.sg_stay to R.color.sg_on_stay
                HeroPresenter.Tone.NEUTRAL -> R.color.sg_card_high to R.color.sg_text_2
            }
            setPill(chip, color(cbg))
            chip.setTextColor(color(cfg))
        }
        heroAction = hero.action
        show(R.id.heroAction, hero.action != null)
        hero.action?.let { text(R.id.heroAction, it.label) }

        // The target SOC replaces the navigation's estimate, so its stepper sits on the main screen. The reserve
        // SOC is a preference and lives in the settings sheet (info button or the arrival tile).
        val target = s.usesTargetSoc() && s.charging == ChargingStatus.CHARGING && hero.action == null
        show(R.id.stepRow, target)
        text(R.id.stepLabel, "목표 SOC ${s.manualTargetSoc?.let { "$it%" } ?: "미설정"}")
        findViewById<View>(R.id.stepMinus).contentDescription = "목표 SOC 5% 낮추기"
        findViewById<View>(R.id.stepPlus).contentDescription = "목표 SOC 5% 높이기"
        text(R.id.reserveLabel, "${s.reserveSocPct}%")
        text(R.id.routeText, routeLine(s))
    }

    /** One line about the navigation route (charging stop or direct). */
    private fun routeLine(s: StayGoUiState): String = when (val r = s.route) {
        RouteState.None -> if (s.go == GoAvailability.LOCKED_NAVI_UNAVAILABLE) "내비에 연결할 수 없습니다" else "내비 경로 정보를 기다리는 중"
        RouteState.Direct -> "내비 경로 · 충전 경유 없이 목적지 직행"
        is RouteState.ViaStop -> {
            val name = shortName(r.stopName)
            val c = r.comparison
            when {
                r.arrivalSocPct == null -> "내비 경로 · 경유 충전 $name · 경유지 도착 SOC 계산 중"
                c != null && c.gainMinutes > 0 -> "내비 경로 · 경유 충전 $name (도착 SOC ${r.arrivalSocPct.roundToInt()}%)"
                c != null -> "내비 경로 · 여기서 ${"%.1f".format(c.hereMinutes)}분 / $name ${"%.1f".format(c.candidateMinutes)}분 (정차 ${s.stopOverheadMin.roundToInt()}분 포함)"
                r.maxPowerKw == null -> "내비 경로 · 경유 충전 $name (도착 SOC ${r.arrivalSocPct.roundToInt()}%) · 최대 출력 미확인"
                else -> "내비 경로 · 경유 충전 $name (도착 SOC ${r.arrivalSocPct.roundToInt()}%)"
            }
        }
    }

    private fun renderMetrics(s: StayGoUiState) {
        val charging = s.charging == ChargingStatus.CHARGING
        text(R.id.socText, s.socPercent?.let { "$it%" } ?: "--%")
        findViewById<SocBar>(R.id.socBar).apply { socPercent = s.socPercent; this.charging = charging }
        val mismatch = s.socMismatch
        text(R.id.socNote, when {
            s.vehicleLinkFailure != null -> "차량 값을 표시하지 않습니다"
            mismatch != null -> "차량 표시 ${mismatch.pleosSoc}% · 잔량 기준 ${mismatch.levelSoc.roundToInt()}% (내비는 잔량 기준)"
            charging -> s.batteryLevelKwh?.let { level -> "${"%.1f".format(level)} / ${s.capacityKwh?.roundToInt() ?: "--"} kWh" } ?: "충전 중"
            s.charging == ChargingStatus.FINISHED -> "충전이 끝났습니다"
            else -> "충전을 시작하면 판단합니다"
        })

        val power = s.power
        text(R.id.powerText, power?.kw?.roundToInt()?.toString() ?: "--")
        val trend = if (power != null) HeroPresenter.trend(s.powerHistory.map { it.elapsedMs to it.kw }) else null
        text(R.id.powerTrend, when (trend) { HeroPresenter.Trend.DOWN -> "↓"; HeroPresenter.Trend.UP -> "↑"; HeroPresenter.Trend.FLAT -> "→"; null -> " " })
        show(R.id.powerTag, !narrow && power?.estimated == true)
        val pmax = power?.sessionMaxKw
        text(R.id.powerNote, when {
            !charging -> " "
            power == null -> "충전전력을 읽지 못해 SOC 기준으로 판단"
            pmax == null -> "배터리 잔량 변화로 전력을 계산합니다"
            !power.sessionMaxTrusted -> "세션 최대를 믿을 수 없어 SOC 기준으로 판단"
            else -> "최대 ${pmax.roundToInt()} kW의 ${(power.kw / pmax * 100).roundToInt()}%"
        })
        findViewById<PowerChart>(R.id.powerChart).update(s.powerHistory.map { it.elapsedMs to it.kw }, pmax, s.lateRatio)

        val a = s.arrival
        val target = a?.basis == ArrivalBasis.DRIVER_TARGET
        text(R.id.arrivalText, when {
            target -> "--%"
            else -> a?.socPct?.let { if (it < 0) "0% 미만" else "${it.roundToInt()}%" } ?: "--%"
        })
        findViewById<SocBar>(R.id.arrivalBar).apply {
            socPercent = if (target) null else a?.socPct?.roundToInt()
            markerPercent = s.reserveSocPct
            fillColor = color(if (a?.reserveMet == true) R.color.sg_stay else R.color.sg_warn)
        }
        text(R.id.arrivalNote, when {
            target || s.screen == Screen.TARGET_SOC_NEEDED -> "내비 도착 예상 없음 · 목표 SOC로 판단"
            a?.socPct != null && a.reserveMet -> "${basisPrefix(a.basis)}예비 ${s.reserveSocPct}% 충족"
            a?.socPct != null -> "${basisPrefix(a.basis)}예비 ${s.reserveSocPct}%까지 ${"%.1f".format(a.deficitPct ?: 0.0)}%p"
            s.destination == null -> "내비에 목적지를 설정하면 계산합니다"
            else -> "내비 계산을 기다리는 중"
        })
    }

    /** With a charging stop on the route the navigation's own figure includes charging; ours is without it. */
    private fun basisPrefix(basis: ArrivalBasis) = if (basis == ArrivalBasis.NAVI_ROUTE_ENERGY) "경유 충전 없이 갈 때 · " else ""

    private fun renderButtons(s: StayGoUiState, hero: HeroPresenter.Hero) {
        val stayPrimary = hero.verdict == HeroPresenter.Verdict.STAY && hero.strong
        val goPrimary = hero.verdict == HeroPresenter.Verdict.GO && hero.strong
        styleButton(R.id.stayButton, R.id.stayIcon, R.id.stayTitle, R.id.staySub, stayPrimary, R.color.sg_stay, R.color.sg_on_stay)
        styleButton(R.id.goButton, R.id.goIcon, R.id.goTitle, R.id.goSub, goPrimary, R.color.sg_go, R.color.sg_on_go)
        text(R.id.staySub, s.power?.secondsPerPercent?.let { "1%p당 약 ${it.roundToInt()}초" } ?: "현재 전력으로 계속 충전")
        text(R.id.goSub, when (s.go) {
            GoAvailability.LOCKED_NO_DESTINATION -> "목적지가 없어 잠김"
            GoAvailability.LOCKED_NAVI_UNAVAILABLE -> "내비에 연결할 수 없어 잠김"
            GoAvailability.LOCKED_NOT_CHARGING -> "충전 중에 사용할 수 있습니다"
            GoAvailability.VIA_STOP -> "${shortName((s.route as? RouteState.ViaStop)?.stopName ?: "")} 경유 경로를 내비로"
            GoAvailability.DIRECT -> "목적지 경로를 내비로"
        })
        enable(R.id.stayButton, s.charging == ChargingStatus.CHARGING)
        enable(R.id.goButton, s.go == GoAvailability.DIRECT || s.go == GoAvailability.VIA_STOP)
        findViewById<View>(R.id.stayButton).contentDescription = "Stay, 더 충전" + if (stayPrimary) ", 권장" else ""
        findViewById<View>(R.id.goButton).contentDescription = "Go, 지금 출발" + if (goPrimary) ", 권장" else ""
    }

    private fun renderFooter(s: StayGoUiState) {
        val foot = findViewById<TextView>(R.id.footText)
        val o = s.occupancy
        if (o == null) {
            foot.text = "앱은 충전 중에만 Stay·Go를 판단합니다"
            foot.setTextColor(color(R.color.sg_text_3))
        } else {
            foot.text = if (o.warning) {
                "충전 확인 후 ${o.minutesObserved}분 · 급속충전구역 1시간 점유 제한까지 ${(o.limitMinutes - o.minutesObserved).coerceAtLeast(0)}분 (앱을 연 뒤 기준)"
            } else {
                "충전 확인 후 ${o.minutesObserved}분 · 급속충전구역 1시간 점유 제한 안내"
            }
            foot.setTextColor(color(if (o.warning) R.color.sg_text else R.color.sg_text_3))
        }
        text(R.id.ruleText, when (s.decision.rule) {
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
        // The state is drawn every second; the button is restyled only when its emphasis changes.
        val button = findViewById<LinearLayout>(container)
        if (button.tag == primary) return
        button.tag = primary
        val radius = resources.getDimension(R.dimen.btn_radius)
        val shape = GradientDrawable().apply {
            cornerRadius = radius
            setColor(color(if (primary) fill else R.color.sg_basic))
            if (!primary) setStroke(dp(2), color(R.color.sg_line))
        }
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
        button.background = RippleDrawable(ColorStateList.valueOf(color(R.color.sg_basic_pressed)), shape, mask)
        val fg = color(if (primary) onFill else R.color.sg_text)
        findViewById<TextView>(title).setTextColor(fg)
        findViewById<TextView>(sub).setTextColor(if (primary) fg else color(R.color.sg_text_3))
        findViewById<ImageView>(icon).imageTintList =
            ColorStateList.valueOf(if (primary) fg else color(if (fill == R.color.sg_go) R.color.sg_go_text else R.color.sg_text_2))
    }

    private fun enable(id: Int, on: Boolean) = findViewById<View>(id).apply { isEnabled = on; alpha = if (on) 1f else 0.4f }

    private fun setPill(view: View, fill: Int) {
        if (view.tag == fill) return
        view.tag = fill
        view.background = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); setColor(fill) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    // ---- One-shot notices ----

    private fun showNotice(notice: UiNotice) {
        when (notice) {
            is UiNotice.GoRecommended -> showToast(
                "지금 출발해도 됩니다 · ${notice.arrivalSocPct?.let { "목적지 ${it.roundToInt()}% 도착" } ?: "충전 후반"}", R.drawable.ic_navigate,
            )
            is UiNotice.RouteSent -> showToast(
                if (notice.viaStopName == null) "목적지 경로를 내비에 전달했습니다" else "${shortName(notice.viaStopName)} 경유 경로를 내비에 전달했습니다",
                R.drawable.ic_navigate,
            )
            is UiNotice.RouteFailed -> showToast("경로를 전달하지 못했습니다 · 내비에서 직접 선택해 주세요")
            UiNotice.StayAcknowledged -> showToast("계속 충전합니다 · SOC가 바뀌면 다시 판단합니다", R.drawable.ic_plug)
            UiNotice.DestinationRequired -> showToast("내비에 목적지를 먼저 설정해 주세요")
            UiNotice.NaviUnavailable -> showToast("내비에 연결할 수 없습니다 · 내비에서 직접 선택해 주세요")
        }
    }

    private fun showToast(message: String, icon: Int? = null) {
        val t = findViewById<TextView>(R.id.toast)
        // In wide windows the toast stays inside the hero column so it never covers the key numbers.
        if (!narrow) t.maxWidth = (findViewById<View>(R.id.heroCard).width - dp(16)).coerceAtLeast(dp(320))
        t.text = message
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(icon ?: 0, 0, 0, 0)
        t.visibility = View.VISIBLE
        toastHide?.let { main.removeCallbacks(it) }
        toastHide = Runnable { findViewById<View>(R.id.toast).visibility = View.GONE }.also { main.postDelayed(it, 4_000L) }
    }

    private fun openPermissionSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun show(id: Int, visible: Boolean) { findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE }
    /** Setting an unchanged text would still lay the view out again, once a second for every label. */
    private fun text(id: Int, value: CharSequence) {
        val view = findViewById<TextView>(id)
        if (view.text?.toString() != value.toString()) view.text = value
    }
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    /** POI names arrive as "청도새마을휴게소(부산방향) 전기차충전소"; the screen shows the rest area only. */
    private fun shortName(poi: String) = poi.replace("전기차충전소", "").replace(Regex("\\(.*?\\)"), "").trim()

    companion object {
        private const val STEP_PCT = 5
        private val VEHICLE_PERMISSIONS = listOf(
            "pleos.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_ENERGY",
            "pleos.car.permission.CAR_INFO",
            "android.car.permission.CAR_INFO",
        )
    }
}
