package ai.staygo.ui

import ai.staygo.core.ArrivalBasis
import ai.staygo.core.OutputSource
import ai.staygo.core.RouteState
import ai.staygo.core.Screen
import ai.staygo.core.StayGoUiState
import ai.staygo.core.VehicleLinkFailure
import ai.staygo.decision.Decision
import ai.staygo.decision.Emphasis
import ai.staygo.decision.Params
import kotlin.math.roundToInt

/**
 * The glanceable hero: one verdict word, one headline and one reason line (Pleos guide: grasp in 1–2 s,
 * one message per line). Pure function of the decision so the copy is unit-tested.
 */
object HeroPresenter {

    enum class Verdict { STAY, GO, WAIT }

    /** Tone of the small status chip under the reason. */
    enum class Tone { POSITIVE, WARNING, NEUTRAL }

    data class Hero(
        val verdict: Verdict,
        /** True when the verdict is the app's recommendation (filled primary action), false for a soft lean. */
        val strong: Boolean,
        val headline: String,
        val reason: String,
        val chip: String?,
        val chipTone: Tone,
        /** The one thing the driver can do about a blocked state; null when Stay/Go are the actions. */
        val action: Action? = null,
    )

    enum class Action(val label: String) {
        GRANT_PERMISSION("권한 허용"),
        CONFIRM_CHARGING("충전 중입니다"),
    }

    enum class Trend { UP, FLAT, DOWN }

    /** Hero for the whole screen state: blocked states first, then the Stay/Go decision. */
    fun present(s: StayGoUiState): Hero = when (s.screen) {
        Screen.VEHICLE_LINK_UNAVAILABLE -> when (s.vehicleLinkFailure) {
            VehicleLinkFailure.PERMISSION_DENIED -> Hero(
                Verdict.WAIT, false, "차량 정보 권한이 필요합니다", "배터리 잔량·충전 상태를 읽어 Stay·Go를 판단합니다 · 기기 밖으로 보내지 않습니다",
                "차량 값 표시 안 함", Tone.WARNING, Action.GRANT_PERMISSION,
            )
            VehicleLinkFailure.SOC_UNSUPPORTED -> Hero(
                Verdict.WAIT, false, "이 차량은 SOC 조회를 지원하지 않습니다", "차량 연동을 멈췄습니다", "차량 값 표시 안 함", Tone.WARNING,
            )
            else -> Hero(
                Verdict.WAIT, false, "차량과 연결할 수 없습니다", "차량 연동을 멈췄습니다 · 연결되면 다시 판단합니다", "차량 값 표시 안 함", Tone.WARNING,
            )
        }
        Screen.CONNECTING -> Hero(Verdict.WAIT, false, "차량 정보를 확인하는 중", "잠시만 기다려 주세요", null, Tone.NEUTRAL)
        Screen.CHARGING_UNCONFIRMED -> Hero(
            Verdict.WAIT, false, "충전 상태를 확인할 수 없습니다", "지금 급속충전 중이면 직접 확인해 주세요",
            "충전 신호 없음", Tone.WARNING, Action.CONFIRM_CHARGING,
        )
        Screen.TARGET_SOC_NEEDED -> Hero(
            Verdict.WAIT, false, "목표 SOC를 정해 주세요", "내비에서 도착 예상 SOC를 받지 못해 목표 SOC로 판단합니다",
            "내비 응답 없음", Tone.WARNING,
        )
        else -> {
            val a = s.arrival
            val via = s.route as? RouteState.ViaStop
            val gain = via?.comparison?.gainMinutes ?: 0.0
            when {
                a?.basis == ArrivalBasis.DRIVER_TARGET -> presentTarget(s)
                s.decision.rule == 5 && via != null && gain > 0.0 -> presentFasterStop(s, via, gain)
                else -> present(s.decision, a?.socPct, s.socPercent, Params(reserveSocPct = s.reserveSocPct.toDouble()))
            }
        }
    }

    /**
     * Rule 5: filling the deficit at the stop the navigation planned is faster than staying. It is offered next
     * to Stay, not instead of it, and always with what is unknown: the stop's queue and faults.
     */
    private fun presentFasterStop(s: StayGoUiState, via: RouteState.ViaStop, gainMinutes: Double): Hero {
        val d = s.decision
        val source = when {
            via.outputSource == OutputSource.SNAPSHOT -> " (${via.snapshotAsOf ?: "이전"} 자료)"
            via.comparison?.expectedPowerEstimated == true -> " (추정)"
            else -> ""
        }
        return Hero(
            Verdict.STAY, false,
            "경유 충전이 약 ${gainMinutes.roundToInt()}분 빠릅니다",
            "${shortStopName(via.stopName)} 최대 ${via.maxPowerKw?.roundToInt() ?: "--"} kW · ${via.chargerCount ?: "--"}기$source · 대기·고장은 알 수 없습니다",
            "${"%.1f".format(d.deficitPct ?: 0.0)}%p 부족" + (d.extraWaitMinutes?.let { " · 여기서 최소 ${"%.1f".format(it)}분" } ?: ""),
            Tone.WARNING,
        )
    }

    /** POI names arrive as "청도새마을휴게소(부산방향) 전기차충전소"; the screen shows the rest area only. */
    fun shortStopName(poi: String) = poi.replace("전기차충전소", "").replace(Regex("\\(.*?\\)"), "").trim()

