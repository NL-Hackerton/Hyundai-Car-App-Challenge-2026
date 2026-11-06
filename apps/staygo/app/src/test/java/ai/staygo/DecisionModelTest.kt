package ai.staygo

import ai.staygo.decision.Candidate
import ai.staygo.decision.ChargingStatus
import ai.staygo.decision.Decision
import ai.staygo.decision.DecisionModel
import ai.staygo.decision.Emphasis
import ai.staygo.decision.Inputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DecisionModelTest {

    private fun inputs(
        a: Double? = 20.0,
        soc: Int? = 83,
        power: Double? = 95.0,
        pmax: Double? = 230.0,
        trusted: Boolean = true,
        candidate: Candidate? = null,
        band: Double? = null,
        charging: ChargingStatus = ChargingStatus.CHARGING,
    ) = Inputs(charging, soc, power, pmax, trusted, hasDestination = true, arrivalSoc = a, candidate = candidate, bandPowerKw = band, capacityKwh = 77.4)

    @Test fun notChargingGivesNoDecision() {
        val d = DecisionModel.decide(inputs(charging = ChargingStatus.NOT_CHARGING))
        assertEquals(Emphasis.NONE, d.emphasis)
    }

    @Test fun finishedChargeEmphasizesGo() {
        assertEquals(Emphasis.GO, DecisionModel.decide(inputs(charging = ChargingStatus.FINISHED)).emphasis)
    }

    @Test fun arrivalBelowReserveIsStayWithWaitAtCurrentPower() {
        // Proposal scenario: SOC 72 %, A = 9 %, P = 150 kW → 6 %p short, about 1.9 min.
        val d = DecisionModel.decide(inputs(a = 9.0, power = 150.0))
        assertEquals(2, d.rule)
        assertEquals(Emphasis.STAY, d.emphasis)
        assertEquals(6.0, d.deficitPct!!, 1e-9)
        assertEquals(1.86, d.extraWaitMinutes!!, 0.01)
    }

    @Test fun reserveBoundaryIsInclusive() {
        assertEquals(3, DecisionModel.decide(inputs(a = 15.0, power = 200.0)).rule)
        assertEquals(2, DecisionModel.decide(inputs(a = 14.99, power = 200.0)).rule)
    }

    @Test fun reserveMetPowerAtHalfOfMaxStillPrefersStay() {
        val d = DecisionModel.decide(inputs(a = 20.0, power = 115.0))
        assertEquals(3, d.rule)
        assertEquals(Emphasis.BOTH_STAY, d.emphasis)
    }

    @Test fun reserveMetPowerBelowHalfOfMaxEmphasizesGo() {
        val d = DecisionModel.decide(inputs(a = 20.0, power = 95.0))
        assertEquals(4, d.rule)
        assertEquals(Emphasis.GO, d.emphasis)
        assertEquals(95.0 / 230.0, d.powerRatio!!, 1e-9)
    }

    @Test fun untrustedPmaxFallsBackToSocRule() {
        assertEquals(Emphasis.BOTH_STAY, DecisionModel.decide(inputs(a = 20.0, soc = 79, trusted = false)).emphasis)
        val high = DecisionModel.decide(inputs(a = 20.0, soc = 80, trusted = false))
        assertEquals(6, high.rule)
        assertEquals(Emphasis.GO, high.emphasis)
    }

    @Test fun smallDeficitKeepsStayWithoutWaypointGain() {
        // 6 %p at 150 kW vs candidate 240 kW + 5 min → staying is faster.
        val c = Candidate("청도새마을휴게소", arrivalSoc = 19.0, maxPowerKw = 300.0, chargerCount = 6)
        val d = DecisionModel.decide(inputs(a = 9.0, power = 150.0, candidate = c, band = 240.0))
        assertEquals(2, d.rule)
        val cmp = d.comparison!!
        assertEquals(1.86, cmp.hereMinutes, 0.01)
        assertEquals(6.16, cmp.candidateMinutes, 0.01)
    }

    @Test fun largeDeficitAtSlowChargerSuggestsWaypoint() {
        // Proposal counter-example: 30 %p short at 50 kW, candidate 200 kW → about 16 min faster.
        val c = Candidate("후보", arrivalSoc = 20.0, maxPowerKw = 200.0, chargerCount = 4)
        val d = DecisionModel.decide(inputs(a = -15.0, power = 50.0, candidate = c, band = 250.0))
        assertEquals(5, d.rule)
        assertEquals(15.9, d.comparison!!.gainMinutes, 0.05)
    }

    @Test fun candidateComparisonUsesPublishedTimeWithoutHistory() {
        val c = Candidate("후보", arrivalSoc = 20.0, maxPowerKw = 350.0, chargerCount = 6)
        val cmp = DecisionModel.decide(inputs(a = -15.0, power = 50.0, candidate = c)).comparison!!
        assertEquals(180.6, cmp.expectedPowerKw, 0.1)
        assertEquals(true, cmp.expectedPowerEstimated)
    }

    @Test fun candidateBelowReserveOrUnknownPowerIsNotCompared() {
        val low = Candidate("후보", arrivalSoc = 12.0, maxPowerKw = 300.0, chargerCount = 6)
        assertNull(DecisionModel.decide(inputs(a = 5.0, candidate = low)).comparison)
        val unknown = Candidate("후보", arrivalSoc = 19.0, maxPowerKw = null, chargerCount = null)
        assertNull(DecisionModel.decide(inputs(a = 5.0, candidate = unknown)).comparison)
    }

    @Test fun waitsForNaviBeforeDeciding() {
        val d = DecisionModel.decide(inputs(a = null))
        assertEquals(Decision.State.WAITING_NAVI, d.state)
        assertNotNull(d)
    }
}
