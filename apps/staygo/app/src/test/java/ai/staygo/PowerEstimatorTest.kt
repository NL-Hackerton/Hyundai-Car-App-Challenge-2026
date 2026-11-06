package ai.staygo

import ai.staygo.signal.PowerEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerEstimatorTest {

    private fun feed(e: PowerEstimator, startMs: Long, seconds: Int, startWh: Double, kw: Double): Double {
        var wh = startWh
        for (s in 0 until seconds) {
            e.addLevel(startMs + s * 1000L, wh)
            wh += kw * 1000.0 / 3600.0
        }
        return wh
    }

    @Test fun steadyRampReadsItsPower() {
        val e = PowerEstimator()
        feed(e, 0, 40, 55_000.0, 230.0)
        assertEquals(230.0, e.powerKw!!, 1.0)
        assertEquals(230.0, e.sessionMaxKw!!, 1.0)
    }

    @Test fun singleStepSpikeIsRemovedByMedian() {
        val e = PowerEstimator()
        var wh = feed(e, 0, 35, 55_000.0, 150.0)
        wh += 3_000.0 // console step: +3 kWh in one second
        e.addLevel(35_000L, wh)
        assertEquals(150.0, e.powerKw!!, 15.0)
        assertTrue(e.sessionMaxKw!! < 200.0)
    }

    @Test fun chargingIsRecognizedOnlyAfterThirtySecondsOfRise() {
        val e = PowerEstimator()
        feed(e, 0, 25, 55_000.0, 100.0)
        assertFalse(e.isRising(24_000L))
        feed(e, 25_000L, 10, 55_700.0, 100.0)
        assertTrue(e.isRising(34_000L))
        assertFalse(e.isRising(60_000L))
    }

    @Test fun pmaxIsDistrustedWhenPowerOnlyFellAfterOpening() {
        val e = PowerEstimator()
        var wh = 60_000.0
        var t = 0L
        var kw = 140.0
        repeat(80) {
            e.addLevel(t, wh)
            wh += kw * 1000.0 / 3600.0
            kw -= 0.7
            t += 1000L
        }
        assertFalse(e.pmaxTrusted)
    }
}
