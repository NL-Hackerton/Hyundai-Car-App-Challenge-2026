package ai.staygo

import ai.staygo.core.ArrivalBasis
import ai.staygo.core.GoAvailability
import ai.staygo.core.RouteState
import ai.staygo.core.Screen
import ai.staygo.core.StayGoUiState
import ai.staygo.core.UiNotice
import ai.staygo.decision.ChargingStatus
import ai.staygo.ui.HeroPresenter
import ai.staygo.ui.PowerChart
import ai.staygo.ui.SocBar
import android.content.ActivityNotFoundException
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
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.text.TextUtils
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
 *
 * The activity is declared distraction-optimized, so it must be safe at any moment of a drive. The layout
 * starts with the lock overlay visible, and it is removed only for a state the engine marks as parked with a
 * known restriction; while locked, nothing under the overlay can be reached by touch, focus or accessibility.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: StayGoViewModel by viewModels()
    private val main = Handler(Looper.getMainLooper())
    private var narrow = false
    private var compact = false
    private var toastHide: Runnable? = null
    private var lockUnknownNote: Runnable? = null
    private var blockedReason: String? = null
    private var inlineNotice: String? = null
    private var chipSlotFree = true
    private var heroAction: HeroPresenter.Action? = null
    private var infoShown = false
    /** The system no longer shows the permission dialog: the only way left is the app's settings page. */
    private var permissionDialogBlocked = false
    private var permissionRequests = 0

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val missing = missingPermissions()
        // "Blocked" needs evidence: the request came back refused, without a rationale to show, twice. One such
        // result can also be a dialog the driver closed without choosing.
        if (missing.isNotEmpty() && missing.none { shouldShowRequestPermissionRationale(it) }) {
            if (++permissionRequests >= 2) permissionDialogBlocked = true
        } else {
            permissionRequests = 0
        }
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
        // The fresh layout shows the default lock text again; the delayed "cannot confirm" note is re-armed.
        lockUnknownNote?.let { main.removeCallbacks(it) }
        lockUnknownNote = null
        tap(R.id.infoButton) { showInfo(true) }
        tap(R.id.infoClose) { showInfo(false) }
        // On a blocked screen neither button can act; a tap answers with why.
        tap(R.id.goButton) { blockedReason?.let { showToast(it) } ?: viewModel.go() }
        tap(R.id.stayButton) { blockedReason?.let { showToast(it) } ?: viewModel.stay() }
        tap(R.id.stepMinus) { viewModel.adjustTargetSoc(-STEP_PCT) }
        tap(R.id.stepPlus) { viewModel.adjustTargetSoc(STEP_PCT) }
        tap(R.id.reserveMinus) { viewModel.adjustReserveSoc(-STEP_PCT) }
        tap(R.id.reservePlus) { viewModel.adjustReserveSoc(STEP_PCT) }
        tap(R.id.clearHistory) { viewModel.clearChargingHistory() }
        tap(R.id.consentRevoke) { showInfo(false); viewModel.declineConsent() }
        tap(R.id.consentAccept) { viewModel.acceptConsent() }
        tap(R.id.consentDecline) { viewModel.declineConsent() }
        // The arrival tile shows the reserve it is judged against; tapping it opens the setting.
        tap(R.id.tileArrival) { showInfo(true) }
        tap(R.id.heroAction) {
            when (heroAction) {
                HeroPresenter.Action.GRANT_PERMISSION ->
                    if (permissionDialogBlocked) openPermissionSettings() else permissionLauncher.launch(missingPermissions().toTypedArray())
                HeroPresenter.Action.CONFIRM_CHARGING -> viewModel.confirmCharging()
                null -> Unit
            }
        }
        // Narrow windows (three tiles share the width): no tile notes; the hero carries the reason and the tile
        // labels carry the "추정" and "경유 없이" qualifiers. Short narrow windows (e.g. Connect-W wide window
        // 1040x850dp) also drop the tile visuals, the route line and the footer, and keep the reason to one
        // line, so the hero is never squeezed.
        val c = resources.configuration
        narrow = c.screenWidthDp < 1100
        compact = narrow && c.screenHeightDp < 1000
        show(R.id.topTitle, !narrow)
        listOf(R.id.socNote, R.id.powerNote, R.id.arrivalNote).forEach { show(it, !narrow) }
        listOf(R.id.socBar, R.id.powerChart, R.id.arrivalBar).forEach { show(it, !compact) }
        (findViewById<View>(R.id.routeText).parent as View).visibility = if (compact) View.GONE else View.VISIBLE
        (findViewById<View>(R.id.footText).parent as View).visibility = if (compact) View.GONE else View.VISIBLE
        // Narrow windows: the parking-limit caption gets the whole line; the hero already states the basis.
        show(R.id.ruleText, !narrow)
        findViewById<TextView>(R.id.reasonText).maxLines = if (compact) 1 else 2
        show(R.id.infoOverlay, infoShown)
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
        // Not visible: the view model drops every listener until the screen is back.
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
        val locked = s.screen == Screen.DRIVING || s.screen == Screen.RESTRICTION_UNKNOWN
        renderLock(s, locked)
        // The data notice is modal as well: nothing under it is reachable by focus or accessibility.
        if (s.screen == Screen.CONSENT_REQUIRED) findViewById<ViewGroup>(R.id.content).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val consent = s.screen == Screen.CONSENT_REQUIRED
        show(R.id.consentOverlay, consent)
        show(R.id.consentStatus, s.consentDeclined)
        if (consent) showInfo(false)

        val hero = HeroPresenter.present(s)
        blockedReason = if (s.screen.interactive) null else hero.headline
        renderTopBar(s)
        renderHero(s, hero)
        renderMetrics(s)
        renderButtons(s, hero)
        renderFooter(s)
        show(R.id.clearHistory, s.hasChargingHistory)
        // Narrow windows: a blocked screen has no numbers to show, and its hero needs the room for the action.
        if (narrow) (findViewById<View>(R.id.tileSoc).parent as View).visibility = if (s.screen.interactive) View.VISIBLE else View.GONE
    }

    /**
     * The lock covers everything while driving and while the driving state is unknown. Under it, the content
     * is also taken out of focus and accessibility traversal, and the toast and the settings sheet are closed.
     */
    private fun renderLock(s: StayGoUiState, locked: Boolean) {
        show(R.id.drivingOverlay, locked)
        val content = findViewById<ViewGroup>(R.id.content)
        content.importantForAccessibility =
            if (locked) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        content.descendantFocusability = if (locked) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (!locked) {
            lockUnknownNote?.let { main.removeCallbacks(it) }
            lockUnknownNote = null
            return
        }
        show(R.id.toast, false)
        showInfo(false)
        show(R.id.consentOverlay, false)
        if (s.screen == Screen.DRIVING) {
            lockUnknownNote?.let { main.removeCallbacks(it) }
            lockUnknownNote = null
            text(R.id.drivingTitle, getString(R.string.lock_driving))
            text(R.id.drivingSummary, "Stay·Go 판단은 정차 중에 보여 줍니다")
        } else if (lockUnknownNote == null) {
            text(R.id.drivingTitle, getString(R.string.lock_checking))
            text(R.id.drivingSummary, " ")
            // Still unknown after a few seconds: say so, and that the lock lifts by itself.
            lockUnknownNote = Runnable {
                if (viewModel.state.value.screen == Screen.RESTRICTION_UNKNOWN) {
                    text(R.id.drivingTitle, getString(R.string.lock_unknown))
                    text(R.id.drivingSummary, getString(R.string.lock_unknown_note))
                }
            }.also { main.postDelayed(it, 5_000L) }
        }
    }

    private fun renderTopBar(s: StayGoUiState) {
        val linked = s.vehicleLinkFailure == null && s.screen != Screen.CONSENT_REQUIRED
        (findViewById<View>(R.id.statusDot).background.mutate() as GradientDrawable).setColor(color(when {
            !linked -> R.color.sg_warn
            s.charging == ChargingStatus.CHARGING -> R.color.sg_stay
            s.charging == ChargingStatus.FINISHED -> R.color.sg_go
            else -> R.color.sg_text_3
        }))
        text(R.id.statusText, when {
            !linked -> "차량 연동 안 됨"
            s.charging == ChargingStatus.CHARGING -> if (narrow) "급속충전 중" else "급속충전 중 · 정차"
            s.charging == ChargingStatus.FINISHED -> if (narrow) "충전 종료" else "충전 종료 · 정차"
            s.screen == Screen.CHARGING_UNCONFIRMED -> "충전 상태 읽기 실패"
            s.charging == ChargingStatus.UNKNOWN -> "확인 중"
            else -> if (narrow) "충전 전" else "충전 전 · 정차"
        })
        val d = s.destination
        // Narrow chip: the name alone, so no number is ever cut mid-digit; the distance needs the wide bar.
        text(R.id.destChip, when {
            d != null -> if (narrow) d.name else "${d.name} · ${(d.remainDistanceM / 1000.0).roundToInt()} km"
            !linked || !s.naviAvailable -> "목적지 미확인"
            else -> "목적지 없음"
        })
    }

    private fun renderHero(s: StayGoUiState, hero: HeroPresenter.Hero) {
        val pill = findViewById<TextView>(R.id.verdictPill)
        // The pill and the Stay button always agree: a Stay the button highlights is drawn filled here too.
        val filled = hero.strong || (hero.verdict == HeroPresenter.Verdict.STAY && s.stayEmphasized)
        val (bg, fg) = when (hero.verdict) {
            HeroPresenter.Verdict.GO -> (if (filled) R.color.sg_go else R.color.sg_card_high) to (if (filled) R.color.sg_on_go else R.color.sg_go_text)
            HeroPresenter.Verdict.STAY -> (if (filled) R.color.sg_stay else R.color.sg_card_high) to (if (filled) R.color.sg_on_stay else R.color.sg_text)
            HeroPresenter.Verdict.CHOICE, HeroPresenter.Verdict.WAIT -> R.color.sg_card_high to R.color.sg_text_2
        }
        text(R.id.verdictPill, hero.label)
        setPill(pill, color(bg))
        pill.setTextColor(color(fg))
        text(R.id.headlineText, hero.headline)
        text(R.id.reasonText, hero.reason)
        val chip = findViewById<TextView>(R.id.heroChip)
        // Short windows: the chip gives way to the action or the stepper the driver needs.
        val chipSlot = !(narrow && (hero.action != null || s.usesTargetSoc()))
        chipSlotFree = chipSlot
        // Narrow windows have no free strip for a toast, so a notice takes the chip's place for a few seconds.
        val notice = inlineNotice.takeIf { narrow && chipSlot }
        show(R.id.heroChip, chipSlot && (hero.chip != null || notice != null))
        if (notice != null) {
            chip.text = notice
            setPill(chip, color(R.color.sg_toast))
            chip.setTextColor(color(R.color.sg_on_toast))
        } else if (hero.chip != null) {
            chip.text = hero.chip
            // A met reserve under a GO verdict takes the Go color, so the chip never looks like the Stay button.
            val (cbg, cfg) = when {
                hero.chipTone == HeroPresenter.Tone.WARNING -> R.color.sg_warn to R.color.sg_on_warn
                hero.chipTone == HeroPresenter.Tone.POSITIVE && hero.verdict == HeroPresenter.Verdict.GO -> R.color.sg_go to R.color.sg_on_go
                hero.chipTone == HeroPresenter.Tone.POSITIVE -> R.color.sg_stay to R.color.sg_on_stay
                else -> R.color.sg_card_high to R.color.sg_text_2
            }
            setPill(chip, color(cbg))
            chip.setTextColor(color(cfg))
        }
        heroAction = hero.action
        show(R.id.heroAction, hero.action != null)
        hero.action?.let { text(R.id.heroAction, it.label) }

        // The target SOC replaces the navigation's estimate, so its stepper sits on the main screen. The reserve
        // SOC is a preference and lives in the settings sheet (settings button or the arrival tile).
        val target = s.usesTargetSoc() && s.charging == ChargingStatus.CHARGING && hero.action == null
        show(R.id.stepRow, target)
        // The shortest window has room for the headline and the stepper, not for a reason line between them.
        show(R.id.reasonText, !(compact && target))
        text(R.id.stepLabel, s.manualTargetSoc?.let { "목표 SOC $it%" } ?: "목표 SOC 정하기")
        findViewById<View>(R.id.stepMinus).contentDescription = "목표 SOC 5% 낮추기"
        findViewById<View>(R.id.stepPlus).contentDescription = "목표 SOC 5% 높이기"
        text(R.id.reserveLabel, "${s.reserveSocPct}%")
        text(R.id.routeText, routeLine(s))
    }

    /** One line about the navigation route (charging stop or direct). */
    private fun routeLine(s: StayGoUiState): String = when (val r = s.route) {
        RouteState.None -> when {
            !s.screen.interactive -> " "
            !s.naviAvailable -> "내비 응답 없음"
            s.destination == null -> "내비 목적지 없음"
            s.usesTargetSoc() -> "내비가 도착 SOC를 주지 않음 · 목표 SOC로 판단"
            else -> "내비 경로 계산 중"
        }
        RouteState.Direct -> "내비 경로 · 충전 경유 없이 직행"
        is RouteState.ViaStop -> {
            val name = HeroPresenter.shortStopName(r.stopName)
            val c = r.comparison
            when {
                r.arrivalSocPct == null -> "내비 경로 · $name 경유 충전"
                c != null && c.gainMinutes <= 0 -> "여기서 ${HeroPresenter.minutes(c.hereMinutes)}분 / $name ${HeroPresenter.minutes(c.candidateMinutes)}분(정차 ${s.stopOverheadMin.roundToInt()}분 포함)"
                else -> "경유지 도착 SOC ${r.arrivalSocPct.roundToInt()}% · 내비 경로 $name 경유"
            }
        }
    }

    private fun renderMetrics(s: StayGoUiState) {
        val charging = s.charging == ChargingStatus.CHARGING
        text(R.id.socText, s.socPercent?.let { "$it%" } ?: "--%")
        findViewById<SocBar>(R.id.socBar).apply { socPercent = s.socPercent; this.charging = charging }
        val mismatch = s.socMismatch
        text(R.id.socNote, when {
            !s.screen.interactive -> " "
            mismatch != null -> "차량 표시 SOC ${mismatch.pleosSoc}% · 전력량 기준 ${mismatch.levelSoc.roundToInt()}%(내비 기준)"
            charging -> s.batteryLevelKwh?.let { level -> "${"%.1f".format(level)} / ${s.capacityKwh?.roundToInt() ?: "--"} kWh" } ?: " "
            s.charging == ChargingStatus.FINISHED -> "충전이 끝났습니다"
            s.screen == Screen.CHARGING_UNCONFIRMED -> "충전 여부를 읽지 못했습니다"
            else -> "충전을 시작하면 판단합니다"
        })

        val power = s.power
        text(R.id.powerText, power?.kw?.roundToInt()?.toString() ?: "--")
        val history = s.powerHistory.map { it.elapsedMs to it.kw }
        val trend = if (power != null) HeroPresenter.trend(history) else null
        val arrow = when (trend) { HeroPresenter.Trend.DOWN -> "↓"; HeroPresenter.Trend.UP -> "↑"; HeroPresenter.Trend.FLAT -> "→"; null -> "" }
        // Narrow tiles have no room for the arrow next to the number, so it joins the label; "추정" never
        // disappears either: a tag in wide windows, part of the label where there is no room for the tag.
        show(R.id.powerTrend, !narrow)
        text(R.id.powerTrend, arrow.ifEmpty { " " })
        val estimated = power?.estimated == true
        show(R.id.powerTag, !narrow && estimated)
        text(R.id.powerLabel, when {
            !narrow -> getString(R.string.label_power)
            else -> getString(if (estimated) R.string.label_power_estimated_short else R.string.label_power_short) + if (arrow.isNotEmpty()) " $arrow" else ""
        })
        val pmax = power?.sessionMaxKw
        text(R.id.powerNote, when {
            !charging -> " "
            power == null -> "충전 전력 측정 중 · SOC 기준으로 판단"
            pmax == null -> "배터리 전력량 변화로 계산합니다"
            !power.sessionMaxTrusted -> "이번 충전 최대를 알 수 없어 SOC 기준으로 판단"
            else -> "이번 충전 최대 ${pmax.roundToInt()} kW의 ${(power.kw / pmax * 100).roundToInt().coerceAtMost(100)}%"
        })
        findViewById<PowerChart>(R.id.powerChart).apply {
            emptyText = if (charging) "충전 전력 기록을 모으는 중" else ""
            update(if (charging) history else emptyList(), pmax, s.lateRatio)
        }

        val a = s.arrival
        val target = a?.basis == ArrivalBasis.DRIVER_TARGET
        val reserve = s.reserveSocPct.toDouble()
        val withoutStop = a?.basis == ArrivalBasis.NAVI_ROUTE_ENERGY
        text(R.id.arrivalLabel, getString(if (withoutStop) R.string.label_arrival_without_stop else R.string.label_arrival))
        text(R.id.arrivalText, if (target) "--%" else a?.socPct?.let { HeroPresenter.soc(it, reserve) } ?: "--%")
        findViewById<SocBar>(R.id.arrivalBar).apply {
            socPercent = if (target) null else a?.socPct?.roundToInt()
            markerPercent = s.reserveSocPct
            fillColor = color(if (a?.reserveMet == true) R.color.sg_stay else R.color.sg_warn)
        }
        text(R.id.arrivalNote, when {
            !s.screen.interactive -> " "
            target || s.screen == Screen.TARGET_SOC_NEEDED -> "도착 SOC 계산 불가 · 목표 SOC로 판단"
            a?.socPct != null && a.reserveMet -> "예비 SOC ${s.reserveSocPct}% 충족"
            a?.socPct != null -> "예비 SOC ${s.reserveSocPct}%까지 ${HeroPresenter.shortfall(a.socPct, s.reserveSocPct)}"
            s.destination == null -> "내비 목적지 없음"
            else -> "내비 경로 계산 중"
        })
        findViewById<View>(R.id.tileArrival).contentDescription =
            "도착 SOC ${a?.socPct?.let { HeroPresenter.soc(it, reserve) } ?: "없음"}, 예비 SOC ${s.reserveSocPct}%, 눌러서 예비 SOC 설정"
    }

    private fun renderButtons(s: StayGoUiState, hero: HeroPresenter.Hero) {
        // BOTH_STAY shows both options with Stay as the default; a choice between two options highlights neither.
        val choice = hero.verdict == HeroPresenter.Verdict.CHOICE
        val stayPrimary = s.stayEmphasized && !choice && s.screen.interactive
        val goPrimary = s.goEmphasized && s.screen.interactive
        val stayOn = s.screen.interactive && s.charging == ChargingStatus.CHARGING
        val goOn = s.screen.interactive && (s.go == GoAvailability.DIRECT || s.go == GoAvailability.VIA_STOP)
        styleButton(R.id.stayButton, R.id.stayIcon, R.id.stayTitle, R.id.staySub, stayPrimary, stayOn, R.color.sg_stay, R.color.sg_on_stay)
        styleButton(R.id.goButton, R.id.goIcon, R.id.goTitle, R.id.goSub, goPrimary, goOn, R.color.sg_go, R.color.sg_on_go)
        val via = s.route as? RouteState.ViaStop
        text(R.id.staySub, when {
            !s.screen.interactive -> " "
            !stayOn -> "충전 중에만 사용"
            choice -> s.decision.extraWaitMinutes?.let { "여기서 최소 ${HeroPresenter.minutes(it)}분" } ?: "여기서 계속 충전"
            else -> s.power?.secondsPerPercent?.let { "1%p당 약 ${it.roundToInt()}초" } ?: "계속 충전"
        })
        text(R.id.goSub, when {
            !s.screen.interactive -> " "
            s.go == GoAvailability.LOCKED_NO_DESTINATION -> "목적지 없음 · 잠김"
            s.go == GoAvailability.LOCKED_NAVI_UNAVAILABLE -> "내비 응답 없음 · 잠김"
            s.go == GoAvailability.LOCKED_NOT_CHARGING -> "충전 중에만 사용"
            s.go == GoAvailability.VIA_STOP -> via?.let { "${HeroPresenter.shortStopName(it.stopName)} 경유 경로를 내비로" } ?: "충전 경유지 포함 경로를 내비로"
            else -> "목적지 경로를 내비로"
        })
        // A locked button still answers a tap with the reason (toast), so it stays enabled for touch.
        findViewById<View>(R.id.stayButton).contentDescription = "Stay, 더 충전" + when { !stayOn -> ", 잠김"; stayPrimary -> ", 권장"; else -> "" }
        findViewById<View>(R.id.goButton).contentDescription = "Go, 지금 출발" + when { !goOn -> ", 잠김"; goPrimary -> ", 권장"; else -> "" }
    }

    private fun renderFooter(s: StayGoUiState) {
        val foot = findViewById<TextView>(R.id.footText)
        val o = s.occupancy
        val limit = (o?.limitMinutes ?: 60) / 60
        foot.setTextColor(color(if (o?.warning == true) R.color.sg_text else R.color.sg_text_3))
        text(R.id.footText, when {
            !s.screen.interactive -> " "
            o == null -> "Stay·Go는 급속충전 중에만 안내합니다"
            s.charging == ChargingStatus.FINISHED -> "충전 종료 · 급속충전구역 주차는 ${limit}시간까지"
            o.warning -> "앱에서 충전 확인 후 ${o.minutesObserved}분 · ${limit}시간까지 ${(o.limitMinutes - o.minutesObserved).coerceAtLeast(0)}분"
            else -> "앱에서 충전 확인 후 ${o.minutesObserved}분 · 급속충전구역 주차는 ${limit}시간까지"
        })
        // The basis of the verdict in plain words; rule numbers are for the documentation.
        text(R.id.ruleText, if (!s.screen.interactive) " " else when (s.decision.rule) {
            2 -> "근거 · 예비 SOC 부족"
            3 -> "근거 · 충전 전력 아직 높음"
            4 -> "근거 · 충전 전력 줄어듦"
            5 -> "근거 · 경유 충전 비교"
            6 -> "근거 · SOC 기준"
            else -> " "
        })
    }

    /**
     * Pleos basic (tonal) vs filled button; ripple gives pressed feedback well under the 0.25 s response rule.
     * A locked button is drawn flat with its title dimmed to the tertiary text color, which still meets 4.5:1;
     * its sub-line, the reason for the lock, keeps the secondary text color.
     */
    private fun styleButton(container: Int, icon: Int, title: Int, sub: Int, primary: Boolean, on: Boolean, fill: Int, onFill: Int) {
        // The state is drawn every second; the button is restyled only when its look changes.
        val button = findViewById<LinearLayout>(container)
        val key = (if (primary) 1 else 0) + (if (on) 2 else 0)
        if (button.tag == key) return
        button.tag = key
        val radius = resources.getDimension(R.dimen.btn_radius)
        val shape = GradientDrawable().apply {
            cornerRadius = radius
            setColor(color(if (primary) fill else R.color.sg_basic))
            if (!primary) setStroke(dp(2), color(R.color.sg_line))
        }
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
        button.background = RippleDrawable(ColorStateList.valueOf(color(R.color.sg_basic_pressed)), shape, mask)
        val titleColor = color(when { primary -> onFill; on -> R.color.sg_text; else -> R.color.sg_text_3 })
        findViewById<TextView>(title).setTextColor(titleColor)
        findViewById<TextView>(sub).setTextColor(if (primary) titleColor else color(R.color.sg_text_2))
        findViewById<ImageView>(icon).imageTintList = ColorStateList.valueOf(
            when {
                primary -> titleColor
                !on -> color(R.color.sg_text_3)
                else -> color(if (fill == R.color.sg_go) R.color.sg_go_text else R.color.sg_text_2)
            }
        )
    }

    private fun setPill(view: View, fill: Int) {
        if (view.tag == fill) return
        view.tag = fill
        view.background = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); setColor(fill) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    // ---- One-shot notices ----

    private fun showNotice(notice: UiNotice) {
        when (notice) {
            is UiNotice.GoRecommended -> showToast("지금 출발해도 됩니다 · 예비 SOC 충족", R.drawable.ic_navigate)
            is UiNotice.RouteSent -> showToast(
                if (notice.viaStopName == null) "목적지 경로를 내비에 전달했습니다" else "${HeroPresenter.shortStopName(notice.viaStopName)} 경유 경로를 내비에 전달했습니다",
                R.drawable.ic_navigate,
            )
            is UiNotice.RouteFailed -> showToast("경로를 전달하지 못했습니다 · 내비에서 직접 선택해 주세요")
            UiNotice.StayAcknowledged -> showToast("계속 충전합니다 · SOC가 바뀌면 다시 판단합니다", R.drawable.ic_plug)
            UiNotice.DestinationRequired -> showToast("내비에 목적지를 먼저 설정해 주세요")
            UiNotice.NaviUnavailable -> showToast("내비 응답이 없습니다 · 내비에서 직접 선택해 주세요")
            UiNotice.NotCharging -> showToast("급속충전 중에만 사용할 수 있습니다")
            UiNotice.HistoryCleared -> showToast("충전 기록을 지웠습니다")
        }
    }

    private fun showToast(message: String, icon: Int? = null) {
        toastHide?.let { main.removeCallbacks(it) }
        if (narrow && chipSlotFree) {
            inlineNotice = message
            render(viewModel.state.value)
            toastHide = Runnable { inlineNotice = null; render(viewModel.state.value) }.also { main.postDelayed(it, 4_000L) }
            return
        }
        val t = findViewById<TextView>(R.id.toast)
        // Pleos toasts sit at the bottom: inside the hero card, over its route line, never over the verdict,
        // the numbers or the buttons. A narrow window whose chip slot is taken shows it under the hero text.
        val root = t.parent as View
        val anchor = findViewById<View>(R.id.heroCard)
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        val origin = IntArray(2).also { root.getLocationInWindow(it) }
        (t.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = (root.height - (at[1] - origin[1] + anchor.height) + dp(24)).coerceAtLeast(dp(16))
            marginStart = at[0] - origin[0] + dp(24)
            leftMargin = marginStart
            marginEnd = dp(24)
        }
        t.maxWidth = (anchor.width - dp(48)).coerceAtLeast(dp(320))
        t.text = message
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(icon ?: 0, 0, 0, 0)
        t.visibility = View.VISIBLE
        toastHide?.let { main.removeCallbacks(it) }
        toastHide = Runnable { findViewById<View>(R.id.toast).visibility = View.GONE }.also { main.postDelayed(it, 4_000L) }
    }

    private fun openPermissionSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (e: ActivityNotFoundException) {
            showToast("차량 설정의 앱 권한에서 허용해 주세요")
        }
    }

    private fun tap(id: Int, action: () -> Unit) { findViewById<View>(id).setOnClickListener { action() } }
    private fun show(id: Int, visible: Boolean) { findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE }
    /** Setting an unchanged text would still lay the view out again, once a second for every label. */
    private fun text(id: Int, value: CharSequence) {
        val view = findViewById<TextView>(id)
        if (view.text?.toString() != value.toString()) view.text = value
    }
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    companion object {
        private const val STEP_PCT = 5
        /** android.car.permission.CAR_ENERGY is the only runtime (dangerous) permission; the others are granted at install. */
        private val VEHICLE_PERMISSIONS = listOf(
            "pleos.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_INFO",
        )
    }
}
