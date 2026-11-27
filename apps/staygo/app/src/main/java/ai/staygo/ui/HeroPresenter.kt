package ai.staygo.ui

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
    )

    enum class Trend { UP, FLAT, DOWN }

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
                    Verdict.STAY, true,
                    if (viaFaster) "경유지에서 채우는 편이 빠릅니다" else "조금 더 충전하세요",
                    "지금 가면 목적지에 ${a ?: "--"}%로 도착 · 예비 $reserve% 미달",
                    "${"%.1f".format(deficit)}%p 부족" + (d.extraWaitMinutes?.let { " · 약 ${"%.1f".format(it)}분 더" } ?: ""),
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