    /** The navigation gives no arrival estimate: the verdict compares the current SOC with the driver's target. */
    private fun presentTarget(s: StayGoUiState): Hero {
        val target = s.manualTargetSoc
        val soc = s.socPercent
        val a = s.arrival
        val strong = s.decision.emphasis != Emphasis.NONE
        return when {
            a?.reserveMet == true && s.decision.emphasis == Emphasis.GO -> Hero(
                Verdict.GO, strong, "목표 SOC에 도달했습니다", "현재 ${soc ?: "--"}% · 목표 ${target ?: "--"}% · 충전 속도가 느려졌습니다",
                "목표 SOC 충족", Tone.POSITIVE,
            )
            a?.reserveMet == true -> Hero(
                Verdict.STAY, false, "목표 도달 · 아직 빠르게 충전 중", "현재 ${soc ?: "--"}% · 목표 ${target ?: "--"}%", "목표 SOC 충족", Tone.POSITIVE,
            )
            else -> Hero(
                Verdict.STAY, strong, "목표까지 더 충전하세요", "현재 ${soc ?: "--"}% · 목표 ${target ?: "--"}% (내비 도착 예상 없음)",
                "${a?.deficitPct?.roundToInt() ?: "--"}%p 남음" + (a?.extraWaitMinutes?.let { " · 최소 ${"%.1f".format(it)}분" } ?: ""),
                Tone.WARNING,
            )
        }
    }

    fun present(d: Decision, arrivalSoc: Double?, soc: Int?, params: Params = Params()): Hero {
        val a = arrivalSoc?.roundToInt()
        val reserve = params.reserveSocPct.roundToInt()
        val ratio = d.powerRatio?.let { (it * 100).roundToInt() }
        return when (d.state) {
            Decision.State.NOT_CHARGING -> Hero(
                Verdict.WAIT, false, "충전을 시작하면 판단합니다", "Stay·Go는 급속충전 중에만 보여 드립니다", null, Tone.NEUTRAL,
            )
            Decision.State.NO_DESTINATION -> Hero(
                Verdict.WAIT, false, "내비에 목적지를 설정하세요", "도착 예상 SOC를 알 수 없어 Go를 잠갔습니다", "목적지 없음", Tone.WARNING,
            )
            Decision.State.WAITING_NAVI -> Hero(
                Verdict.WAIT, false, "도착 예상 SOC 계산 중", "내비에 현재 배터리로 다시 묻고 있습니다", null, Tone.NEUTRAL,
            )
            Decision.State.CHARGE_FINISHED -> Hero(
                Verdict.GO, true, "충전이 끝났습니다",
                a?.let { "지금 출발하면 목적지에 $it%로 도착" } ?: "케이블을 뽑고 출발하세요", null, Tone.POSITIVE,
            )
            Decision.State.SHORT_OF_RESERVE -> {
                val deficit = d.deficitPct ?: 0.0
                val viaFaster = d.rule == 5 && (d.comparison?.gainMinutes ?: 0.0) > 0.0
                Hero(
                    // A faster stop ahead is offered next to Stay, not instead of it: its queue and faults are unknown.
                    Verdict.STAY, !viaFaster,
                    if (viaFaster) "경유 충전이 약 ${d.comparison!!.gainMinutes.roundToInt()}분 빠릅니다" else "조금 더 충전하세요",
                    "지금 가면 목적지에 ${a ?: "--"}%로 도착 · 예비 $reserve% 미달",
                    "${"%.1f".format(deficit)}%p 부족" + (d.extraWaitMinutes?.let { " · 최소 ${"%.1f".format(it)}분" } ?: ""),
                    Tone.WARNING,
                )
            }
            Decision.State.RESERVE_MET_POWER_HIGH -> Hero(
                Verdict.STAY, false, "아직 빠르게 충전되는 중",
                "목적지 ${a ?: "--"}% 도착 가능 · 전력이 세션 최대의 ${ratio ?: "--"}%",
                "예비 SOC 충족", Tone.POSITIVE,
            )
            Decision.State.RESERVE_MET_POWER_LOW -> Hero(
                Verdict.GO, true, "지금 출발하세요",
                "목적지 ${a ?: "--"}% 도착 · 충전 속도가 최대의 ${ratio ?: "--"}%",
                "예비 SOC $reserve%p 충족", Tone.POSITIVE,
            )
            Decision.State.RESERVE_MET_SOC_FALLBACK_HIGH -> Hero(
                Verdict.GO, true, "지금 출발하세요",
                "목적지 ${a ?: "--"}% 도착 · SOC ${soc ?: "--"}% 충전 후반",
                "예비 SOC $reserve%p 충족", Tone.POSITIVE,
            )
            Decision.State.RESERVE_MET_SOC_FALLBACK_LOW -> Hero(
                Verdict.STAY, false, "아직 빠르게 충전되는 구간",
                "목적지 ${a ?: "--"}% 도착 가능 · SOC ${soc ?: "--"}%",
                "예비 SOC 충족", Tone.POSITIVE,
            )
        }.let { if (d.emphasis == Emphasis.NONE) it.copy(strong = false) else it }
    }

    /**
     * Power trend over the last [windowMs]: compares the newest sample with the oldest one in the window.
     * Changes under [tolerance] (relative) read as flat.
     */
    fun trend(samples: List<Pair<Long, Double>>, windowMs: Long = 20_000L, tolerance: Double = 0.05): Trend? {
        if (samples.size < 2) return null
        val (tNow, pNow) = samples.last()
        val past = samples.firstOrNull { tNow - it.first <= windowMs } ?: return null
        if (past === samples.last() || past.second <= 0.0) return null
        val change = (pNow - past.second) / past.second
        return when {
            change > tolerance -> Trend.UP
            change < -tolerance -> Trend.DOWN
            else -> Trend.FLAT
        }
    }
}
