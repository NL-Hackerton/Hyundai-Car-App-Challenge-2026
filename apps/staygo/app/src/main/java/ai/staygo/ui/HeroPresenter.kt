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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The glanceable hero: one verdict word, one headline and one reason line (Pleos guide: grasp in 1–2 s,
 * one message per line). Pure function of the state so the copy is unit-tested.
 *
 * One term per concept: "SOC" for the charge level in percent, "예비 SOC" for the reserve at the destination,
 * "도착 SOC" for the arrival estimate, "충전 전력" for power, "이번 충전 최대" for the session maximum.
 */
object HeroPresenter {

    /** CHOICE: two options are offered with equal weight. WAIT: no verdict; [Hero.label] says why. */
    enum class Verdict { STAY, GO, CHOICE, WAIT }

    /** Tone of the small status chip under the reason. */
    enum class Tone { POSITIVE, WARNING, NEUTRAL }

    data class Hero(
        val verdict: Verdict,
        /** True when the verdict is the app's recommendation (filled pill), false for a soft lean. */
        val strong: Boolean,
        val headline: String,
        val reason: String,
        val chip: String?,
        val chipTone: Tone,
        /** The one thing the driver can do about a blocked state; null when Stay/Go are the actions. */
        val action: Action? = null,
        /** The word in the verdict pill. */
        val label: String = when (verdict) { Verdict.STAY -> "STAY"; Verdict.GO -> "GO"; Verdict.CHOICE -> "선택"; Verdict.WAIT -> "대기" },
    )

    enum class Action(val label: String) {
        GRANT_PERMISSION("권한 허용"),
        CONFIRM_CHARGING("충전 중으로 확인"),
    }

    enum class Trend { UP, FLAT, DOWN }

    /**
     * An SOC for the screen. Below zero the car would not arrive, so the number itself is not shown; below
     * [reserve] it is rounded down so that 14.6 % never reads as "15 %, below the 15 % reserve".
     */
    fun soc(value: Double, reserve: Double = Double.NEGATIVE_INFINITY): String = when {
        value < 0.0 -> "0% 미만"
        value < reserve -> "${floor(value).toInt()}%"
        else -> "${value.roundToInt()}%"
    }

    /**
     * Shortfall against the reserve in whole points, taken from the arrival SOC as it is displayed, so the
     * two numbers on the screen always add up. [fallback] is the engine's figure when no arrival is shown.
     */
    fun shortfall(arrivalSoc: Double?, reserve: Int, fallback: Double? = null): String = when {
        // Below zero no arrival figure is shown, so the shortfall is stated against the whole reserve.
        arrivalSoc != null && arrivalSoc < 0.0 -> "$reserve%p 넘게 부족"
        arrivalSoc != null -> "${(reserve - floor(arrivalSoc).toInt()).coerceAtLeast(1)}%p 부족"
        else -> "${ceil(fallback ?: 0.0).toInt()}%p 부족"
    }

    /** Waiting times are lower bounds, so they round up to whole minutes. */
    fun minutes(value: Double): Int = ceil(value).toInt().coerceAtLeast(1)

