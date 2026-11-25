package ai.staygo

import ai.staygo.core.ActiveWaypoint
import ai.staygo.core.ChargingStop
import ai.staygo.core.Destination
import ai.staygo.core.DestinationTrip
import ai.staygo.core.GridStationDirectory
import ai.staygo.core.InMemoryBandStore
import ai.staygo.core.InMemorySettingsStore
import ai.staygo.core.NaviSource
import ai.staygo.core.OutputSource
import ai.staygo.core.RouteState
import ai.staygo.core.StayGoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationDirectoryTest {

    private val directory = GridStationDirectory.parse(
        sequenceOf(
            "#asOf=2026-11-26",
            "35.671200\t128.739000\t300\t6\t청도새마을휴게소(부산방향)",
            "35.671900\t128.739400\t100\t2\t다른사업자 청도충전소",
            "35.673000\t128.752000\t350\t4\t청도새마을휴게소(대구방향)",
            "not\ta\tstation",
            "35.0\t129.0\t0\t3\t출력 없는 충전소",
        )
    )

    @Test fun parsesHeaderAndSkipsMalformedRows() {
        assertEquals("2026-11-26", directory.asOf)
        assertEquals(3, directory.size)
    }

    @Test fun sameNamedStationNearTheStopWinsOverACloserUnrelatedOne() {
        // 60 m from the unrelated station, 90 m from the rest-area station of the same name.
        val s = directory.lookup("청도새마을휴게소(부산방향) 전기차충전소", 35.67195, 128.73880)!!
        assertEquals(300, s.maxPowerKw)
        assertEquals(6, s.chargerCount)
    }

    @Test fun unnamedMatchFallsBackToTheNearestStation() {
        assertEquals(100, directory.lookup("이름이 다른 지점", 35.67195, 128.73935)!!.maxPowerKw)
    }

    @Test fun oppositeDirectionRestAreaAcrossTheRoadIsOutOfRange() {
        // The 대구방향 station is about 1.2 km away: same name key, but not the stop.
        assertEquals(300, directory.lookup("청도새마을휴게소", 35.6712, 128.7390)!!.maxPowerKw)
        assertNull(directory.lookup("청도새마을휴게소", 35.6800, 128.7390))
    }

    private class NoNavi : NaviSource {
        override fun start(listener: NaviSource.Listener) = Unit
        override fun stop() = Unit
        override fun requestDestinationTrip() = Unit
        override fun go(viaStop: ChargingStop?) = Unit
    }

    private fun engineAtSlowCharger(): StayGoEngine {
        var now = 0L
        var levelWh = 45_000.0
        val engine = StayGoEngine(NoNavi(), InMemoryBandStore().apply { record(18, 240.0) }, InMemorySettingsStore(), { now })
        engine.start()
        engine.onDrivingRestricted(false)
        engine.onCapacityWh(77_400.0)
        engine.onSoc(58)
        engine.onNaviReady()
        engine.onDestination(Destination("부산시청", 35.1798, 129.0750, 375_000))
        repeat(45) {
            now += 1_000L
            levelWh += 50_000.0 / 3600.0
            engine.onBatteryLevelWh(levelWh)
        }
        val stop = ChargingStop("청도새마을휴게소(부산방향) 전기차충전소", 35.6712, 128.7390, 0.82)
        engine.onDestinationTrip(DestinationTrip(10, 56_600.0, 375_000, stop))
        engine.onChargingStopTrip(stop, 18)
        return engine
    }

    @Test fun snapshotSuppliesTheStopOutputWhenTheActiveRouteDoesNot() {
        val engine = engineAtSlowCharger()
        assertEquals(2, engine.snapshot().decision.rule)

        engine.stations = directory
        val s = engine.snapshot()
        val via = s.route as RouteState.ViaStop
        assertEquals(5, s.decision.rule)
        assertEquals(OutputSource.SNAPSHOT, via.outputSource)
        assertEquals("2026-11-26", via.snapshotAsOf)
        assertEquals(300.0, via.maxPowerKw!!, 1e-9)
    }

    @Test fun activeRouteValueOverridesTheSnapshot() {
        val engine = engineAtSlowCharger()
        engine.stations = directory
        engine.onActiveWaypoints(listOf(ActiveWaypoint("청도새마을휴게소", 200, 4)))
        val via = engine.snapshot().route as RouteState.ViaStop
        assertEquals(OutputSource.ACTIVE_ROUTE, via.outputSource)
        assertEquals(200.0, via.maxPowerKw!!, 1e-9)
        assertEquals(4, via.chargerCount)
    }
}
