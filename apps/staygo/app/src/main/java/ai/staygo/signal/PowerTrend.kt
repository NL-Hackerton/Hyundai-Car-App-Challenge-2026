package ai.staygo.signal

/**
 * Filtered charging power and the session maximum it is judged against, from one series of power samples.
 *
 * The reported power is the median of the samples over the last [medianMs]. The session maximum Pmax is the
 * largest power that median held for [sustainMs] (the maximum over time of the rolling minimum), so a short
 * burst cannot raise it. Pmax is not trusted when, during the first [trustWindowMs] after the first sample,
 * the median only fell: the app was opened after the taper had already started.
 *
 * Both the current power and Pmax come from the same filtered series, so their ratio never compares a raw
 * reading with a smoothed one.
 */
class PowerTrend(
    private val medianMs: Long = 30_000L,
    private val trustWindowMs: Long = 60_000L,
    private val sustainMs: Long = 10_000L,
) {
    private val samples = ArrayDeque<Pair<Long, Double>>()
    private val medians = ArrayDeque<Pair<Long, Double>>()
    private val trustMedians = mutableListOf<Double>()
    private var firstMs: Long? = null
    private var trustDecided = false

    var powerKw: Double? = null
        private set
    var sessionMaxKw: Double? = null
        private set
    var pmaxTrusted: Boolean = true
        private set

    fun restore(sessionMaxKw: Double?, pmaxTrusted: Boolean) {
        this.sessionMaxKw = sessionMaxKw
        this.pmaxTrusted = pmaxTrusted
        trustDecided = true
    }

    /** Continues after a gap without samples: the windows start over, the session maximum stays. */
    fun resume() {
        samples.clear(); medians.clear()
        powerKw = null
        trustDecided = true
    }

    fun add(nowMs: Long, kw: Double) {
        samples.addLast(nowMs to kw)
        while (samples.isNotEmpty() && nowMs - samples.first().first > medianMs) samples.removeFirst()
        val median = median(samples.map { it.second })
        powerKw = median.takeIf { it > 0.0 }

        if (firstMs == null) firstMs = nowMs
        val p = powerKw ?: return
        val first = firstMs!!
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
        // The sustain window must be covered by samples before its minimum counts.
        if (nowMs - first < sustainMs || medians.last().first - medians.first().first < sustainMs / 2) return
        val held = medians.minOf { it.second }
        if (sessionMaxKw == null || held > sessionMaxKw!!) sessionMaxKw = held
    }

    companion object {
        fun median(values: List<Double>): Double {
            val s = values.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }
    }
}
