package ai.staygo

import ai.staygo.core.ArrivalBasis
import ai.staygo.core.GoAvailability
import ai.staygo.core.OutputSource
import ai.staygo.core.RouteState
import ai.staygo.core.Screen
import ai.staygo.core.StayGoUiState
import ai.staygo.core.UiNotice
import ai.staygo.core.VehicleLinkFailure
import ai.staygo.decision.ChargingStatus
import ai.staygo.ui.PowerChart
import ai.staygo.ui.SocBar
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Html
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Renders [StayGoUiState] into the XML layout and forwards taps to [StayGoViewModel].
 * The activity keeps no app state: replacing this file and the layout replaces the whole UI.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: StayGoViewModel by viewModels()
    private val main = Handler(Looper.getMainLooper())
    private var toastHide: Runnable? = null
    private var noticeAction: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onPermissions(missingPermissions().isEmpty())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SourceProvider.prepare(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.goButton).setOnClickListener { viewModel.go() }
        findViewById<View>(R.id.stayButton).setOnClickListener { viewModel.stay() }
        findViewById<View>(R.id.stepMinus).setOnClickListener { step(-STEP_PCT) }
        findViewById<View>(R.id.stepPlus).setOnClickListener { step(STEP_PCT) }
        findViewById<View>(R.id.noticeAction).setOnClickListener { noticeAction?.invoke() }

        if (viewModel.permissionMode == PermissionMode.REQUEST && savedInstanceState == null) {
            val missing = missingPermissions()
            if (missing.isEmpty()) viewModel.onPermissions(true) else permissionLauncher.launch(missing.toTypedArray())
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.notices.collect(::showNotice) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the permission settings with the permissions granted starts the vehicle link.
        if (viewModel.permissionMode == PermissionMode.REQUEST && missingPermissions().isEmpty()) viewModel.onPermissions(true)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun missingPermissions(): List<String> =
        VEHICLE_PERMISSIONS.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun step(delta: Int) {
        if (viewModel.state.value.usesTargetSoc()) viewModel.adjustTargetSoc(delta) else viewModel.adjustReserveSoc(delta)
    }

    private fun StayGoUiState.usesTargetSoc() = screen == Screen.TARGET_SOC_NEEDED || arrival?.basis == ArrivalBasis.DRIVER_TARGET

    // ---- Rendering ----

    private fun render(s: StayGoUiState) {
        show(R.id.drivingOverlay, s.screen == Screen.DRIVING)
        if (s.screen == Screen.DRIVING) show(R.id.toast, false)
        text(R.id.drivingSummary, drivingSummary(s))
        renderNotice(s)

        val chargingNow = s.charging == ChargingStatus.CHARGING
        (findViewById<View>(R.id.statusDot).background.mutate() as GradientDrawable).setColor(
            color(when (s.charging) { ChargingStatus.CHARGING -> R.color.green; ChargingStatus.FINISHED -> R.color.blue; else -> R.color.muted })
        )
        text(R.id.statusText, statusText(s))
        text(R.id.destChip, s.destination?.let { "목적지 ${it.name} · ${(it.remainDistanceM / 1000.0).roundToInt()} km" } ?: "목적지 없음")

        text(R.id.socText, s.socPercent?.let { "$it%" } ?: "--%")
        findViewById<SocBar>(R.id.socBar).apply { socPercent = s.socPercent; charging = chargingNow }
        val power = s.power
        text(R.id.powerText, power?.let { "충전전력 ${it.kw.roundToInt()} kW" } ?: if (chargingNow) "충전전력 확인 중" else "충전전력 없음")
        show(R.id.powerTag, power?.estimated == true)
        text(R.id.powerNote, powerNote(s))
        findViewById<PowerChart>(R.id.powerChart).update(s.powerHistory.map { it.elapsedMs to it.kw }, power?.sessionMaxKw, s.lateRatio)

        renderArrival(s)
        renderRoute(s)
        renderButtons(s)
        renderFooter(s)
    }

    private fun renderNotice(s: StayGoUiState) {
        val notice: Triple<String, String, Pair<String, () -> Unit>?>? = when (s.screen) {
            Screen.VEHICLE_LINK_UNAVAILABLE -> when (s.vehicleLinkFailure) {
                VehicleLinkFailure.PERMISSION_DENIED -> Triple(
                    "차량 정보를 읽을 권한이 없습니다",
                    "배터리 정보 권한을 허용해야 Stay·Go를 판단할 수 있습니다.\n차량 값은 표시하지 않습니다.",
                    "권한 설정 열기" to ::openPermissionSettings,
                )
                VehicleLinkFailure.SOC_UNSUPPORTED -> Triple("이 차량은 배터리 잔량(SOC) 조회를 지원하지 않습니다", "차량 연동을 멈췄습니다. 차량 값은 표시하지 않습니다.", null)
                else -> Triple("차량과 연결할 수 없습니다", "차량 연동을 멈췄습니다. 차량 값은 표시하지 않습니다.", null)
            }
            Screen.CONNECTING -> Triple("차량 정보를 확인하는 중", " ", null)
            Screen.CHARGING_UNCONFIRMED -> Triple(
                "충전 상태를 확인할 수 없습니다",
                "충전 상태와 배터리 전력량을 읽지 못했습니다.\n지금 급속충전 중이라면 직접 확인해 주세요.",
                "충전 중입니다" to viewModel::confirmCharging,
            )
            else -> null
        }
        show(R.id.noticeOverlay, notice != null && s.screen != Screen.DRIVING)
        if (notice == null) return
        text(R.id.noticeTitle, notice.first)
        text(R.id.noticeBody, notice.second)
        show(R.id.noticeAction, notice.third != null)
        notice.third?.let { (label, action) -> text(R.id.noticeAction, label); noticeAction = action }
    }

    private fun drivingSummary(s: StayGoUiState): String {
        val a = s.arrival?.socPct ?: return "Stay·Go 판단은 정차 중에 보여 줍니다"
        return "목적지 도착 예상 SOC ${a.roundToInt()}% · ${if (s.route is RouteState.ViaStop) "경유 충전 포함 경로" else "충전 경유 없음"}"
    }

    private fun statusText(s: StayGoUiState): String {
        val base = when (s.charging) {
            ChargingStatus.CHARGING -> "급속충전 중 · 정차(조작 가능)"
            ChargingStatus.FINISHED -> "충전 종료 · 이동 권장"
            else -> "충전 중이 아닙니다 · 정차"
        }
        val mismatch = s.socMismatch ?: return base
        return "$base · SOC 차이: 차량 표시 ${mismatch.pleosSoc}% / 잔량 기준 ${mismatch.levelSoc.roundToInt()}% (내비는 잔량 기준)"
    }

    private fun powerNote(s: StayGoUiState): String {
        val p = s.power
        val pmax = p?.sessionMaxKw
        return when {
            s.charging != ChargingStatus.CHARGING -> " "
            p == null -> "충전전력을 읽지 못해 SOC 기준으로 판단"
            pmax == null -> "배터리 잔량 변화로 전력을 계산합니다"
            !p.sessionMaxTrusted -> "세션 최대를 믿을 수 없어 SOC 기준으로 판단"
            else -> "세션 최대 ${pmax.roundToInt()} kW의 ${(p.kw / pmax * 100).roundToInt()}%"
        }
    }

    private fun renderArrival(s: StayGoUiState) {
        val arrival = findViewById<TextView>(R.id.arrivalText)
        val note = findViewById<TextView>(R.id.arrivalNote)
        val a = s.arrival
        val charging = s.charging == ChargingStatus.CHARGING
        note.setTextColor(color(R.color.muted))
        when {
            s.screen == Screen.TARGET_SOC_NEEDED -> {
                arrival.text = "도착 예상 SOC를 받지 못했습니다"
                note.text = "목표 SOC를 정하면 그 값으로 판단합니다"
            }
            a?.basis == ArrivalBasis.DRIVER_TARGET -> {
                val hex = if (a.reserveMet) "#1F6FD1" else "#B26A00"
                arrival.text = Html.fromHtml("목표 SOC <b><font color='$hex'>${s.manualTargetSoc}%</font></b> 기준", Html.FROM_HTML_MODE_LEGACY)
                if (a.reserveMet) {
                    note.text = "목표 SOC에 도달했습니다"
                    note.setTextColor(color(R.color.green_text))
                } else {
                    note.text = "목표까지 ${a.deficitPct?.roundToInt()}%p${waitText(a.extraWaitMinutes)}"
                    note.setTextColor(color(R.color.amber))
                }
            }
            a?.socPct != null -> {
                val hex = if (a.reserveMet) "#1F6FD1" else "#B26A00"
                arrival.text = Html.fromHtml("목적지에 <b><font color='$hex'>${a.socPct.roundToInt()}%</font></b>로 도착", Html.FROM_HTML_MODE_LEGACY)
                val basis = if (a.basis == ArrivalBasis.NAVI_ROUTE_ENERGY) "충전 경유 없이 갈 때 · " else ""
                if (a.reserveMet) {
                    note.text = "${basis}예비 SOC ${s.reserveSocPct}%p 충족"
                    note.setTextColor(color(R.color.green_text))
                } else {
                    note.text = "${basis}예비 SOC까지 ${"%.1f".format(a.deficitPct ?: 0.0)}%p 부족${waitText(a.extraWaitMinutes)}"
                    note.setTextColor(color(R.color.amber))
                }
            }
            s.destination == null -> {
                arrival.text = "목적지를 설정하면 계산합니다"
                note.text = if (charging) "내비에 목적지가 없어 Go를 잠갔습니다" else " "
            }
            else -> { arrival.text = "내비 계산을 기다리는 중"; note.text = " " }
        }
        show(R.id.stepRow, charging)
        text(R.id.stepLabel, if (s.usesTargetSoc()) "목표 SOC ${s.manualTargetSoc?.let { "$it%" } ?: "미설정"}" else "예비 SOC ${s.reserveSocPct}%p")
    }

    /** The wait is a lower bound: it assumes the current power holds, and power falls late in the charge. */
    private fun waitText(minutes: Double?) = minutes?.let { " · 현재 전력으로 최소 ${"%.1f".format(it)}분" } ?: ""

    private fun renderRoute(s: StayGoUiState) {
        val title = findViewById<TextView>(R.id.routeText)
        val note = findViewById<TextView>(R.id.routeNote)
        note.setTextColor(color(R.color.muted))
        when (val r = s.route) {
            RouteState.None -> { title.text = " "; note.text = " " }
            RouteState.Direct -> {
                title.text = "충전 경유 없이 목적지 직행"
                note.text = "내비가 경로에 충전 경유지를 넣지 않았습니다"
            }
            is RouteState.ViaStop -> {
                title.text = "경유 충전 · ${shortName(r.stopName)}"
                val c = r.comparison
                val unknown = " · 대기·고장 여부는 알 수 없음"
                note.text = when {
                    r.arrivalSocPct == null -> "경유지 도착 SOC를 계산하는 중"
                    c != null && c.gainMinutes > 0 -> {
                        note.setTextColor(color(R.color.blue))
                        val source = if (r.outputSource == OutputSource.SNAPSHOT) " · ${r.snapshotAsOf ?: "이전"} 공개자료 기준" else ""
                        "지금 출발해 경유지에서 채우면 약 ${"%.1f".format(c.gainMinutes)}분 빠름 (최대 ${r.maxPowerKw?.roundToInt()} kW · ${r.chargerCount}기${if (c.expectedPowerEstimated) " · 추정" else ""}$source)$unknown"
                    }
                    c != null -> "여기서 채우면 ${"%.1f".format(c.hereMinutes)}분, 경유지는 ${"%.1f".format(c.candidateMinutes)}분 (정차 ${s.stopOverheadMin.roundToInt()}분 포함)"
                    r.maxPowerKw == null -> "경유지 도착 SOC ${r.arrivalSocPct.roundToInt()}% · 최대 출력 미확인으로 비교 생략"
                    else -> "경유지 도착 SOC ${r.arrivalSocPct.roundToInt()}% · 예비 SOC 미만이라 비교 생략"
                }
            }
        }
    }

    private fun renderButtons(s: StayGoUiState) {
        val active = s.charging == ChargingStatus.CHARGING || s.charging == ChargingStatus.FINISHED
        styleButton(R.id.stayButton, R.id.stayTitle, R.id.staySub, s.stayEmphasized, R.color.green)
        styleButton(R.id.goButton, R.id.goTitle, R.id.goSub, s.goEmphasized, R.color.blue)
        text(R.id.stayTitle, if (s.stayEmphasized) "Stay · 더 충전  권장" else "Stay · 더 충전")
        text(R.id.goTitle, if (s.goEmphasized) "Go · 지금 출발  권장" else "Go · 지금 출발")
        text(R.id.staySub, s.power?.secondsPerPercent?.let { "1%p당 약 ${it.roundToInt()}초 (현재 전력 기준)" } ?: " ")
        text(R.id.goSub, when (s.go) {
            GoAvailability.LOCKED_NO_DESTINATION -> "목적지가 없어 잠김"
            GoAvailability.LOCKED_NAVI_UNAVAILABLE -> "내비에 연결할 수 없어 잠김"
            GoAvailability.LOCKED_NOT_CHARGING -> " "
            GoAvailability.VIA_STOP -> "${shortName((s.route as? RouteState.ViaStop)?.stopName ?: "")}을 거치는 경로를 내비에 전달"
            GoAvailability.DIRECT -> "목적지 경로를 내비에 전달"
        })
        findViewById<View>(R.id.stayButton).alpha = if (s.charging == ChargingStatus.CHARGING) 1f else 0.45f
        findViewById<View>(R.id.goButton).alpha = if (active && (s.go == GoAvailability.DIRECT || s.go == GoAvailability.VIA_STOP)) 1f else 0.45f
    }

    private fun renderFooter(s: StayGoUiState) {
        val foot = findViewById<TextView>(R.id.footText)
        val o = s.occupancy
        if (o == null) {
            foot.text = "앱은 충전 중에만 Stay·Go를 판단합니다"
            foot.setTextColor(color(R.color.muted))
        } else {
            foot.text = if (o.warning) {
                "충전 확인 후 ${o.minutesObserved}분 · 급속충전구역 1시간 점유 제한까지 ${(o.limitMinutes - o.minutesObserved).coerceAtLeast(0)}분 (앱을 연 뒤 기준)"
            } else {
                "충전 확인 후 ${o.minutesObserved}분 경과 · 급속충전구역 1시간 점유 제한 안내 (실제 충전 시작 시각은 알 수 없음)"
            }
            foot.setTextColor(color(if (o.warning) R.color.amber else R.color.muted))
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

    private fun styleButton(container: Int, title: Int, sub: Int, filled: Boolean, accent: Int) {
        val bg = GradientDrawable().apply {
            cornerRadius = 32f
            if (filled) { setColor(color(accent)); setStroke(4, color(accent)) } else { setColor(Color.WHITE); setStroke(4, color(R.color.line)) }
        }
        findViewById<LinearLayout>(container).background = bg
        findViewById<TextView>(title).setTextColor(if (filled) Color.WHITE else color(R.color.ink))
        findViewById<TextView>(sub).setTextColor(if (filled) 0xE6FFFFFF.toInt() else color(R.color.ink_soft))
    }

    // ---- One-shot notices ----

    private fun showNotice(notice: UiNotice) {
        showToast(when (notice) {
            is UiNotice.RouteSent ->
                if (notice.viaStopName == null) "목적지 경로를 내비에 전달했습니다" else "${shortName(notice.viaStopName)}을 거치는 경로를 내비에 전달했습니다"
            is UiNotice.RouteFailed -> "경로를 전달하지 못했습니다 · 내비에서 직접 경로를 선택해 주세요"
            UiNotice.StayAcknowledged -> "계속 충전합니다 · SOC가 바뀔 때마다 다시 판단합니다"
            UiNotice.DestinationRequired -> "목적지를 먼저 설정해 주세요"
            UiNotice.NaviUnavailable -> "내비에 연결할 수 없습니다 · 내비에서 직접 경로를 선택해 주세요"
        })
    }

    private fun showToast(message: String) {
        val t = findViewById<TextView>(R.id.toast)
        t.text = message
        t.visibility = View.VISIBLE
        toastHide?.let { main.removeCallbacks(it) }
        toastHide = Runnable { t.visibility = View.GONE }.also { main.postDelayed(it, 2_500L) }
    }

    private fun openPermissionSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun show(id: Int, visible: Boolean) { findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE }
    private fun text(id: Int, value: CharSequence) { findViewById<TextView>(id).text = value }
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
