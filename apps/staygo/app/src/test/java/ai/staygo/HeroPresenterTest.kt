package ai.staygo

import ai.staygo.decision.Decision
import ai.staygo.decision.Emphasis
import ai.staygo.ui.HeroPresenter
import ai.staygo.ui.HeroPresenter.Trend
import ai.staygo.ui.HeroPresenter.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroPresenterTest {

    @Test fun shortOfReserveIsAStrongStayWithTheDeficit() {
        val d = Decision(2, Emphasis.STAY, Decision.State.SHORT_OF_RESERVE, deficitPct = 3.0, extraWaitMinutes = 1.4, powerRatio = 0.43)
        val h = HeroPresenter.present(d, arrivalSoc = 12.0, soc = 83)
        assertEquals(Verdict.STAY, h.verdict)
        assertTrue(h.strong)
        assertEquals("지금 가면 목적지에 12%로 도착 · 예비 15% 미달", h.reason)
        assertEquals("3.0%p 부족 · 최소 1.4분", h.chip)
        assertEquals(HeroPresenter.Tone.WARNING, h.chipTone)
    }

    @Test fun latePhaseIsAStrongGoWithArrivalAndPowerRatio() {
        val d = Decision(4, Emphasis.GO, Decision.State.RESERVE_MET_POWER_LOW, powerRatio = 0.41)
        val h = HeroPresenter.present(d, arrivalSoc = 15.0, soc = 86)
        assertEquals(Verdict.GO, h.verdict)
        assertTrue(h.strong)
        assertEquals("지금 출발하세요", h.headline)
        assertEquals("목적지 15% 도착 · 충전 속도가 최대의 41%", h.reason)
    }

    @Test fun powerStillHighLeansStayWithoutForcingIt() {
        val h = HeroPresenter.present(Decision(3, Emphasis.BOTH_STAY, Decision.State.RESERVE_MET_POWER_HIGH, powerRatio = 0.8), 18.0, 80)
        assertEquals(Verdict.STAY, h.verdict)
        assertFalse(h.strong)
    }

    @Test fun waitingStatesAreNeutral() {
        val none = HeroPresenter.present(Decision(null, Emphasis.NONE, Decision.State.NO_DESTINATION), null, 80)
        assertEquals(Verdict.WAIT, none.verdict)
        assertFalse(none.strong)
        assertEquals("목적지 없음", none.chip)
        val idle = HeroPresenter.present(Decision(1, Emphasis.NONE, Decision.State.NOT_CHARGING), null, 74)
        assertEquals(Verdict.WAIT, idle.verdict)
        assertNull(idle.chip)
    }

    @Test fun reasonFitsOneLine() {
        // Pleos copy rule: one line <= 80 characters, no paragraph over 3 lines.
        val states = Decision.State.values()
        for (s in states) {
            val h = HeroPresenter.present(Decision(4, Emphasis.GO, s, deficitPct = 12.3, extraWaitMinutes = 10.5, powerRatio = 0.99), 100.0, 100)
            assertTrue("$s: ${h.reason}", h.reason.length <= 80)
            assertTrue("$s: ${h.headline}", h.headline.length <= 40)
        }
    }

    @Test fun trendComparesWithTwentySecondsAgo() {
        val falling = (0..30).map { it * 1000L to 230.0 - it * 3 }
        assertEquals(Trend.DOWN, HeroPresenter.trend(falling))
        val flat = (0..30).map { it * 1000L to 95.0 }
        assertEquals(Trend.FLAT, HeroPresenter.trend(flat))
        assertNull(HeroPresenter.trend(listOf(0L to 100.0)))
    }
}
