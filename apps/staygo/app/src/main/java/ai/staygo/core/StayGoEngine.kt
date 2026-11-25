package ai.staygo.core

import ai.staygo.decision.Candidate
import ai.staygo.decision.ChargingStatus
import ai.staygo.decision.Decision
import ai.staygo.decision.DecisionModel
import ai.staygo.decision.Emphasis
import ai.staygo.decision.Inputs
import ai.staygo.decision.Params
import ai.staygo.signal.PowerEstimator
import kotlin.math.abs

/**
 * Session state of the app: consumes vehicle and navigation events, produces [StayGoUiState].
 * Plain Kotlin, single-threaded; every call must come from the same thread (the main thread in the app).
 * Time comes from [clock] (monotonic milliseconds) so the engine can be replayed in tests.
 */
class StayGoEngine(
    private val navi: NaviSource,
    private val bands: BandStore,
    private val settings: SettingsStore,
    private val clock: () -> Long,
    private val baseParams: Params = Params(),
    private val notify: (UiNotice) -> Unit = {},
    private val log: (String) -> Unit = {},
    /** Called after every event that can change the state. */
    private val onChange: () -> Unit = {},
) : VehicleSource.Listener, NaviSource.Listener {

    /** Bundled station snapshot; fills in a stop's max output when the active route does not provide it. */
    var stations: StationDirectory? = null
        set(value) { field = value; onChange() }

    private var startedAtMs: Long? = null

    // Vehicle
    private var linkFailure: VehicleLinkFailure? = null
    private var soc: Int? = null
    private var levelWh: Double? = null
    private var capacityWh: Double? = null
    private var chargeState: Int? = null
    private var instantaneousKw: Double? = null
    private var restricted = false

    // Charging session
    private var estimator = PowerEstimator()
    private val powerHistory = ArrayList<PowerSample>()
    private var charging = ChargingStatus.UNKNOWN
    private var chargingBasis: ChargingBasis? = null
    private var chargingSinceMs: Long? = null
    private var wasCharging = false
    private var driverConfirmedCharging = false
    private var manualTargetSoc: Int? = null

    // Navigation
    private var naviReady = false
    private var naviUnavailable = false
    private var destination: Destination? = null
    private var destinationTrip: DestinationTrip? = null
    private var stopArrival: Pair<ChargingStop, Int>? = null
    private var activeWaypoints: List<ActiveWaypoint> = emptyList()
    private var lastTripSoc: Int? = null
    private var lastTripRequestMs: Long? = null
    private var tripFailures = 0
    private var routeSentAtMs: Long? = null

    private var lastDecision: Decision? = null

    private val params: Params get() = baseParams.copy(reserveSocPct = settings.reserveSocPct.toDouble())

    fun start() {
        startedAtMs = clock()
    }

    // ---- Driver actions ----

    fun onPermissionDenied() {
        linkFailure = VehicleLinkFailure.PERMISSION_DENIED
        onChange()
    }

    fun onPermissionGranted() {
        if (linkFailure == VehicleLinkFailure.PERMISSION_DENIED) linkFailure = null
        onChange()
    }

    fun stay() {
        if (charging == ChargingStatus.CHARGING) notify(UiNotice.StayAcknowledged)
    }

    fun go() {
        val state = snapshot()
        when (state.go) {
            GoAvailability.LOCKED_NO_DESTINATION -> notify(UiNotice.DestinationRequired)
            GoAvailability.LOCKED_NAVI_UNAVAILABLE -> notify(UiNotice.NaviUnavailable)
            GoAvailability.LOCKED_NOT_CHARGING -> Unit
            GoAvailability.DIRECT -> { log("GO rule=${state.decision.rule} via=null"); navi.go(null) }
            GoAvailability.VIA_STOP -> {
                val stop = destinationTrip?.chargingStop
                log("GO rule=${state.decision.rule} via=${stop?.name}")
                navi.go(stop)
            }
        }
    }

    /** The driver states that the car is charging; used only while no charging signal can be read. */
    fun confirmCharging() {
        if (charging == ChargingStatus.UNKNOWN) driverConfirmedCharging = true
        onChange()
    }

    fun adjustReserveSoc(deltaPct: Int) {
        settings.reserveSocPct = (settings.reserveSocPct + deltaPct).coerceIn(RESERVE_MIN, RESERVE_MAX)
        onChange()
    }

    /** Target SOC that replaces the arrival estimate while the navigation gives none. */
    fun adjustTargetSoc(deltaPct: Int) {
        val current = manualTargetSoc
        manualTargetSoc = if (current == null) DEFAULT_TARGET_SOC else (current + deltaPct).coerceIn(TARGET_MIN, TARGET_MAX)
        onChange()
    }

    // ---- VehicleSource.Listener ----

    override fun onVehicleLinkFailed(failure: VehicleLinkFailure, detail: String) {
        log("vehicle link failed: $failure $detail")
        // A denied permission explains every later failure; keep the most specific reason.
        if (linkFailure != VehicleLinkFailure.PERMISSION_DENIED) linkFailure = failure
        onChange()
    }

    override fun onSoc(soc: Int) {
        this.soc = soc
        // A delivered SOC proves the link works again after a transient failure.
        if (linkFailure == VehicleLinkFailure.UNAVAILABLE) linkFailure = null
        if (soc != lastTripSoc) requestTrip()
        onChange()
    }

    override fun onBatteryLevelWh(wh: Double) {
        val now = clock()
        levelWh = wh
        // A rising level while driving is regeneration, not charging.
        if (!restricted) {
            estimator.addLevel(now, wh)
            advance(now)
            val p = currentPowerKw()
            if (charging == ChargingStatus.CHARGING && p != null) {
                powerHistory += PowerSample(now, p)
                while (powerHistory.size > MAX_HISTORY) powerHistory.removeAt(0)
                soc?.let { bands.record(it, p) }
            }
        }
        onChange()
    }

    override fun onChargeState(state: Int) {
        chargeState = state
        onChange()
    }

    override fun onInstantaneousRateKw(kw: Double) {
        instantaneousKw = kw
        onChange()
    }

    override fun onCapacityWh(wh: Double) {
        if (wh > 0) capacityWh = wh
        onChange()
    }

    override fun onDrivingRestricted(restricted: Boolean) {
        if (restricted == this.restricted) return
        log("driving restricted=$restricted")
        this.restricted = restricted
        if (restricted) endSession()
        onChange()
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
        val changed = destination?.name != this.destination?.name
        this.destination = destination
        if (changed) {
            destinationTrip = null
            stopArrival = null
            lastTripSoc = null
            tripFailures = 0
        }
        if (changed || destinationTrip == null) requestTrip()
        onChange()
    }

    override fun onDestinationTrip(trip: DestinationTrip) {
        log("trip estSoc=${trip.estimatedSoc} energyWh=${trip.energyWh.toLong()} stop=${trip.chargingStop?.name}")
        tripFailures = 0
        destinationTrip = trip
        if (trip.chargingStop?.name != stopArrival?.first?.name) stopArrival = null
        onChange()
    }

    override fun onChargingStopTrip(stop: ChargingStop, estimatedSoc: Int) {
        log("stop trip ${stop.name} estSoc=$estimatedSoc")
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
        log("route via=${viaStop?.name} sent=$sent $detail")
        if (sent) routeSentAtMs = clock()
        notify(if (sent) UiNotice.RouteSent(viaStop?.name) else UiNotice.RouteFailed(detail))
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
        val capacityKwh = capacityKwh()
        val trip = destinationTrip
        val candidate = candidate()
        val arrivalEstimate = naviArrivalSoc()
        val target = manualTargetSoc
        val useTarget = arrivalEstimate == null && arrivalUnavailable() && target != null
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
                pmaxKw = estimator.sessionMaxKw,
                pmaxTrusted = estimator.pmaxTrusted,
                hasDestination = destination != null || useTarget,
                arrivalSoc = arrivalForModel,
                candidate = candidate,
                bandPowerKw = candidate?.arrivalSoc?.let { bands.max(it.toInt()) },
                capacityKwh = capacityKwh ?: 0.0,
                params = p,
            )
        ).let { d -> if (capacityKwh == null) d.copy(extraWaitMinutes = null, comparison = null, rule = if (d.rule == 5) 2 else d.rule) else d }
        if (decision.rule != lastDecision?.rule || decision.emphasis != lastDecision?.emphasis || decision.state != lastDecision?.state) {
            log("DECISION rule=${decision.rule} emphasis=${decision.emphasis} state=${decision.state} soc=$soc A=$arrivalForModel P=$power Pmax=${estimator.sessionMaxKw} cand=${candidate?.name}/${candidate?.arrivalSoc}/${candidate?.maxPowerKw}")
        }
        lastDecision = decision

        val screen = screen(now, useTarget)
        val showVehicle = linkFailure == null
        val arrival = when {
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
            vehicleLinkFailure = linkFailure,
            charging = charging,
            chargingBasis = chargingBasis,
            socPercent = soc.takeIf { showVehicle },
            socMismatch = socMismatch().takeIf { showVehicle },
            power = power?.let {
                PowerState(
                    kw = it,
                    estimated = instantaneousKw?.let { r -> r <= INSTANT_MIN_KW } ?: true,
                    sessionMaxKw = estimator.sessionMaxKw,
                    sessionMaxTrusted = estimator.pmaxTrusted,
                    secondsPerPercent = capacityKwh?.let { c -> DecisionModel.secondsPerPercent(c, it) },
                )
            },
            powerHistory = powerHistory.toList(),
            destination = destination?.let { DestinationState(it.name, it.remainDistanceM) },
            arrival = arrival,
            route = when {
                destination == null || trip == null -> RouteState.None
                trip.chargingStop == null -> RouteState.Direct
                else -> RouteState.ViaStop(
                    trip.chargingStop.name, candidate?.arrivalSoc, candidate?.maxPowerKw, candidate?.chargerCount, decision.comparison,
                    outputSource = stopOutput(trip.chargingStop)?.second,
                    snapshotAsOf = stations?.asOf,
                )
            },
            decision = decision,
            go = goAvailability(decision),
            reserveSocPct = settings.reserveSocPct,
            manualTargetSoc = target,
            occupancy = chargingSinceMs?.takeIf { charging == ChargingStatus.CHARGING || charging == ChargingStatus.FINISHED }?.let {
                val min = ((now - it) / 60_000L).toInt()
                Occupancy(min, OCCUPANCY_LIMIT_MIN, min >= OCCUPANCY_WARN_MIN)
            },
            lateRatio = p.lateRatio,
            stopOverheadMin = p.stopOverheadMin,
        )
    }

    private fun screen(now: Long, useTarget: Boolean): Screen = when {
        restricted -> Screen.DRIVING
        linkFailure != null -> Screen.VEHICLE_LINK_UNAVAILABLE
        charging == ChargingStatus.UNKNOWN ->
            if (now - (startedAtMs ?: now) < CONNECT_GRACE_MS) Screen.CONNECTING else Screen.CHARGING_UNCONFIRMED
        charging == ChargingStatus.NOT_CHARGING -> Screen.NOT_CHARGING
        charging == ChargingStatus.FINISHED -> Screen.CHARGE_FINISHED
        useTarget -> Screen.DECISION
        arrivalUnavailable() -> Screen.TARGET_SOC_NEEDED
        destination == null -> Screen.NO_DESTINATION
        else -> Screen.DECISION
    }

    private fun goAvailability(d: Decision): GoAvailability = when {
        charging != ChargingStatus.CHARGING && charging != ChargingStatus.FINISHED -> GoAvailability.LOCKED_NOT_CHARGING
        naviUnavailable || !naviReady -> GoAvailability.LOCKED_NAVI_UNAVAILABLE
        destination == null -> GoAvailability.LOCKED_NO_DESTINATION
        d.rule == 5 && d.emphasis != Emphasis.NONE && destinationTrip?.chargingStop != null -> GoAvailability.VIA_STOP
        else -> GoAvailability.DIRECT
    }

    /** Recomputes the charging status and keeps the session markers in step with it. */
    private fun advance(now: Long) {
        val signalsReadable = chargeState != null || levelWh != null
        if (signalsReadable) driverConfirmedCharging = false
        val rising = !restricted && estimator.isRising(now)
        val (status, basis) = when {
            restricted -> ChargingStatus.NOT_CHARGING to null
            chargeState == VehicleSource.CHARGE_STATE_CHARGING -> ChargingStatus.CHARGING to ChargingBasis.CHARGE_STATE
            rising -> ChargingStatus.CHARGING to ChargingBasis.BATTERY_LEVEL_RISE
            driverConfirmedCharging -> ChargingStatus.CHARGING to ChargingBasis.DRIVER_CONFIRMED
            wasCharging -> ChargingStatus.FINISHED to null
            !signalsReadable -> ChargingStatus.UNKNOWN to null
            else -> ChargingStatus.NOT_CHARGING to null
        }
        if (status == ChargingStatus.CHARGING) {
            if (chargingSinceMs == null) chargingSinceMs = now
            wasCharging = true
        }
        if (status != charging) log("charging $charging -> $status ($basis)")
        charging = status
        chargingBasis = basis
    }

    /** Driving away ends the charging session; the next stop starts a new one. */
    private fun endSession() {
        estimator = PowerEstimator()
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

    /** The navigation cannot give an arrival estimate: it is down, or it keeps failing the trip request. */
    private fun arrivalUnavailable(): Boolean =
        naviUnavailable || (destination != null && destinationTrip == null && tripFailures >= TRIP_FAILURES_BEFORE_TARGET)

    private fun currentPowerKw(): Double? = instantaneousKw?.takeIf { it > INSTANT_MIN_KW } ?: estimator.powerKw

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
        private const val MAX_HISTORY = 900
        private const val CONNECT_GRACE_MS = 5_000L
        private const val TRIP_RETRY_MS = 3_000L
        private const val TRIP_REFRESH_MS = 30_000L
        private const val REROUTE_GRACE_MS = 10_000L

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
