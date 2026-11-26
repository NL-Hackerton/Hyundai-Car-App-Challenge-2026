package ai.staygo

import ai.staygo.core.ActiveWaypoint
import ai.staygo.core.ArrivalBasis
import ai.staygo.core.ChargingBasis
import ai.staygo.core.ChargingStop
import ai.staygo.core.Destination
import ai.staygo.core.DestinationTrip
import ai.staygo.core.GoAvailability
import ai.staygo.core.InMemoryBandStore
import ai.staygo.core.InMemorySettingsStore
import ai.staygo.core.NaviSource
import ai.staygo.core.RouteState
import ai.staygo.core.Screen
import ai.staygo.core.StayGoEngine
import ai.staygo.core.UiNotice
import ai.staygo.core.VehicleLinkFailure
import ai.staygo.decision.ChargingStatus
import ai.staygo.decision.Decision
import ai.staygo.decision.Emphasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StayGoEngineTest {

    private class FakeNavi : NaviSource {
        var tripRequests = 0
        val routes = ArrayList<ChargingStop?>()
        override fun start(listener: NaviSource.Listener) = Unit
        override fun stop() = Unit
        override fun requestDestinationTrip() { tripRequests++ }
        override fun go(viaStop: ChargingStop?) { routes += viaStop }
    }

    private class Rig(reserve: Int = 15, consent: Boolean? = true) {
        var now = 0L
        val navi = FakeNavi()
        val bands = InMemoryBandStore()
        val settings = InMemorySettingsStore(reserve, consent)
        val notices = ArrayList<UiNotice>()
        val engine = StayGoEngine(navi, bands, settings, { now }, notify = { notices += it })
        var levelWh = 57_300.0

        init { engine.start() }

        fun parked() = engine.onDrivingRestricted(false)

        fun vehicle(soc: Int = 74) {
            parked()
            engine.onCapacityWh(CAPACITY_WH)
            engine.onBatteryLevelWh(levelWh)
            engine.onChargeState(2) // the emulator reports FULLY_CHARGED whatever the charger does
            engine.onInstantaneousRateKw(0.0)
            engine.onSoc(soc)
        }

        fun navi(destination: Destination? = BUSAN) {
            engine.onNaviReady()
            engine.onDestination(destination)
        }

        /** Feeds the battery level once per second at a constant power. */
        fun charge(seconds: Int, kw: Double) = repeat(seconds) {
            now += 1_000L
            levelWh += kw * 1000.0 / 3600.0
            engine.onBatteryLevelWh(levelWh)
            engine.tick()
        }

        fun idle(seconds: Int) = repeat(seconds) {
            now += 1_000L
            engine.onBatteryLevelWh(levelWh)
            engine.tick()
        }

        fun state() = engine.snapshot()
    }

    // ---- Decision screens ----

    @Test fun chargingWithArrivalEstimateShowsTheDecision() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.engine.onDestinationTrip(DestinationTrip(20, 48_800.0, 375_000, null))
        r.charge(45, 230.0)
        val s = r.state()
        assertEquals(Screen.DECISION, s.screen)
        assertEquals(ChargingBasis.BATTERY_LEVEL_RISE, s.chargingBasis)
        assertEquals(ArrivalBasis.NAVI_DIRECT, s.arrival!!.basis)
        assertEquals(3, s.decision.rule)
        assertEquals(GoAvailability.DIRECT, s.go)
        assertTrue(s.power!!.estimated)
        assertEquals(230.0, s.power!!.kw, 2.0)
    }

    @Test fun chargeStateSignalAloneIsEnoughToRecognizeCharging() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.engine.onChargeState(1)
        repeat(15) { r.now += 1_000L; r.engine.onInstantaneousRateKw(180.0) }
        val s = r.state()
        assertEquals(ChargingStatus.CHARGING, s.charging)
        assertEquals(ChargingBasis.CHARGE_STATE, s.chargingBasis)
        assertFalse(s.power!!.estimated)
        assertEquals(180.0, s.power!!.kw, 1e-9)
    }

    @Test fun rateOnlyCarJudgesPowerAgainstTheMaximumOfTheSameSeries() {
        val r = Rig()
        r.parked()
        r.engine.onCapacityWh(CAPACITY_WH)
        r.engine.onSoc(70)
        r.engine.onChargeState(1)
        r.navi()
        r.engine.onDestinationTrip(DestinationTrip(20, 48_800.0, 375_000, null))
        repeat(70) { r.now += 1_000L; r.engine.onInstantaneousRateKw(200.0); r.engine.tick() }
        var s = r.state()
        assertEquals(200.0, s.power!!.sessionMaxKw!!, 1e-9)
        assertEquals(3, s.decision.rule)
        // One low reading does not flip the advice: the power is the median of the last 30 s.
        r.now += 1_000L; r.engine.onInstantaneousRateKw(40.0)
        assertEquals(3, r.state().decision.rule)
        repeat(40) { r.now += 1_000L; r.engine.onInstantaneousRateKw(80.0); r.engine.tick() }
        s = r.state()
        assertEquals(4, s.decision.rule)
        assertTrue(s.power!!.kw <= s.power!!.sessionMaxKw!!)
    }

    @Test fun noDestinationLocksGo() {
        val r = Rig()
        r.vehicle(); r.navi(destination = null)
        r.charge(45, 230.0)
        val s = r.state()
        assertEquals(Screen.NO_DESTINATION, s.screen)
        assertEquals(GoAvailability.LOCKED_NO_DESTINATION, s.go)
        r.engine.go()
        assertTrue(r.navi.routes.isEmpty())
        assertEquals(listOf<UiNotice>(UiNotice.DestinationRequired), r.notices)
    }

    @Test fun chargerStoppingWithEnoughToArriveEmphasizesGo() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.engine.onDestinationTrip(DestinationTrip(20, 48_800.0, 375_000, null))
        r.charge(45, 150.0)
        r.idle(15)
        val s = r.state()
        assertEquals(Screen.CHARGE_FINISHED, s.screen)
        assertEquals(Emphasis.GO, s.decision.emphasis)
        assertNull(s.power)
        assertNotNull(s.occupancy)
    }

    @Test fun chargerStoppingBelowTheReserveDoesNotSendTheDriverOff() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.engine.onDestinationTrip(DestinationTrip(9, 48_800.0, 375_000, null))
        r.charge(45, 150.0)
        r.state()
        r.idle(15)
        val s = r.state()
        assertEquals(Screen.CHARGE_FINISHED, s.screen)
        assertEquals(Decision.State.CHARGE_STOPPED_SHORT, s.decision.state)
        assertEquals(Emphasis.NONE, s.decision.emphasis)
        assertEquals(6.0, s.decision.deficitPct!!, 1e-9)
        assertTrue(r.notices.none { it is UiNotice.GoRecommended })
    }

    @Test fun pauseInLevelUpdatesDoesNotEndCharging() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.engine.onDestinationTrip(DestinationTrip(12, 48_800.0, 375_000, null))
        r.charge(60, 230.0)
        // 25 s without any level sample (measured on a busy head unit), then the backlog arrives.
        repeat(25) { r.now += 1_000L; r.engine.tick(); assertEquals(ChargingStatus.CHARGING, r.state().charging) }
        r.levelWh += 25 * 230.0 * 1000.0 / 3600.0
        r.engine.onBatteryLevelWh(r.levelWh)
        assertEquals(ChargingStatus.CHARGING, r.state().charging)
        assertTrue(r.notices.none { it is UiNotice.GoRecommended })
    }

    @Test fun parkedWithoutChargingIsNotCharging() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.idle(40)
        val s = r.state()
        assertEquals(Screen.NOT_CHARGING, s.screen)
        assertEquals(Emphasis.NONE, s.decision.emphasis)
        assertEquals(GoAvailability.LOCKED_NOT_CHARGING, s.go)
        r.engine.go()
        r.engine.stay()
        assertEquals(listOf<UiNotice>(UiNotice.NotCharging, UiNotice.NotCharging), r.notices)
    }

    @Test fun powerThatOnlyFellInTheFirstMinuteFallsBackToTheSocRule() {
        val r = Rig()
        r.levelWh = 62_700.0
        r.vehicle(soc = 81); r.navi()
        r.engine.onDestinationTrip(DestinationTrip(18, 48_800.0, 375_000, null))
        for (kw in 120 downTo 60 step 1) r.charge(2, kw.toDouble())
        val s = r.state()
        assertFalse(s.power!!.sessionMaxTrusted)
        assertEquals(6, s.decision.rule)
        assertEquals(Emphasis.GO, s.decision.emphasis)
    }

    // ---- Driving restriction: locked unless the platform says "not restricted" ----

    @Test fun screenStaysLockedUntilTheDrivingRestrictionIsKnown() {
        val r = Rig()
        r.engine.onCapacityWh(CAPACITY_WH)
        r.engine.onSoc(74)
        r.navi()
        r.engine.onDestinationTrip(DestinationTrip(20, 48_800.0, 375_000, null))
        r.charge(45, 230.0) // level rises, but nothing says the car is parked
        val s = r.state()
        assertEquals(Screen.RESTRICTION_UNKNOWN, s.screen)
        assertNull(s.socPercent)
        assertNull(s.arrival)
        assertNull(s.destination)
        assertEquals(GoAvailability.LOCKED_NOT_CHARGING, s.go)
        r.engine.go(); r.engine.stay(); r.engine.adjustReserveSoc(5); r.engine.confirmCharging()
        assertTrue(r.navi.routes.isEmpty())
        assertTrue(r.notices.isEmpty())
        assertEquals(15, r.state().reserveSocPct)
    }

    @Test fun lockComesBeforeConsentAndPermission() {
        val r = Rig(consent = null)
        r.engine.onPermissionDenied()
        assertEquals(Screen.RESTRICTION_UNKNOWN, r.state().screen)
        r.engine.onDrivingRestricted(true)
        assertEquals(Screen.DRIVING, r.state().screen)
        r.parked()
        assertEquals(Screen.CONSENT_REQUIRED, r.state().screen)
        r.engine.acceptConsent()
        assertEquals(Screen.VEHICLE_LINK_UNAVAILABLE, r.state().screen)
    }

    @Test fun drivingHidesEverythingAndEndsTheSession() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.charge(45, 230.0)
        r.engine.onDrivingRestricted(true)
        val driving = r.state()
        assertEquals(Screen.DRIVING, driving.screen)
        assertEquals(ChargingStatus.NOT_CHARGING, driving.charging)
        assertTrue(driving.powerHistory.isEmpty())
        assertNull(driving.occupancy)
        assertNull(driving.socPercent)
        r.parked()
        assertEquals(Screen.NOT_CHARGING, r.state().screen)
    }

    @Test fun regenerationWhileDrivingIsNotCharging() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.engine.onDrivingRestricted(true)
        r.charge(60, 40.0) // level rises downhill
        r.parked()
        r.idle(1)
        assertEquals(ChargingStatus.NOT_CHARGING, r.state().charging)
    }

    // ---- Consent ----

    @Test fun nothingIsShownBeforeTheDataNoticeIsAccepted() {
        val r = Rig(consent = null)
        r.parked()
        var s = r.state()
        assertEquals(Screen.CONSENT_REQUIRED, s.screen)
        assertFalse(r.engine.consentGiven)
        assertFalse(s.consentDeclined)
        r.engine.declineConsent()
        s = r.state()
        assertEquals(Screen.CONSENT_REQUIRED, s.screen)
        assertTrue(s.consentDeclined)
        r.engine.acceptConsent()
        assertTrue(r.engine.consentGiven)
        assertEquals(Screen.CONNECTING, r.state().screen)
    }

    @Test fun withdrawingConsentDeletesWhatWasKept() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.charge(45, 230.0)
        r.engine.adjustReserveSoc(10)
        assertTrue(r.state().hasChargingHistory)
        r.engine.declineConsent()
        assertEquals(Screen.CONSENT_REQUIRED, r.state().screen)
        assertTrue(r.bands.isEmpty())
        assertEquals(15, r.settings.reserveSocPct)
        val kept = r.engine.sessionState()
        assertNull(kept.chargingSinceMs)
        assertNull(kept.sessionMaxKw)
        assertFalse(kept.wasCharging)
    }

    @Test fun consentAndDeletionAreIgnoredWhileLocked() {
        val r = Rig(consent = null)
        r.engine.acceptConsent() // restriction still unknown
        assertFalse(r.engine.consentGiven)
        r.engine.onDrivingRestricted(true)
        r.engine.acceptConsent()
        assertFalse(r.engine.consentGiven)
    }

    @Test fun chargingHistoryCanBeDeleted() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.charge(45, 230.0)
        assertTrue(r.state().hasChargingHistory)
        r.engine.clearChargingHistory()
        assertFalse(r.state().hasChargingHistory)
        assertEquals(UiNotice.HistoryCleared, r.notices.last())
    }

    // ---- Vehicle link ----

    @Test fun deniedPermissionShowsNoVehicleValues() {
        val r = Rig()
        r.parked()
        r.engine.onPermissionDenied()
        r.engine.onSoc(74)
        val s = r.state()
        assertEquals(Screen.VEHICLE_LINK_UNAVAILABLE, s.screen)
        assertEquals(VehicleLinkFailure.PERMISSION_DENIED, s.vehicleLinkFailure)
        assertNull(s.socPercent)
        r.engine.onPermissionGranted()
        assertNull(r.state().vehicleLinkFailure)
    }

    @Test fun unsupportedSocStopsTheVehicleLink() {
        val r = Rig()
        r.parked()
        r.engine.onVehicleLinkFailed(VehicleLinkFailure.SOC_UNSUPPORTED, "accessType=NONE")
        val s = r.state()
        assertEquals(Screen.VEHICLE_LINK_UNAVAILABLE, s.screen)
        assertEquals(VehicleLinkFailure.SOC_UNSUPPORTED, s.vehicleLinkFailure)
    }

    @Test fun transientLinkFailureClearsWhenSocArrives() {
        val r = Rig()
        r.parked()
        r.engine.onVehicleLinkFailed(VehicleLinkFailure.UNAVAILABLE, "get: timeout")
        r.engine.onSoc(74)
        assertNull(r.state().vehicleLinkFailure)
    }

    @Test fun outOfRangeValuesAreIgnored() {
        val r = Rig()
        r.vehicle(soc = 74)
        r.engine.onSoc(250)
        r.engine.onBatteryLevelWh(-1.0)
        val s = r.state()
        assertEquals(74, s.socPercent)
        assertEquals(57.3, s.batteryLevelKwh!!, 1e-9)
    }

    @Test fun unreadableChargeSignalsNeedDriverConfirmation() {
        val r = Rig()
        r.parked()
        r.engine.onSoc(74)
        r.navi()
        r.engine.onDestinationTrip(DestinationTrip(20, 48_800.0, 375_000, null))
        assertEquals(Screen.CONNECTING, r.state().screen)
        r.now += 6_000L
        assertEquals(Screen.CHARGING_UNCONFIRMED, r.state().screen)
        r.engine.confirmCharging()
        val s = r.state()
        assertEquals(Screen.DECISION, s.screen)
        assertEquals(ChargingBasis.DRIVER_CONFIRMED, s.chargingBasis)
        assertEquals(6, s.decision.rule) // no power: SOC rule
    }

    // ---- Arrival estimate ----

    @Test fun naviDownAsksForATargetSocAndDecidesOnIt() {
        val r = Rig()
        r.vehicle(soc = 74)
        r.engine.onNaviUnavailable("initialize: bind failed")
        r.charge(45, 230.0)
        assertEquals(Screen.TARGET_SOC_NEEDED, r.state().screen)

        r.engine.adjustTargetSoc(-5) // the first tap, whichever button, sets the starting value
        var s = r.state()
        assertEquals(80, s.manualTargetSoc)
        assertEquals(Screen.DECISION, s.screen)
        assertEquals(ArrivalBasis.DRIVER_TARGET, s.arrival!!.basis)
        assertEquals(6.0, s.arrival!!.deficitPct!!, 1e-9)
        assertEquals(Emphasis.STAY, s.decision.emphasis)
        assertEquals(GoAvailability.LOCKED_NAVI_UNAVAILABLE, s.go)

        r.engine.onSoc(80)
        r.charge(40, 60.0) // well under half of the 230 kW session max
        s = r.state()
        assertTrue(s.arrival!!.reserveMet)
        assertEquals(Emphasis.GO, s.decision.emphasis)
        r.engine.go()
        assertTrue(r.navi.routes.isEmpty())
        assertEquals(UiNotice.NaviUnavailable, r.notices.last())
    }

    @Test fun targetStartsAboveTheCurrentSocAndStaysInRange() {
        val r = Rig()
        r.vehicle(soc = 88)
        r.engine.onNaviUnavailable("down")
        r.charge(45, 100.0)
        r.engine.adjustTargetSoc(5)
        assertEquals(90, r.state().manualTargetSoc)
        repeat(10) { r.engine.adjustTargetSoc(5) }
        assertEquals(StayGoEngine.TARGET_MAX, r.state().manualTargetSoc)
    }

    @Test fun repeatedTripFailuresAskForATargetSocUntilTheNaviAnswers() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.charge(45, 230.0)
        assertEquals(Screen.DECISION, r.state().screen) // still waiting for the first answer
        r.engine.onTripFailed("timeout DESTINATION")
        r.engine.onTripFailed("timeout DESTINATION")
        assertEquals(Screen.TARGET_SOC_NEEDED, r.state().screen)
        val before = r.navi.tripRequests
        r.charge(30, 230.0)
        assertTrue("trip is retried while failing", r.navi.tripRequests > before)

        r.engine.onDestinationTrip(DestinationTrip(20, 48_800.0, 375_000, null))
        val s = r.state()
        assertEquals(Screen.DECISION, s.screen)
        assertEquals(ArrivalBasis.NAVI_DIRECT, s.arrival!!.basis)
    }

    @Test fun oldEstimateIsDroppedWhenTheNaviKeepsFailing() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.engine.onDestinationTrip(DestinationTrip(20, 48_800.0, 375_000, null))
        r.charge(45, 230.0)
        r.engine.onTripFailed("timeout DESTINATION")
        r.engine.onTripFailed("timeout DESTINATION")
        assertEquals("a fresh estimate is still used", Screen.DECISION, r.state().screen)
        r.charge(100, 230.0)
        val s = r.state()
        assertEquals(Screen.TARGET_SOC_NEEDED, s.screen)
        assertNull(s.arrival)
        r.engine.adjustTargetSoc(5)
        assertEquals(ArrivalBasis.DRIVER_TARGET, r.state().arrival!!.basis)
    }

    @Test fun insertedStopMakesArrivalComeFromRouteEnergy() {
        val r = Rig()
        r.levelWh = 60_000.0
        r.vehicle(soc = 77); r.navi()
        // The navigation reports 10 % because it plans to charge on the way; without charging it is (60 − 54.6) / 77.4.
        r.engine.onDestinationTrip(DestinationTrip(10, 54_600.0, 375_000, STOP))
        val s = r.state()
        assertEquals(ArrivalBasis.NAVI_ROUTE_ENERGY, s.arrival!!.basis)
        assertEquals(6.98, s.arrival!!.socPct!!, 0.01)
        assertTrue(s.route is RouteState.ViaStop)
    }

    @Test fun insertedStopWithoutBatteryLevelFallsBackToTheTargetSoc() {
        val r = Rig()
        r.parked()
        r.engine.onSoc(60)
        r.engine.onChargeState(1)
        r.navi()
        r.engine.onDestinationTrip(DestinationTrip(10, 54_600.0, 375_000, STOP))
        val s = r.state()
        assertEquals(Screen.TARGET_SOC_NEEDED, s.screen)
        assertNull(s.arrival)
        assertEquals(RouteState.None, s.route)
        // The arrival without the stop cannot be computed, so Go must not take the stop out of the route.
        assertEquals(GoAvailability.VIA_STOP, s.go)
        r.engine.go()
        assertEquals(listOf<ChargingStop?>(STOP), r.navi.routes)
    }

    @Test fun fasterStopAheadSendsTheRouteThroughIt() {
        val r = Rig()
        r.levelWh = 45_000.0
        r.bands.record(18, 240.0)
        r.vehicle(soc = 58); r.navi()
        r.charge(45, 50.0)
        r.engine.onDestinationTrip(DestinationTrip(10, 56_600.0, 375_000, STOP))
        r.engine.onChargingStopTrip(STOP, 18)
        r.engine.onActiveWaypoints(listOf(ActiveWaypoint("청도새마을휴게소", 200, 6)))
        val s = r.state()
        assertEquals(5, s.decision.rule)
        assertEquals(GoAvailability.VIA_STOP, s.go)
        val via = s.route as RouteState.ViaStop
        assertEquals(200.0, via.maxPowerKw!!, 1e-9)
        assertTrue(via.comparison!!.gainMinutes > 10.0)
        r.engine.go()
        assertEquals(listOf<ChargingStop?>(STOP), r.navi.routes)
    }

    @Test fun stopWithoutKnownOutputIsNotCompared() {
        val r = Rig()
        r.levelWh = 45_000.0
        r.vehicle(soc = 58); r.navi()
        r.charge(45, 50.0)
        r.engine.onDestinationTrip(DestinationTrip(10, 56_600.0, 375_000, STOP))
        r.engine.onChargingStopTrip(STOP, 18)
        val s = r.state()
        assertEquals(2, s.decision.rule)
        // The stop is the navigation's own plan: Go keeps it instead of sending a route without charging.
        assertEquals(GoAvailability.VIA_STOP, s.go)
        r.engine.go()
        assertEquals(listOf<ChargingStop?>(STOP), r.navi.routes)
        assertNull((s.route as RouteState.ViaStop).comparison)
    }

    @Test fun sameNameAtAnotherPlaceIsANewDestination() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.engine.onDestinationTrip(DestinationTrip(20, 48_800.0, 375_000, null))
        val before = r.navi.tripRequests
        r.engine.onDestination(BUSAN.copy(latitude = 37.5, longitude = 127.0))
        assertNull(r.state().arrival)
        assertEquals(before + 1, r.navi.tripRequests)
    }

    @Test fun socMismatchAboveThreePointsIsReported() {
        val r = Rig()
        r.vehicle(soc = 79) // level is 57.3 / 77.4 = 74.0 %
        val m = r.state().socMismatch!!
        assertEquals(79, m.pleosSoc)
        assertEquals(74.0, m.levelSoc, 0.1)
        r.engine.onSoc(76)
        assertNull(r.state().socMismatch)
    }

    @Test fun raisingTheReserveTurnsAMetArrivalIntoStay() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.engine.onDestinationTrip(DestinationTrip(16, 48_800.0, 375_000, null))
        r.charge(45, 230.0)
        assertTrue(r.state().arrival!!.reserveMet)
        r.engine.adjustReserveSoc(5)
        val s = r.state()
        assertEquals(20, s.reserveSocPct)
        assertEquals(2, s.decision.rule)
        assertEquals(4.0, s.arrival!!.deficitPct!!, 1e-9)
        repeat(20) { r.engine.adjustReserveSoc(5) }
        assertEquals(StayGoEngine.RESERVE_MAX, r.state().reserveSocPct)
        repeat(20) { r.engine.adjustReserveSoc(-5) }
        assertEquals(StayGoEngine.RESERVE_MIN, r.state().reserveSocPct)
    }

    @Test fun destinationClearedByTheRequestedRerouteKeepsTheDecision() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.engine.onDestinationTrip(DestinationTrip(20, 48_800.0, 375_000, null))
        r.charge(45, 230.0)
        r.engine.onRouteResult(null, true, null)
        r.engine.onDestination(null) // the navigation drops the old route before starting the new one
        assertEquals(Screen.DECISION, r.state().screen)
        r.now += 11_000L
        r.engine.onDestination(null) // a real cancellation later is honoured
        assertEquals(GoAvailability.LOCKED_NO_DESTINATION, r.state().go)
    }

    @Test fun tripIsRequestedAgainWhenSocChanges() {
        val r = Rig()
        r.vehicle(soc = 74); r.navi()
        val before = r.navi.tripRequests
        r.engine.onSoc(74)
        assertEquals(before, r.navi.tripRequests)
        r.engine.onSoc(75)
        assertEquals(before + 1, r.navi.tripRequests)
    }

    @Test fun occupancyWarnsTenMinutesBeforeTheOneHourLimit() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.charge(49 * 60, 20.0)
        assertFalse(r.state().occupancy!!.warning)
        r.charge(2 * 60, 20.0)
        val o = r.state().occupancy!!
        assertTrue(o.warning)
        assertEquals(50, o.minutesObserved)
    }

    @Test fun goNoticeIsSentOncePerSession() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.engine.onDestinationTrip(DestinationTrip(20, 48_800.0, 375_000, null))
        r.charge(60, 230.0)
        r.state()
        r.charge(60, 95.0)
        r.state()
        r.engine.onDestinationTrip(DestinationTrip(14, 48_800.0, 375_000, null)) // the navigation re-plans: back to Stay
        r.state()
        r.engine.onDestinationTrip(DestinationTrip(20, 48_800.0, 375_000, null))
        r.state()
        assertEquals(1, r.notices.count { it is UiNotice.GoRecommended })
    }

    // ---- Background and process recreation ----

    @Test fun sessionContinuesWhenTheScreenComesBackWhileStillCharging() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.engine.onDestinationTrip(DestinationTrip(20, 48_800.0, 375_000, null))
        r.charge(120, 230.0)
        r.state()
        r.charge(60, 95.0)
        r.state()
        assertEquals(1, r.notices.count { it is UiNotice.GoRecommended })
        val max = r.state().power!!.sessionMaxKw!!
        r.engine.suspend()
        r.engine.onRestrictionUnknown()
        var s = r.state()
        assertEquals(Screen.RESTRICTION_UNKNOWN, s.screen)
        assertNull("nothing read before the gap is shown", s.arrival)

        r.now += 120_000L
        r.levelWh += 3_000.0 // charging went on while another app was in front
        r.engine.resume()
        r.parked()
        r.engine.onSoc(84)
        r.navi()
        r.engine.onDestinationTrip(DestinationTrip(21, 48_800.0, 375_000, null))
        r.engine.onBatteryLevelWh(r.levelWh)
        s = r.state()
        assertEquals(ChargingStatus.CHARGING, s.charging)
        assertEquals(4, s.occupancy!!.minutesObserved) // counted from the first sighting, across the gap
        r.charge(20, 95.0)
        s = r.state()
        assertEquals(max, s.power!!.sessionMaxKw!!, 1e-9)
        assertEquals(4, s.decision.rule)
        assertEquals("no second Go notice after coming back", 1, r.notices.count { it is UiNotice.GoRecommended })
    }

    @Test fun levelThatArrivedBeforeTheRestrictionDecidesTheSessionOnceParked() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.charge(120, 230.0)
        r.engine.suspend()
        r.engine.onRestrictionUnknown()
        r.now += 30_000L
        r.levelWh += 1_000.0
        r.engine.resume()
        r.engine.onBatteryLevelWh(r.levelWh) // the car reports the level only on change, and it came first
        r.parked()
        assertEquals(ChargingStatus.CHARGING, r.state().charging)
    }

    @Test fun staleValuesAreNotShownAfterComingBackToADeadNavi() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.engine.onDestinationTrip(DestinationTrip(20, 48_800.0, 375_000, null))
        r.charge(120, 230.0)
        r.engine.suspend()
        r.now += 60_000L
        r.levelWh += 2_000.0
        r.engine.resume()
        r.engine.onSoc(79)
        r.engine.onBatteryLevelWh(r.levelWh)
        r.engine.onNaviUnavailable("initialize: bind failed")
        var s = r.state()
        assertEquals(Screen.TARGET_SOC_NEEDED, s.screen)
        assertNull(s.arrival)
        r.engine.adjustTargetSoc(5)
        s = r.state()
        assertEquals(Screen.DECISION, s.screen)
        assertEquals(ArrivalBasis.DRIVER_TARGET, s.arrival!!.basis)
    }

    @Test fun sessionEndsWhenTheCarDroveWhileTheScreenWasAway() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.charge(120, 230.0)
        r.engine.suspend()
        r.now += 30 * 60_000L
        r.levelWh -= 6_000.0
        r.engine.resume()
        r.engine.onBatteryLevelWh(r.levelWh)
        val s = r.state()
        assertEquals(ChargingStatus.NOT_CHARGING, s.charging)
        assertNull(s.occupancy)
    }

    @Test fun recreatedProcessRestoresTheSession() {
        val first = Rig()
        first.vehicle(); first.navi()
        first.charge(120, 230.0)
        first.engine.suspend()
        val saved = first.engine.sessionState()

        val r = Rig()
        r.now = first.now + 20_000L
        r.levelWh = first.levelWh + 500.0
        r.engine.restore(saved)
        r.engine.resume()
        r.parked()
        r.engine.onCapacityWh(CAPACITY_WH)
        r.engine.onSoc(84)
        r.engine.onBatteryLevelWh(r.levelWh)
        assertEquals(ChargingStatus.CHARGING, r.state().charging)
        assertEquals(saved.chargingSinceMs, r.engine.sessionState().chargingSinceMs)
        r.charge(20, 95.0)
        assertEquals(saved.sessionMaxKw!!, r.state().power!!.sessionMaxKw!!, 1e-9)
    }

    @Test fun restoredValuesTheEngineCouldNotHaveProducedAreDropped() {
        val r = Rig()
        r.now = 100_000L
        r.engine.restore(
            StayGoEngine.SessionState(
                chargingSinceMs = 9_000_000L, wasCharging = true, sessionMaxKw = 1e9, pmaxTrusted = true,
                manualTargetSoc = 500, goNoticeSent = false, suspendedAtMs = -5L, levelAtSuspendWh = -1.0, chargingAtSuspend = true,
            )
        )
        val s = r.engine.sessionState()
        assertNull(s.chargingSinceMs)
        assertFalse(s.wasCharging)
        assertNull(s.sessionMaxKw)
        assertNull(s.manualTargetSoc)
        assertNull(s.suspendedAtMs)
        r.vehicle()
        assertEquals(Screen.NOT_CHARGING, r.state().screen)
    }

    @Test fun carWithoutBatteryLevelRecoversAfterTheScreenComesBack() {
        val r = Rig()
        r.parked()
        r.engine.onSoc(60)
        r.navi()
        r.now += 6_000L
        r.engine.confirmCharging()
        assertEquals(ChargingStatus.CHARGING, r.state().charging)
        r.engine.suspend()
        r.now += 30_000L
        r.engine.resume()
        r.engine.onSoc(62)
        r.now += 6_000L
        r.engine.tick()
        // No level sample will ever settle the session: past the grace it continues on the driver's word.
        assertEquals(ChargingStatus.CHARGING, r.state().charging)
        assertEquals(ChargingBasis.DRIVER_CONFIRMED, r.state().chargingBasis)

        // A longer gap may hide a drive: the driver is asked again instead of trusted.
        r.engine.suspend()
        r.now += 20 * 60_000L
        r.engine.resume()
        r.engine.onSoc(62)
        r.now += 6_000L
        r.engine.tick()
        assertEquals(Screen.CHARGING_UNCONFIRMED, r.state().screen)
        assertNull(r.state().occupancy)

        // A recreated process no longer has the driver's word: it asks again instead of showing "finished".
        r.engine.confirmCharging()
        assertEquals(ChargingStatus.CHARGING, r.state().charging)
        r.engine.suspend()
        val saved = r.engine.sessionState()
        val again = Rig()
        again.now = r.now + 20_000L
        again.engine.restore(saved)
        again.engine.resume()
        again.parked()
        again.engine.onSoc(63)
        again.now += 6_000L
        again.engine.tick()
        assertEquals(Screen.CHARGING_UNCONFIRMED, again.state().screen)

        // The same car with a readable charge state needs no confirmation.
        r.engine.suspend()
        r.now += 60_000L
        r.engine.resume()
        r.engine.onChargeState(1)
        r.now += 6_000L
        r.engine.tick()
        assertEquals(ChargingBasis.CHARGE_STATE, r.state().chargingBasis)
    }

    @Test fun coarseLevelStepsAtLowPowerDoNotEndCharging() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.engine.onDestinationTrip(DestinationTrip(20, 48_800.0, 375_000, null))
        r.charge(60, 150.0)
        // 100 Wh steps at 20 kW: the reported level changes every 18 s.
        var exact = r.levelWh
        repeat(180) {
            r.now += 1_000L
            exact += 20.0 * 1000.0 / 3600.0
            r.levelWh = Math.floor(exact / 100.0) * 100.0
            r.engine.onBatteryLevelWh(r.levelWh)
            r.engine.tick()
            if (it > 60) assertEquals("second $it", ChargingStatus.CHARGING, r.state().charging)
        }
    }

    @Test fun routeCancelledRightAfterGoClearsTheDestination() {
        val r = Rig()
        r.vehicle(); r.navi()
        r.engine.onDestinationTrip(DestinationTrip(20, 48_800.0, 375_000, null))
        r.charge(45, 230.0)
        r.engine.onRouteResult(null, true, null)
        r.engine.onDestination(null) // cancelled, and no new route follows
        r.charge(12, 230.0)
        val s = r.state()
        assertNull(s.destination)
        assertNull(s.arrival)
        assertEquals(GoAvailability.LOCKED_NO_DESTINATION, s.go)
    }

    @Test fun plannedStopMatchesActiveWaypointByLeadingName() {
        val waypoints = listOf(ActiveWaypoint("여주휴게소", 350, 6), ActiveWaypoint("청도새마을휴게소", 300, 6))
        assertEquals(300, StayGoEngine.matchWaypoint(STOP.name, waypoints)!!.maxPowerKw)
        assertNull(StayGoEngine.matchWaypoint("칠곡휴게소 E-pit", waypoints))
    }

    private companion object {
        const val CAPACITY_WH = 77_400.0
        val BUSAN = Destination("부산시청", 35.1798, 129.0750, 375_000)
        val STOP = ChargingStop("청도새마을휴게소(부산방향) 전기차충전소", 35.6712, 128.7390, 0.82)
    }
}