    /** Hero for the whole screen state: blocked states first, then the Stay/Go decision. */
    fun present(s: StayGoUiState): Hero = when (s.screen) {
        Screen.RESTRICTION_UNKNOWN, Screen.DRIVING ->
            Hero(Verdict.WAIT, false, "주행 중에는 조작할 수 없습니다", " ", null, Tone.NEUTRAL, label = "잠김")
        Screen.CONSENT_REQUIRED ->
            Hero(Verdict.WAIT, false, "정보 이용 안내를 확인해 주세요", "동의한 뒤에 차량과 내비 정보를 읽습니다", null, Tone.NEUTRAL, label = "동의 필요")
        Screen.VEHICLE_LINK_UNAVAILABLE -> when (s.vehicleLinkFailure) {
            VehicleLinkFailure.PERMISSION_DENIED -> Hero(
                Verdict.WAIT, false, "차량 정보 권한이 필요합니다", "SOC와 충전 상태를 읽어 Stay·Go를 판단합니다",
                null, Tone.NEUTRAL, Action.GRANT_PERMISSION, label = "권한 필요",
            )
            VehicleLinkFailure.SOC_UNSUPPORTED -> Hero(
                Verdict.WAIT, false, "이 차량에서는 사용할 수 없습니다", "차량이 SOC 조회를 지원하지 않습니다", null, Tone.NEUTRAL, label = "사용 불가",
            )
            else -> Hero(
                Verdict.WAIT, false, "차량과 연결할 수 없습니다", "연결되면 자동으로 다시 판단합니다", null, Tone.NEUTRAL, label = "연결 안 됨",
            )
        }
        Screen.CONNECTING -> Hero(Verdict.WAIT, false, "차량 정보를 확인하는 중", "잠시만 기다려 주세요", null, Tone.NEUTRAL, label = "확인 중")
        Screen.CHARGING_UNCONFIRMED -> Hero(
            Verdict.WAIT, false, "충전 상태를 읽을 수 없습니다", "지금 급속충전 중이면 아래 버튼으로 알려 주세요",
            null, Tone.NEUTRAL, Action.CONFIRM_CHARGING, label = "확인 필요",
        )
        Screen.TARGET_SOC_NEEDED -> Hero(
            Verdict.WAIT, false, "목표 SOC를 정해 주세요", "도착 SOC를 계산할 수 없어 목표 SOC로 판단합니다", null, Tone.NEUTRAL, label = "입력 필요",
        )
        else -> {
            val a = s.arrival
            val via = s.route as? RouteState.ViaStop
            val gain = via?.comparison?.gainMinutes ?: 0.0
            when {
                a?.basis == ArrivalBasis.DRIVER_TARGET -> presentTarget(s)
                s.decision.rule == 5 && via != null && gain > 0.0 -> presentFasterStop(s, via, gain)
                else -> present(
                    s.decision, a?.socPct, s.socPercent, Params(reserveSocPct = s.reserveSocPct.toDouble()),
                    withoutStop = a?.basis == ArrivalBasis.NAVI_ROUTE_ENERGY,
                    slowdownKw = s.power?.takeIf { it.sessionMaxTrusted }?.sessionMaxKw?.let { it * s.lateRatio },
                )
            }
        }
    }

    /**
     * Hero for a decision. [withoutStop] marks an arrival SOC computed for driving on without the charging
     * stop the navigation planned; the wording must say so, because the navigation's own figure includes it.
     */
    fun present(
        d: Decision, arrivalSoc: Double?, soc: Int?, params: Params = Params(), withoutStop: Boolean = false, slowdownKw: Double? = null,
    ): Hero {
        val reserveValue = params.reserveSocPct
        val reserve = reserveValue.roundToInt()
        val a = arrivalSoc?.let { soc(it, reserveValue) } ?: "--%"
        val ratio = d.powerRatio?.let { (it * 100).roundToInt() }
        val leaving = if (withoutStop) "경유 충전 없이 가면" else "지금 가면"
        val met = arrivalSoc?.let { "예비 $reserve% 충족 (+${(it.roundToInt() - reserve).coerceAtLeast(0)}%p)" } ?: "예비 $reserve% 충족"
        val short = shortfall(arrivalSoc, reserve, d.deficitPct)
        return when (d.state) {
            Decision.State.NOT_CHARGING -> Hero(
                Verdict.WAIT, false, "충전을 시작하면 판단합니다", "Stay·Go는 급속충전 중에만 안내합니다", null, Tone.NEUTRAL, label = "충전 전",
            )
            Decision.State.NO_DESTINATION -> Hero(
                Verdict.WAIT, false, "내비에 목적지를 설정하세요", "목적지가 없어 도착 SOC를 계산할 수 없습니다", null, Tone.NEUTRAL, label = "목적지 필요",
            )
            Decision.State.WAITING_NAVI -> Hero(
                Verdict.WAIT, false, "도착 SOC 계산 중", "내비가 현재 배터리로 경로를 계산하고 있습니다", null, Tone.NEUTRAL, label = "계산 중",
            )
            Decision.State.CHARGE_FINISHED ->
                if (d.emphasis == Emphasis.GO) {
                    Hero(Verdict.GO, true, "충전이 끝났습니다", "지금 출발하면 목적지 도착 SOC $a", met, Tone.POSITIVE)
                } else {
                    Hero(Verdict.WAIT, false, "충전이 끝났습니다", "도착 SOC를 알 수 없습니다 · 내비에서 경로를 확인하세요", null, Tone.NEUTRAL, label = "충전 종료")
                }
            Decision.State.CHARGE_STOPPED_SHORT -> Hero(
                Verdict.WAIT, false, "충전이 멈췄습니다", "$leaving 목적지 도착 SOC $a · 예비 $reserve% 미달",
                "$short · 충전기를 확인하세요", Tone.WARNING, label = "충전 멈춤",
            )
            Decision.State.SHORT_OF_RESERVE -> Hero(
                Verdict.STAY, true, "조금 더 충전하세요", "$leaving 목적지 도착 SOC $a · 예비 $reserve% 미달",
                short + (d.extraWaitMinutes?.let { " · 최소 ${minutes(it)}분" } ?: ""),
                Tone.WARNING,
            )
            Decision.State.RESERVE_MET_POWER_HIGH -> Hero(
                Verdict.STAY, false, "아직 빠르게 충전되는 중",
                if (slowdownKw != null) "도착 SOC $a · ${slowdownKw.roundToInt()}\u00A0kW 아래로 줄면 출발 안내"
                else "도착 SOC $a · 충전 전력이 이번 충전 최대의 ${ratio ?: "--"}%",
                met, Tone.POSITIVE,
            )
            Decision.State.RESERVE_MET_POWER_LOW -> Hero(
                Verdict.GO, true, "지금 출발하세요",
                "도착 SOC $a · 충전 전력이 이번 충전 최대의 ${ratio ?: "--"}%", met, Tone.POSITIVE,
            )
            Decision.State.RESERVE_MET_SOC_FALLBACK_HIGH -> Hero(
                Verdict.GO, true, "지금 출발하세요", "도착 SOC $a · SOC ${soc ?: "--"}%로 충전 후반", met, Tone.POSITIVE,
            )
            Decision.State.RESERVE_MET_SOC_FALLBACK_LOW -> Hero(
                Verdict.STAY, false, "아직 빠르게 충전되는 구간", "도착 SOC $a · SOC ${soc ?: "--"}%", met, Tone.POSITIVE,
            )
        }.let { if (d.emphasis == Emphasis.NONE) it.copy(strong = false) else it }
    }

