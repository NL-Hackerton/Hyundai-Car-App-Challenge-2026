package ai.staygo.signal

/**
 * Charging power from the rate of change of EV_BATTERY_LEVEL (Wh).
 *
 * A raw estimate uses a 10 s window (at least 5 s of span). Console or VHAL step changes make the raw value
 * spike to thousands of kW, so the reported power is the median of raw estimates over the last 30 s.
 * The session maximum Pmax is the largest power the median held for [sustainMs] (the maximum over time of the
 * rolling minimum), so a short burst of delayed level updates cannot raise it. Pmax is not trusted when, during the first 60 s
 * after the first estimate, the median only fell: the app was opened after the taper had already started.
 * Raw estimates above [maxPlausibleKw] are dropped: when level updates stall and the backlog then arrives in a
 * burst, the burst dominates the 30 s median, and no passenger-EV charge session exceeds that power.
 */
class PowerEstimator(
    private val windowMs: Long = 10_000L,
    private val minSpanMs: Long = 5_000L,
    private val medianMs: Long = 30_000L,
    private val trustWindowMs: Long = 60_000L,
    private val chargingRiseMs: Long = 30_000L,
    private val stallToleranceMs: Long = 6_000L,
    private val maxPlausibleKw: Double = 400.0,
    private val sustainMs: Long = 10_000L,
) {
    private val medians = ArrayDeque<Pair<Long, Double>>()
    private val levels = ArrayDeque<Pair<Long, Double>>()
    private val raws = ArrayDeque<Pair<Long, Double>>()
    private val trustMedians = mutableListOf<Double>()
    private var firstEstimateMs: Long? = null
    private var trustDecided = false

    var powerKw: Double? = null
        private set
    var sessionMaxKw: Double? = null
        private set
    var pmaxTrusted: Boolean = true
        private set

    private var riseStartMs: Long? = null
    private var lastRiseMs: Long? = null
    private var lastLevel: Double? = null

    /** True when the battery level kept rising for 30 s (charging fallback when EV_CHARGE_STATE is unusable). */
    fun isRising(nowMs: Long): Boolean {
        val start = riseStartMs ?: return false
        val last = lastRiseMs ?: return false
        return nowMs - start >= chargingRiseMs && nowMs - last <= stallToleranceMs
    }

    /** Restores what a recreated process still needs from the session: the maximum and whether it is trusted. */
    fun restore(sessionMaxKw: Double?, pmaxTrusted: Boolean) {
        this.sessionMaxKw = sessionMaxKw
        this.pmaxTrusted = pmaxTrusted
        trustDecided = true
    }

    /**
     * Continues after a gap without samples (the screen was away). The windows are stale and start over; the
     * session maximum stays. [rose] tells whether the level grew over the gap, i.e. charging went on meanwhile.
     */
    fun resume(nowMs: Long, wh: Double, rose: Boolean) {
        levels.clear(); raws.clear(); medians.clear()
        powerKw = null
        trustDecided = true
        lastLevel = wh
        levels.addLast(nowMs to wh)
        if (rose) {
            if (riseStartMs == null) riseStartMs = nowMs - chargingRiseMs
            lastRiseMs = nowMs
        } else {
            riseStartMs = null
            lastRiseMs = null
        }
    }

    fun addLevel(nowMs: Long, wh: Double) {
        val prev = lastLevel
        if (prev != null) {
            when {
                wh > prev + 1e-6 -> {
                    if (riseStartMs == null || (lastRiseMs != null && nowMs - lastRiseMs!! > stallToleranceMs)) riseStartMs = nowMs
                    lastRiseMs = nowMs
                }
                wh < prev - 1e-6 -> { riseStartMs = null; lastRiseMs = null }
            }
        }
        lastLevel = wh

        levels.addLast(nowMs to wh)
        while (levels.size > 1 && nowMs - levels.first().first > windowMs) levels.removeFirst()
        val (t0, e0) = levels.first()
        val span = nowMs - t0
        if (span < minSpanMs) return
        val raw = (wh - e0) / (span / 3_600_000.0) / 1000.0
        if (raw <= maxPlausibleKw) raws.addLast(nowMs to raw)
        while (raws.isNotEmpty() && nowMs - raws.first().first > medianMs) raws.removeFirst()
        if (raws.isEmpty()) return
        val median = median(raws.map { it.second })
        powerKw = median.takeIf { it > 0.0 }

        if (firstEstimateMs == null) firstEstimateMs = nowMs
        val p = powerKw ?: return
        val first = firstEstimateMs!!
        if (!trustDecided) {
            trustMedians += p
            if (nowMs - first >= trustWindowMs) {
                trustDecided = true
                val onlyFell = trustMedians.zipWithNext().all { (a, b) -> b <= a } && trustMedians.last() < trustMedians.first() * 0.95
                pmaxTrusted = !onlyFell
            }
        }
        medians.addLast(nowMs to p)
        while (nowMs - medians.first().first > sustainMs) medians.removeFirst()
        // After resume() the sustain window restarts from the first new estimate.
        if (medians.size < 2 && sessionMaxKw != null) return
        if (nowMs - first >= sustainMs) {
            val held = medians.minOf { it.second }
            if (sessionMaxKw == null || held > sessionMaxKw!!) sessionMaxKw = held
        }
    }

    companion object {
        fun median(values: List<Double>): Double {
            val s = values.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }
    }
}
