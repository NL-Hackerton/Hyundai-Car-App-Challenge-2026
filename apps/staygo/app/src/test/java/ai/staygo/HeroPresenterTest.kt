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
        assertEquals("지금 가면 목적지 도착 SOC 12% · 예비 15% 미달", h.reason)
        // Whole numbers that add up with the arrival SOC on the same screen; a lower bound rounds up.
        assertEquals("3%p 부족 · 최소 2분", h.chip)
        assertEquals(HeroPresenter.Tone.WARNING, h.chipTone)
    }

    /** The navigation's own figure includes the stop it planned; ours does not, and the wording says so. */
    @Test fun arrivalWithoutThePlannedStopIsWordedAsSuch() {
        val d = Decision(2, Emphasis.STAY, Decision.State.SHORT_OF_RESERVE, deficitPct = 7.0, extraWaitMinutes = 2.0)
        val h = HeroPresenter.present(d, arrivalSoc = 8.0, soc = 76, withoutStop = true)
        assertEquals("경유 충전 없이 가면 목적지 도착 SOC 8% · 예비 15% 미달", h.reason)
    }

    @Test fun latePhaseIsAStrongGoWithArrivalAndPowerRatio() {
        val d = Decision(4, Emphasis.GO, Decision.State.RESERVE_MET_POWER_LOW, powerRatio = 0.41)
        val h = HeroPresenter.present(d, arrivalSoc = 15.0, soc = 86)
        assertEquals(Verdict.GO, h.verdict)
        assertTrue(h.strong)
        assertEquals("지금 출발하세요", h.headline)
        assertEquals("도착 SOC 15% · 충전 전력이 이번 충전 최대의 41%", h.reason)
    }

    /** The chip states the margin over the reserve, never the reserve itself as if it were the margin. */
    @Test fun metReserveChipShowsTheMargin() {
        val go = Decision(4, Emphasis.GO, Decision.State.RESERVE_MET_POWER_LOW, powerRatio = 0.41)
        assertEquals("예비 15% 충족 (+0%p)", HeroPresenter.present(go, 15.0, 86).chip)
        assertEquals("예비 15% 충족 (+5%p)", HeroPresenter.present(go, 20.4, 86).chip)
    }

    @Test fun socIsNeverRoundedUpToTheReserveOrShownBelowZero() {
        assertEquals("14%", HeroPresenter.soc(14.6, reserve = 15.0))
        assertEquals("15%", HeroPresenter.soc(15.4, reserve = 15.0))
        assertEquals("0% 미만", HeroPresenter.soc(-13.0, reserve = 15.0))
        val d = Decision(2, Emphasis.STAY, Decision.State.SHORT_OF_RESERVE, deficitPct = 28.0)
        assertTrue(HeroPresenter.present(d, arrivalSoc = -13.0, soc = 59).reason.contains("0% 미만"))
    }

    @Test fun powerStillHighLeansStayWithoutForcingIt() {
        val h = HeroPresenter.present(Decision(3, Emphasis.BOTH_STAY, Decision.State.RESERVE_MET_POWER_HIGH, powerRatio = 0.8), 18.0, 80)
        assertEquals(Verdict.STAY, h.verdict)
        assertFalse(h.strong)
    }

    @Test fun chargingThatStoppedShortIsNotAGo() {
        val h = HeroPresenter.present(Decision(1, Emphasis.NONE, Decision.State.CHARGE_STOPPED_SHORT, deficitPct = 6.0), 9.0, 60)
        assertEquals(Verdict.WAIT, h.verdict)
        assertEquals("충전이 멈췄습니다", h.headline)
        assertEquals("6%p 부족 · 충전기를 확인하세요", h.chip)
        assertEquals("15%p 넘게 부족", HeroPresenter.shortfall(-13.0, 15))
        val unknown = HeroPresenter.present(Decision(1, Emphasis.NONE, Decision.State.CHARGE_FINISHED), null, 80)
        assertEquals(Verdict.WAIT, unknown.verdict)
    }

    @Test fun waitingStatesAreNeutralAndSayWhy() {
        val none = HeroPresenter.present(Decision(null, Emphasis.NONE, Decision.State.NO_DESTINATION), null, 80)
        assertEquals(Verdict.WAIT, none.verdict)
        assertFalse(none.strong)
        assertEquals("목적지 필요", none.label)
        val idle = HeroPresenter.present(Decision(1, Emphasis.NONE, Decision.State.NOT_CHARGING), null, 74)
        assertEquals(Verdict.WAIT, idle.verdict)
        assertEquals("충전 전", idle.label)
        assertNull(idle.chip)
    }

    @Test fun reasonFitsOneLine() {
        // Pleos copy rule: one line <= 80 characters, no paragraph over 3 lines.
        val states = Decision.State.values()
        for (s in states) {
            for (withoutStop in listOf(false, true)) {
                val h = HeroPresenter.present(Decision(4, Emphasis.GO, s, deficitPct = 12.3, extraWaitMinutes = 10.5, powerRatio = 0.99), 100.0, 100, withoutStop = withoutStop)
                assertTrue("$s: ${h.reason}", h.reason.length <= 80)
                assertTrue("$s: ${h.headline}", h.headline.length <= 40)
            }
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