    /**
     * Rule 5: filling the deficit at the stop the navigation planned is faster than staying. Both options are
     * offered with equal weight, and always with what is unknown: the stop's queue and faults.
     */
    private fun presentFasterStop(s: StayGoUiState, via: RouteState.ViaStop, gainMinutes: Double): Hero {
        val d = s.decision
        val source = when {
            via.outputSource == OutputSource.SNAPSHOT -> " (${via.snapshotAsOf ?: "이전"} 자료)"
            via.comparison?.expectedPowerEstimated == true -> " (추정)"
            else -> ""
        }
        return Hero(
            Verdict.CHOICE, false,
            "경유 충전이 약 ${gainMinutes.roundToInt()}분 빠릅니다",
            "${shortStopName(via.stopName)} 최대 ${via.maxPowerKw?.roundToInt() ?: "--"} kW · ${via.chargerCount ?: "--"}기$source · 대기·고장은 알 수 없습니다",
            shortfall(s.arrival?.socPct, s.reserveSocPct, d.deficitPct) + (d.extraWaitMinutes?.let { " · 여기서 최소 ${minutes(it)}분" } ?: ""),
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
                Verdict.GO, strong, "목표 SOC에 도달했습니다", "SOC ${soc ?: "--"}% · 목표 SOC ${target ?: "--"}% · 충전 후반입니다",
                "목표 SOC 충족", Tone.POSITIVE,
            )
            a?.reserveMet == true -> Hero(
                Verdict.STAY, false, "목표 SOC 도달 · 아직 빠르게 충전 중", "SOC ${soc ?: "--"}% · 목표 SOC ${target ?: "--"}%", "목표 SOC 충족", Tone.POSITIVE,
            )
            else -> Hero(
                Verdict.STAY, strong, "목표 SOC까지 더 충전하세요", "SOC ${soc ?: "--"}% · 목표 SOC ${target ?: "--"}% (도착 SOC 계산 불가)",
                "${a?.deficitPct?.let { ceil(it).toInt() } ?: "--"}%p 남음" + (a?.extraWaitMinutes?.let { " · 최소 ${minutes(it)}분" } ?: ""),
                Tone.WARNING,
            )
        }
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
