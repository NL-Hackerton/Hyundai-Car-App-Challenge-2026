package ai.staygo

import ai.staygo.core.GoAvailability
import ai.staygo.core.InMemoryBandStore
import ai.staygo.core.InMemorySettingsStore
import ai.staygo.core.RouteState
import ai.staygo.core.Screen
import ai.staygo.core.StayGoEngine
import ai.staygo.core.StayGoUiState
import ai.staygo.decision.Decision
import ai.staygo.decision.Emphasis
import ai.staygo.sim.Scenario
import ai.staygo.sim.SimWorld
import ai.staygo.ui.HeroPresenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the engine against the simulated car and navigation, one virtual second per step. */
class ScenarioReplayTest {

    private class Run(scenario: Scenario) {
        val world = SimWorld(scenario)
        val engine = StayGoEngine(
            world.navi, InMemoryBandStore().apply { scenario.seedBands(this) },
            InMemorySettingsStore(consent = if (scenario == Scenario.CONSENT) null else true), { world.nowMs },
        )
        val states = ArrayList<StayGoUiState>()

        init {
            engine.start()
            world.restrictions.start(engine::onDrivingRestricted)
            if (scenario == Scenario.PERMISSION_DENIED) engine.onPermissionDenied()
            if (engine.consentGiven) {
                world.vehicle.start(engine)
                world.navi.start(engine)
            }
        }

        fun seconds(n: Int): StayGoUiState {
            repeat(n) {
                world.step()
                engine.tick()
                states += engine.snapshot()
            }
            return states.last()
        }

        fun rules(): List<Int> = states.filter { it.screen == Screen.DECISION }.mapNotNull { it.decision.rule }
            .fold(ArrayList()) { acc, r -> if (acc.lastOrNull() != r) acc += r; acc }
    }

    @Test fun demoCurveGoesFromStayToBothToGoAndThenDrives() {
        val run = Run(Scenario.CHARGE)
        val end = run.seconds(420)
        assertEquals(listOf(2, 3, 4), run.rules())
        assertEquals(Emphasis.GO, end.decision.emphasis)
        assertEquals(GoAvailability.DIRECT, end.go)
        assertTrue(end.power!!.kw < 0.5 * end.power!!.sessionMaxKw!!)

        run.engine.go()
        assertEquals(Screen.DRIVING, run.seconds(10).screen)
    }

    @Test fun vehicleGradeSignalsGiveTheSameDecisionsWithoutEstimation() {
        val run = Run(Scenario.REAL_SIGNALS)
        val end = run.seconds(420)
        assertEquals(4, end.decision.rule)
        assertFalse(end.power!!.estimated)
        assertTrue(run.rules().containsAll(listOf(2, 3, 4)))
    }

    @Test fun slowChargerWithFastStopAheadOffersBothOptions() {
        val run = Run(Scenario.VIA_STOP)
        val s = run.seconds(90)
        assertEquals(5, s.decision.rule)
        assertEquals(GoAvailability.VIA_STOP, s.go)
        val gain = (s.route as RouteState.ViaStop).comparison!!.gainMinutes
        assertTrue("gain was $gain", gain in 12.0..20.0)
        val hero = HeroPresenter.present(s)
        assertEquals(HeroPresenter.Verdict.CHOICE, hero.verdict)
        assertTrue(hero.reason.contains("알 수 없습니다"))
    }

    @Test fun eachScenarioReachesItsScreen() {
        val expected = mapOf(
            Scenario.LATE_OPEN to Screen.DECISION,
            Scenario.NO_DESTINATION to Screen.NO_DESTINATION,
            Scenario.NAVI_DOWN to Screen.TARGET_SOC_NEEDED,
            Scenario.TRIP_FAILS to Screen.TARGET_SOC_NEEDED,
            Scenario.SOC_UNSUPPORTED to Screen.VEHICLE_LINK_UNAVAILABLE,
            Scenario.PERMISSION_DENIED to Screen.VEHICLE_LINK_UNAVAILABLE,
            Scenario.NO_CHARGE_SIGNALS to Screen.CHARGING_UNCONFIRMED,
            Scenario.CHARGER_LIMIT to Screen.CHARGE_FINISHED,
            Scenario.STOPPED_SHORT to Screen.CHARGE_FINISHED,
            Scenario.NOT_CHARGING to Screen.NOT_CHARGING,
            Scenario.SOC_MISMATCH to Screen.DECISION,
            Scenario.CONSENT to Screen.CONSENT_REQUIRED,
            Scenario.RESTRICTION_UNKNOWN to Screen.RESTRICTION_UNKNOWN,
        )
        for ((scenario, screen) in expected) {
            assertEquals(scenario.id, screen, Run(scenario).seconds(150).screen)
        }
    }

    @Test fun lateOpenDecidesOnSoc() {
        val s = Run(Scenario.LATE_OPEN).seconds(120)
        assertEquals(6, s.decision.rule)
        assertEquals(Emphasis.GO, s.decision.emphasis)
    }

    @Test fun chargerThatStopsShortOfTheReserveIsNotAGo() {
        val s = Run(Scenario.STOPPED_SHORT).seconds(150)
        assertEquals(Decision.State.CHARGE_STOPPED_SHORT, s.decision.state)
        assertEquals(Emphasis.NONE, s.decision.emphasis)
    }

    @Test fun mismatchScenarioReportsBothSocValues() {
        val m = Run(Scenario.SOC_MISMATCH).seconds(60).socMismatch!!
        assertEquals(5.0, m.pleosSoc - m.levelSoc, 1.0)
    }
}
