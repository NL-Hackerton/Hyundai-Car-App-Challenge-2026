package ai.staygo.core

import ai.staygo.decision.Candidate
import ai.staygo.decision.ChargingStatus
import ai.staygo.decision.Decision
import ai.staygo.decision.DecisionModel
import ai.staygo.decision.Emphasis
import ai.staygo.decision.Inputs
import ai.staygo.decision.Params
import ai.staygo.signal.PowerEstimator
import ai.staygo.signal.PowerTrend
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Session state of the app: consumes the driving restriction and the vehicle and navigation events, produces
 * [StayGoUiState]. Plain Kotlin, single-threaded; every call must come from the same thread (the main thread
 * in the app). Time comes from [clock] (monotonic milliseconds) so the engine can be replayed in tests.
 *
 * Order of precedence: the driving restriction (unknown counts as restricted), then the driver's consent to
 * the data notice, then the vehicle link, then the charging session.
 */
class StayGoEngine(
    private val navi: NaviSource,
    private val bands: BandStore,
    private val settings: SettingsStore,
    private val clock: () -> Long,
    private val baseParams: Params = Params(),
    private val notify: (UiNotice) -> Unit = {},
    /** Diagnostic lines. They carry battery figures but no place names. */
    private val log: (String) -> Unit = {},
    /** Called after every event that can change the state. */
    private val onChange: () -> Unit = {},
) : VehicleSource.Listener, NaviSource.Listener {

    /** Bundled station snapshot; fills in a stop's max output when the active route does not provide it. */
    var stations: StationDirectory? = null
        set(value) { field = value; onChange() }

    private var startedAtMs: Long? = null

    // Driving restriction. Fail-closed: locked until the platform says distraction optimization is not required.
    private var restricted = true
    private var restrictionKnown = false
    private val unlocked: Boolean get() = restrictionKnown && !restricted

    // Vehicle
    private var linkFailure: VehicleLinkFailure? = null
    private var soc: Int? = null
    private var levelWh: Double? = null
    private var capacityWh: Double? = null
    private var chargeState: Int? = null
    private var instantaneousKw: Double? = null

    // Charging session
    private var estimator = PowerEstimator()
    private var trend = PowerTrend()
    private val powerHistory = ArrayList<PowerSample>()
    private var charging = ChargingStatus.UNKNOWN
    private var chargingBasis: ChargingBasis? = null
    private var chargingSinceMs: Long? = null
    private var wasCharging = false
    private var driverConfirmedCharging = false
    private var manualTargetSoc: Int? = null
    private var suspendedAtMs: Long? = null
    private var levelAtSuspendWh: Double? = null
    private var chargingAtSuspend = false
    private var resumePending = false

    // Navigation
    private var naviReady = false
    private var naviUnavailable = false
    private var destination: Destination? = null
    private var destinationTrip: DestinationTrip? = null
    private var tripAtMs: Long? = null
    private var stopArrival: Pair<ChargingStop, Int>? = null
    private var activeWaypoints: List<ActiveWaypoint> = emptyList()
    private var lastTripSoc: Int? = null
    private var lastTripRequestMs: Long? = null
    private var tripFailures = 0
    private var routeSentAtMs: Long? = null

    private var lastDecision: Decision? = null
    private var goNoticeSent = false

    private val params: Params get() = baseParams.copy(reserveSocPct = settings.reserveSocPct.toDouble())

    /** True once the driver agreed to the data notice: only then may the vehicle and navigation sources run. */
    val consentGiven: Boolean get() = settings.loaded && settings.consent == true

    fun start() {
        startedAtMs = clock()
    }

    // ---- Session across the background and process recreation ----

    /** What survives the screen going away and the process being recreated. Times are monotonic milliseconds. */
    data class SessionState(
        val chargingSinceMs: Long?,
        val wasCharging: Boolean,
        val sessionMaxKw: Double?,
        val pmaxTrusted: Boolean,
        val manualTargetSoc: Int?,
        val goNoticeSent: Boolean,
        val suspendedAtMs: Long?,
        val levelAtSuspendWh: Double?,
        val chargingAtSuspend: Boolean,
    )

    fun sessionState() = SessionState(
        chargingSinceMs, wasCharging, sessionMaxKw(), pmaxTrusted(),
        manualTargetSoc, goNoticeSent, suspendedAtMs, levelAtSuspendWh, chargingAtSuspend,
    )

    /**
     * Call before [start] in a recreated process. Values outside what the engine itself could have produced
     * are dropped. Whether the session is still the same is decided on the first battery level.
     */
    fun restore(s: SessionState) {
        val now = clock()
        chargingSinceMs = s.chargingSinceMs?.takeIf { it in 0..now }
        wasCharging = s.wasCharging && chargingSinceMs != null
        val max = s.sessionMaxKw?.takeIf { it > 0.0 && it <= MAX_PLAUSIBLE_KW }
        estimator.restore(max, s.pmaxTrusted)
        trend.restore(max, s.pmaxTrusted)
        manualTargetSoc = s.manualTargetSoc?.takeIf { it in TARGET_MIN..TARGET_MAX }
        goNoticeSent = s.goNoticeSent
        suspendedAtMs = s.suspendedAtMs?.takeIf { it in 0..now }
        levelAtSuspendWh = s.levelAtSuspendWh?.takeIf { it >= 0.0 }
        chargingAtSuspend = s.chargingAtSuspend
        resumePending = suspendedAtMs != null
    }

    /** The screen is no longer visible and the owner stops the sources: nothing is observed until [resume]. */
    fun suspend() {
        suspendedAtMs = clock()
        levelAtSuspendWh = levelWh
        chargingAtSuspend = charging == ChargingStatus.CHARGING
        // Until the first battery level after the gap, the session is neither continued nor ended.
        resumePending = true
        // Nothing read before the gap is shown as current afterwards.
        soc = null
        levelWh = null
        chargeState = null
        instantaneousKw = null
        naviReady = false
        naviUnavailable = false
        destination = null
        clearTrip()
        activeWaypoints = emptyList()
    }

    /** The screen is visible again and the owner restarts the sources. */
    fun resume() {
        startedAtMs = clock()
        resumePending = suspendedAtMs != null
    }

    // ---- Driving restriction ----

    /** The restriction is no longer observed (the screen went away): locked until it is reported again. */
    fun onRestrictionUnknown() {
        restricted = true
        restrictionKnown = false
        onChange()
    }

    fun onDrivingRestricted(restricted: Boolean) {
        if (restrictionKnown && restricted == this.restricted) return
        restrictionKnown = true
        log("driving restricted=$restricted")
        this.restricted = restricted
        if (restricted) {
            endSession()
        } else {
            // A level that arrived while the restriction was still unknown decides the pending session now.
            val level = levelWh
            if (resumePending && level != null) continueOrEndSession(clock(), level)
        }
        onChange()
    }

    // ---- Driver actions ----

    fun acceptConsent() {
        settings.consent = true
        onChange()
    }

    fun declineConsent() {
        settings.consent = false
        onChange()
    }

    fun clearChargingHistory() {
        bands.clear()
        notify(UiNotice.HistoryCleared)
        onChange()
    }

    fun onPermissionDenied() {
        linkFailure = VehicleLinkFailure.PERMISSION_DENIED
        onChange()
    }

    fun onPermissionGranted() {
        if (linkFailure == VehicleLinkFailure.PERMISSION_DENIED) linkFailure = null
        onChange()
    }

    fun stay() {
        val state = snapshot()
        if (!state.screen.interactive) return
        notify(if (charging == ChargingStatus.CHARGING) UiNotice.StayAcknowledged else UiNotice.NotCharging)
    }

    fun go() {
        val state = snapshot()
        if (!state.screen.interactive) return
        when (state.go) {
            GoAvailability.LOCKED_NO_DESTINATION -> notify(UiNotice.DestinationRequired)
            GoAvailability.LOCKED_NAVI_UNAVAILABLE -> notify(UiNotice.NaviUnavailable)
            GoAvailability.LOCKED_NOT_CHARGING -> notify(UiNotice.NotCharging)
            GoAvailability.DIRECT -> { log("GO rule=${state.decision.rule} via=no"); navi.go(null) }
            GoAvailability.VIA_STOP -> {
                log("GO rule=${state.decision.rule} via=stop")
                navi.go(destinationTrip?.chargingStop)
            }
        }
    }

    /** The driver states that the car is charging; used only while no charging signal can be read. */
    fun confirmCharging() {
        if (unlocked && charging == ChargingStatus.UNKNOWN) driverConfirmedCharging = true
        onChange()
    }

    fun adjustReserveSoc(deltaPct: Int) {
        if (!unlocked) return
        settings.reserveSocPct = (settings.reserveSocPct + deltaPct).coerceIn(RESERVE_MIN, RESERVE_MAX)
        onChange()
    }

    /**
     * Target SOC that replaces the arrival estimate while the navigation gives none. The first tap sets the
     * starting value (80 %, or the next 5 % step above the current SOC); later taps move it.
     */
    fun adjustTargetSoc(deltaPct: Int) {
        if (!unlocked) return
        val current = manualTargetSoc
        manualTargetSoc = if (current == null) defaultTargetSoc() else (current + deltaPct).coerceIn(TARGET_MIN, TARGET_MAX)
        onChange()
    }

    private fun defaultTargetSoc(): Int {
        val above = soc?.let { (ceil((it + 1) / 5.0) * 5).toInt() } ?: DEFAULT_TARGET_SOC
        return maxOf(DEFAULT_TARGET_SOC, above).coerceIn(TARGET_MIN, TARGET_MAX)
    }

    // ---- VehicleSource.Listener ----

    override fun onVehicleLinkFailed(failure: VehicleLinkFailure, detail: String) {
        log("vehicle link failed: $failure $detail")
        // A denied permission explains every later failure; keep the most specific reason.
        if (linkFailure != VehicleLinkFailure.PERMISSION_DENIED) linkFailure = failure
        onChange()
    }

    override fun onSoc(soc: Int) {
        if (soc !in 0..100) return
        this.soc = soc
        // A delivered SOC proves the link works again after a transient failure.
        if (linkFailure == VehicleLinkFailure.UNAVAILABLE) linkFailure = null
        if (soc != lastTripSoc) requestTrip()
        onChange()
    }

    override fun onBatteryLevelWh(wh: Double) {
        if (wh < 0.0) return
        val now = clock()
        levelWh = wh
        // A rising level while driving is regeneration, not charging.
        if (unlocked) {
            if (resumePending) continueOrEndSession(now, wh) else estimator.addLevel(now, wh)
            advance(now)
            recordPower(now)
        }
        onChange()
    }

    override fun onChargeState(state: Int) {
        chargeState = state
        onChange()
    }

    override fun onInstantaneousRateKw(kw: Double) {
        instantaneousKw = kw
        if (unlocked && kw > INSTANT_MIN_KW && kw <= MAX_PLAUSIBLE_KW) trend.add(clock(), kw)
        onChange()
    }

    override fun onCapacityWh(wh: Double) {
        if (wh > 0) capacityWh = wh
        onChange()
    }

    /**
     * First battery level after the screen came back. A level well below the one at suspension means the car
     * drove meanwhile, and a long gap means another stop: both end the session. Otherwise it continues.
     */
    private fun continueOrEndSession(now: Long, wh: Double) {
        resumePending = false
        val before = levelAtSuspendWh
        val since = suspendedAtMs
        suspendedAtMs = null
        levelAtSuspendWh = null
        if (before == null || since == null) {
            estimator.addLevel(now, wh)
        } else if (now - since > SESSION_MAX_GAP_MS || wh < before - DROVE_AWAY_WH) {
            log("session ended while away")
            endSession()
            estimator.addLevel(now, wh)
        } else {
            // An unchanged level after a short look away says nothing yet: charging is assumed to go on and
            // the usual stall tolerance decides within seconds.
            val stillCharging = chargingAtSuspend && wh >= before - 1e-6 && now - since <= SHORT_GAP_MS
            estimator.resume(now, wh, rose = wh > before + 1e-6 || stillCharging)
            trend.resume()
        }
    }

    private fun recordPower(now: Long) {
        val p = currentPowerKw() ?: return
        if (charging != ChargingStatus.CHARGING) return
        powerHistory += PowerSample(now, p)
        while (powerHistory.size > MAX_HISTORY) powerHistory.removeAt(0)
        soc?.let { bands.record(it, p) }
    }

    // ---- NaviSource.Listener ----

    override fun onNaviReady() {
        naviReady = true
        naviUnavailable = false
        onChange()
    }

    override fun onNaviUnavailable(detail: String) {
        log("navi unavailable: $detail")
        naviUnavailable = true
        onChange()
    }

    override fun onDestination(destination: Destination?) {
        // The navigation clears the destination for a moment while it replaces the route the app just requested.
        if (destination == null && routeSentAtMs?.let { clock() - it < REROUTE_GRACE_MS } == true) return
        val changed = if (destination == null) this.destination != null else !destination.samePlaceAs(this.destination)
        this.destination = destination
        if (changed) clearTrip()
        if (changed || destinationTrip == null) requestTrip()
        onChange()
    }

    override fun onDestinationTrip(trip: DestinationTrip) {
        log("trip estSoc=${trip.estimatedSoc} energyWh=${trip.energyWh.toLong()} stop=${if (trip.chargingStop == null) "no" else "yes"}")
        tripFailures = 0
        destinationTrip = trip
        tripAtMs = clock()
        if (trip.chargingStop?.name != stopArrival?.first?.name) stopArrival = null
        onChange()
    }

    override fun onChargingStopTrip(stop: ChargingStop, estimatedSoc: Int) {
        log("stop trip estSoc=$estimatedSoc")
        stopArrival = stop to estimatedSoc
        onChange()
    }

    override fun onTripFailed(detail: String) {
        tripFailures++
        log("trip failed #$tripFailures: $detail")
        onChange()
    }

    override fun onActiveWaypoints(waypoints: List<ActiveWaypoint>) {
        activeWaypoints = waypoints
        onChange()
    }

    override fun onRouteResult(viaStop: ChargingStop?, sent: Boolean, detail: String?) {
        log("route via=${if (viaStop == null) "no" else "stop"} sent=$sent $detail")
        if (sent) routeSentAtMs = clock()
        notify(if (sent) UiNotice.RouteSent(viaStop?.name) else UiNotice.RouteFailed(detail))
    }

    private fun clearTrip() {
        destinationTrip = null
        tripAtMs = null
        stopArrival = null
        lastTripSoc = null
        lastTripRequestMs = null
        tripFailures = 0
    }

    // ---- State ----

    /** Call about once per second: retries trip requests and lets time-based states change. */
    fun tick() {
        val now = clock()
        advance(now)
        if (destination == null || !naviReady || charging != ChargingStatus.CHARGING) return
        val last = lastTripRequestMs
        val due = when {
            last == null -> true
            destinationTrip == null || tripFailures > 0 -> now - last >= retryDelayMs()
            else -> now - last >= TRIP_REFRESH_MS
        }
        if (due) requestTrip()
    }

    fun snapshot(): StayGoUiState {
        val now = clock()
        advance(now)
        val p = params
        val power = if (charging == ChargingStatus.CHARGING) currentPowerKw() else null
        // The session maximum lags the 30 s median by its own window; it is never reported below the current power.
        val pmax = sessionMaxKw()?.let { max -> if (power != null && power > max) power else max }
        val capacityKwh = capacityKwh()
        val trip = destinationTrip
        val candidate = candidate()
        val unavailable = arrivalUnavailable(now)
        val arrivalEstimate = if (unavailable) null else naviArrivalSoc()
        val target = manualTargetSoc
        val useTarget = unavailable && target != null
        val arrivalForModel = when {
            arrivalEstimate != null -> arrivalEstimate
            useTarget -> soc?.let { p.reserveSocPct + (it - target!!) }
            else -> null
        }
        val decision = DecisionModel.decide(
            Inputs(
                charging = charging,
                soc = soc,
                powerKw = power,
                pmaxKw = pmax,
                pmaxTrusted = pmaxTrusted(),
                hasDestination = destination != null || useTarget,
                arrivalSoc = arrivalForModel,
                candidate = candidate,
                bandPowerKw = candidate?.arrivalSoc?.let { bands.max(it.toInt()) },
                capacityKwh = capacityKwh ?: 0.0,
                params = p,
            )
        ).let { d -> if (capacityKwh == null) d.copy(extraWaitMinutes = null, comparison = null, rule = if (d.rule == 5) 2 else d.rule) else d }
        if (decision.rule != lastDecision?.rule || decision.emphasis != lastDecision?.emphasis || decision.state != lastDecision?.state) {
            log("DECISION rule=${decision.rule} emphasis=${decision.emphasis} state=${decision.state} soc=$soc A=${arrivalForModel?.let { "%.1f".format(it) }} P=${power?.let { "%.1f".format(it) }} Pmax=${pmax?.let { "%.1f".format(it) }} stop=${if (candidate == null) "no" else "yes"}/${candidate?.arrivalSoc}/${candidate?.maxPowerKw}")
        }

        val screen = screen(now, useTarget, unavailable)
        // The verdict can flicker while the navigation re-routes, so the Go notice is sent once per session.
        val previous = lastDecision
        if (!goNoticeSent && screen.interactive && previous != null && previous.emphasis != Emphasis.GO && decision.emphasis == Emphasis.GO) {
            goNoticeSent = true
            notify(UiNotice.GoRecommended(arrivalEstimate))
        }
        lastDecision = decision

        val showVehicle = unlocked && consentGiven && linkFailure == null
        val arrival = when {
            !showVehicle -> null
            charging != ChargingStatus.CHARGING && arrivalEstimate == null -> null
            arrivalEstimate != null -> ArrivalState(
                basis = if (trip?.chargingStop == null) ArrivalBasis.NAVI_DIRECT else ArrivalBasis.NAVI_ROUTE_ENERGY,
                socPct = arrivalEstimate,
                reserveMet = arrivalEstimate >= p.reserveSocPct,
                deficitPct = (p.reserveSocPct - arrivalEstimate).takeIf { it > 0 },
                extraWaitMinutes = decision.extraWaitMinutes,
            )
            useTarget && soc != null -> ArrivalState(
                basis = ArrivalBasis.DRIVER_TARGET,
                socPct = null,
                reserveMet = soc!! >= target!!,
                deficitPct = (target - soc!!).toDouble().takeIf { it > 0 },
                extraWaitMinutes = decision.extraWaitMinutes,
            )
            else -> null
        }
        return StayGoUiState(
            screen = screen,
            vehicleLinkFailure = linkFailure.takeIf { unlocked },
            charging = charging,
            chargingBasis = chargingBasis,
            socPercent = soc.takeIf { showVehicle },
            socMismatch = socMismatch().takeIf { showVehicle },
            power = power?.takeIf { showVehicle }?.let {
                PowerState(
                    kw = it,
                    estimated = !rateUsable(),
                    sessionMaxKw = pmax,
                    sessionMaxTrusted = pmaxTrusted(),
                    secondsPerPercent = capacityKwh?.let { c -> DecisionModel.secondsPerPercent(c, it) },
                )
            },
            powerHistory = if (showVehicle) powerHistory.toList() else emptyList(),
            destination = destination?.takeIf { showVehicle }?.let { DestinationState(it.name, it.remainDistanceM) },
            arrival = arrival,
            route = when {
                !showVehicle || destination == null || trip == null || unavailable -> RouteState.None
                trip.chargingStop == null -> RouteState.Direct
                else -> RouteState.ViaStop(
                    trip.chargingStop.name, candidate?.arrivalSoc, candidate?.maxPowerKw, candidate?.chargerCount, decision.comparison,
                    outputSource = stopOutput(trip.chargingStop)?.second,
                    snapshotAsOf = stations?.asOf,
                )
            },
            decision = decision,
            go = goAvailability(decision, unavailable),
            reserveSocPct = settings.reserveSocPct,
            manualTargetSoc = target,
            occupancy = chargingSinceMs?.takeIf { showVehicle && (charging == ChargingStatus.CHARGING || charging == ChargingStatus.FINISHED) }?.let {
                val min = ((now - it) / 60_000L).toInt()
                Occupancy(min, OCCUPANCY_LIMIT_MIN, min >= OCCUPANCY_WARN_MIN)
            },
            batteryLevelKwh = levelWh?.let { it / 1000.0 }.takeIf { showVehicle },
            capacityKwh = capacityKwh.takeIf { showVehicle },
            lateRatio = p.lateRatio,
            stopOverheadMin = p.stopOverheadMin,
            hasChargingHistory = !bands.isEmpty(),
            consentDeclined = settings.loaded && settings.consent == false,
            naviAvailable = naviReady && !naviUnavailable,
        )
    }

    private fun screen(now: Long, useTarget: Boolean, arrivalUnavailable: Boolean): Screen = when {
        !restrictionKnown -> Screen.RESTRICTION_UNKNOWN
        restricted -> Screen.DRIVING
        !settings.loaded -> Screen.CONNECTING
        settings.consent != true -> Screen.CONSENT_REQUIRED
        linkFailure != null -> Screen.VEHICLE_LINK_UNAVAILABLE
        charging == ChargingStatus.UNKNOWN ->
            if (connecting(now)) Screen.CONNECTING else Screen.CHARGING_UNCONFIRMED
        charging == ChargingStatus.NOT_CHARGING -> Screen.NOT_CHARGING
        charging == ChargingStatus.FINISHED -> Screen.CHARGE_FINISHED
        useTarget -> Screen.DECISION
        arrivalUnavailable -> Screen.TARGET_SOC_NEEDED
        destination == null -> Screen.NO_DESTINATION
        else -> Screen.DECISION
    }

    private fun connecting(now: Long) = now - (startedAtMs ?: now) < CONNECT_GRACE_MS

    private fun goAvailability(d: Decision, arrivalUnavailable: Boolean): GoAvailability = when {
        !unlocked -> GoAvailability.LOCKED_NOT_CHARGING
        charging != ChargingStatus.CHARGING && charging != ChargingStatus.FINISHED -> GoAvailability.LOCKED_NOT_CHARGING
        naviUnavailable || !naviReady -> GoAvailability.LOCKED_NAVI_UNAVAILABLE
        destination == null -> GoAvailability.LOCKED_NO_DESTINATION
        !arrivalUnavailable && d.rule == 5 && d.emphasis != Emphasis.NONE && destinationTrip?.chargingStop != null -> GoAvailability.VIA_STOP
        else -> GoAvailability.DIRECT
    }

    /** Recomputes the charging status and keeps the session markers in step with it. */
    private fun advance(now: Long) {
        val signalsReadable = chargeState != null || levelWh != null
        if (signalsReadable) driverConfirmedCharging = false
        val (status, basis) = when {
            !unlocked -> ChargingStatus.NOT_CHARGING to null
            // Until the first battery level after coming back, the session is neither continued nor ended.
            resumePending -> ChargingStatus.UNKNOWN to null
            chargeState == VehicleSource.CHARGE_STATE_CHARGING -> ChargingStatus.CHARGING to ChargingBasis.CHARGE_STATE
            estimator.isRising(now) -> ChargingStatus.CHARGING to ChargingBasis.BATTERY_LEVEL_RISE
            driverConfirmedCharging -> ChargingStatus.CHARGING to ChargingBasis.DRIVER_CONFIRMED
            wasCharging -> ChargingStatus.FINISHED to null
            !signalsReadable -> ChargingStatus.UNKNOWN to null
            else -> ChargingStatus.NOT_CHARGING to null
        }
        if (status == ChargingStatus.CHARGING) {
            if (chargingSinceMs == null) {
                chargingSinceMs = now
                goNoticeSent = false
            }
            wasCharging = true
        }
        if (status != charging) log("charging $charging -> $status ($basis)")
        charging = status
        chargingBasis = basis
    }

    /** Driving away ends the charging session; the next stop starts a new one. */
    private fun endSession() {
        estimator = PowerEstimator()
        trend = PowerTrend()
        goNoticeSent = false
        powerHistory.clear()
        chargingSinceMs = null
        wasCharging = false
        driverConfirmedCharging = false
        manualTargetSoc = null
    }

    private fun requestTrip() {
        if (destination == null || !naviReady) return
        lastTripSoc = soc
        lastTripRequestMs = clock()
        navi.requestDestinationTrip()
    }

    private fun retryDelayMs(): Long = (TRIP_RETRY_MS shl tripFailures.coerceIn(0, 3)).coerceAtMost(TRIP_REFRESH_MS)

    /**
     * The navigation cannot give a usable arrival estimate: it is down; it keeps failing and there is no
     * answer yet or the last one is old; or its route includes a charging stop and the battery level or
     * capacity needed to compute the arrival without that stop cannot be read.
     */
    private fun arrivalUnavailable(now: Long): Boolean {
        if (naviUnavailable) return true
        val trip = destinationTrip
        if (destination != null && tripFailures >= TRIP_FAILURES_BEFORE_TARGET) {
            val at = tripAtMs
            if (trip == null || at == null || now - at > TRIP_STALE_MS) return true
        }
        return destination != null && trip?.chargingStop != null && (levelWh == null || capacityWh == null)
    }

    // Current power and the session maximum always come from the same filtered series: the instantaneous
    // charge rate when the car reports one, the battery level change otherwise.
    private fun rateUsable(): Boolean = instantaneousKw?.let { it > INSTANT_MIN_KW && it <= MAX_PLAUSIBLE_KW } == true && trend.powerKw != null
    private fun currentPowerKw(): Double? = if (rateUsable()) trend.powerKw else estimator.powerKw
    private fun sessionMaxKw(): Double? = if (rateUsable()) trend.sessionMaxKw else estimator.sessionMaxKw
    private fun pmaxTrusted(): Boolean = if (rateUsable()) trend.pmaxTrusted else estimator.pmaxTrusted

    private fun capacityKwh(): Double? = capacityWh?.let { it / 1000.0 }

    /** A: arrival SOC without further charging. With an inserted stop the navigation's SOC is after charging, so use energy. */
    private fun naviArrivalSoc(): Double? {
        val t = destinationTrip ?: return null
        if (destination == null) return null
        if (t.chargingStop == null) return t.estimatedSoc.toDouble()
        val level = levelWh ?: return null
        val capacity = capacityWh ?: return null
        return (level - t.energyWh) / capacity * 100.0
    }

    private fun candidate(): Candidate? {
        val stop = destinationTrip?.chargingStop ?: return null
        val ac = stopArrival?.takeIf { it.first.name == stop.name }?.second
        val output = stopOutput(stop)?.first
        return Candidate(stop.name, ac?.toDouble(), output?.maxPowerKw?.toDouble(), output?.chargerCount)
    }

    /** Max output and charger count of the stop: the active route's value first, the bundled snapshot otherwise. */
    private fun stopOutput(stop: ChargingStop): Pair<ActiveWaypoint, OutputSource>? {
        matchWaypoint(stop.name, activeWaypoints)?.takeIf { it.maxPowerKw > 0 }?.let { return it to OutputSource.ACTIVE_ROUTE }
        val s = stations?.lookup(stop.name, stop.latitude, stop.longitude) ?: return null
        return ActiveWaypoint(s.name, s.maxPowerKw, s.chargerCount) to OutputSource.SNAPSHOT
    }

    private fun socMismatch(): SocMismatch? {
        val s = soc ?: return null
        val level = levelWh ?: return null
        val capacity = capacityWh ?: return null
        val levelSoc = level / capacity * 100.0
        return if (abs(levelSoc - s) > SOC_MISMATCH_PCT) SocMismatch(s, levelSoc) else null
    }

    companion object {
        const val RESERVE_MIN = 5
        const val RESERVE_MAX = 40
        const val TARGET_MIN = 20
        const val TARGET_MAX = 100
        const val DEFAULT_TARGET_SOC = 80
        const val OCCUPANCY_LIMIT_MIN = 60
        const val OCCUPANCY_WARN_MIN = 50
        const val SOC_MISMATCH_PCT = 3.0
        const val TRIP_FAILURES_BEFORE_TARGET = 2
        private const val INSTANT_MIN_KW = 0.5
        private const val MAX_PLAUSIBLE_KW = 400.0
        private const val MAX_HISTORY = 900
        private const val CONNECT_GRACE_MS = 5_000L
        private const val TRIP_RETRY_MS = 3_000L
        private const val TRIP_REFRESH_MS = 30_000L
        /** An arrival estimate older than this is not used once the navigation keeps failing. */
        private const val TRIP_STALE_MS = 90_000L
        private const val REROUTE_GRACE_MS = 10_000L
        /** A fast-charging stop is limited to one hour; a longer gap is another stop. */
        private const val SESSION_MAX_GAP_MS = 45 * 60 * 1000L
        private const val DROVE_AWAY_WH = 500.0
        private const val SHORT_GAP_MS = 60_000L

        /**
         * The planned stop (onTripInfo) and the active-route waypoint (onDrivingInfo) share no id.
         * POI names differ in suffix ("…휴게소(부산방향) 전기차충전소" vs "…휴게소"), so compare the leading part.
         */
        fun matchWaypoint(stopName: String, waypoints: List<ActiveWaypoint>): ActiveWaypoint? {
            fun key(s: String) = s.filterNot { it.isWhitespace() }.take(4)
            val k = key(stopName)
            if (k.length < 2) return null
            return waypoints.firstOrNull { val w = key(it.name); w.length >= 2 && (it.name.filterNot(Char::isWhitespace).contains(k) || stopName.filterNot(Char::isWhitespace).contains(w)) }
        }
    }
}
