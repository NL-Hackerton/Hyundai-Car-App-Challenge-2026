package ai.staygo.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.RoutePointPoiType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.staygo.core.ActiveWaypoint
import ai.staygo.core.ChargingStop
import ai.staygo.core.Destination
import ai.staygo.core.DestinationTrip
import ai.staygo.core.NaviSource
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs

/**
 * NaviHelper wrapper.
 *
 * onTripInfo does not say which request it answers, so trip requests are serialized: one in flight at a time,
 * the destination trip first and then the trip to the charging stop the navigation inserted. Three guards keep
 * an answer from being credited to the wrong request:
 * - a request without an answer within [REQUEST_TIMEOUT_MS] is reported as failed, and the next request waits
 *   another [DRAIN_MS] so that a late answer is swallowed instead of being read as the next one's;
 * - a request whose destination changed while it was in flight is abandoned the same way;
 * - an answer is accepted only when its distance fits the request (the destination's remaining distance, or
 *   the stop's share of the route).
 *
 * Every start() opens a new generation; callbacks of a stopped generation are ignored.
 */
class NaviClient(private val context: Context) : NaviSource {

    private enum class Kind { DESTINATION, STOP }
    private data class Pending(val kind: Kind, val destination: Destination, val stop: ChargingStop?)

    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var helper: NaviHelper? = null
    private var listener: NaviSource.Listener? = null
    private var ready = false
    private var destination: Destination? = null
    private var inFlight: Pending? = null
    /** A request that was given up; its answer may still come and must not be read as another's. */
    private var draining = false
    private var destinationTripQueued = false
    private var stopTripQueued: ChargingStop? = null
    private var destinationDistanceM = 0
    private var lastGoAtMs = 0L
    private var lastGoVia: ChargingStop? = null
    private val timeoutCheck = Runnable { onTimeout() }
    private val drainDone = Runnable { draining = false; pump() }

    private inner class Events(private val gen: Int) : NaviHelperEventListener {
        private inline fun post(crossinline block: () -> Unit) {
            main.post { if (gen == generation) block() }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) = post {
            val d = destinationInfo.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }
                ?.let {
                    Destination(
                        it.name, it.latitude, it.longitude, it.remainDistance,
                        it.poiId.orEmpty(), it.poiSubId.orEmpty(), it.address.orEmpty(), it.poiProvider.orEmpty(),
                        it.displayLatitude, it.displayLongitude,
                    )
                }
            if (d == null || !d.samePlaceAs(destination)) abandonInFlight()
            destination = d
            listener?.onDestination(d)
        }

        override fun onDestinationChanged() = post { helper?.getDestinationInfo() }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = post { helper?.getDestinationInfo() }
        override fun onRouteCancelled() = post {
            abandonInFlight()
            destination = null
            listener?.onDestination(null)
        }

        override fun onTripInfo(tripInfo: TripInfo) = post {
            if (draining) {
                // The late answer of an abandoned request.
                draining = false
                main.removeCallbacks(drainDone)
                pump()
                return@post
            }
            val p = inFlight ?: return@post
            inFlight = null
            main.removeCallbacks(timeoutCheck)
            when (p.kind) {
                Kind.DESTINATION -> {
                    if (!plausibleDestinationDistance(tripInfo.distance, p.destination.remainDistanceM)) {
                        listener?.onTripFailed("answer does not match the destination")
                    } else {
                        val stop = tripInfo.summary.routePoints.firstOrNull { it.poiType == RoutePointPoiType.EV_CHARGING }
                            ?.let { ChargingStop(it.poiName, it.latitude, it.longitude, it.ratioOnRoute) }
                        destinationDistanceM = tripInfo.distance
                        listener?.onDestinationTrip(DestinationTrip(tripInfo.estimatedSocAtDest, tripInfo.energyConsumption, tripInfo.distance, stop))
                        stopTripQueued = stop
                    }
                }
                Kind.STOP -> p.stop?.let {
                    if (plausibleStopDistance(tripInfo.distance, it.ratioOnRoute, destinationDistanceM)) {
                        listener?.onChargingStopTrip(it, tripInfo.estimatedSocAtDest)
                    } else {
                        listener?.onTripFailed("answer does not match the stop")
                    }
                }
            }
            pump()
        }

        override fun onDrivingInfo(drivingInfo: DrivingInfo) = post {
            listener?.onActiveWaypoints(
                drivingInfo.waypoints.filter { it.isChargingStation && it.distance > 0 }
                    .map { ActiveWaypoint(it.name, it.maxChargingPower, it.chargerCount) }
            )
        }

        override fun onError(errorCode: NaviErrorCode) = post {
            val routeError = errorCode != NaviErrorCode.CAUSE_SEARCH_TRIP && SystemClock.elapsedRealtime() - lastGoAtMs < ROUTE_ERROR_WINDOW_MS
            if (routeError) {
                // The route the app handed over was refused; the trip request in flight is not affected.
                lastGoAtMs = 0L
                listener?.onRouteResult(lastGoVia, false, errorCode.toString())
            } else if (inFlight != null) {
                inFlight = null
                main.removeCallbacks(timeoutCheck)
                listener?.onTripFailed(errorCode.toString())
                pump()
            }
        }
    }

    private var events: Events? = null

    override fun start(listener: NaviSource.Listener) {
        val gen = ++generation
        this.listener = listener
        try {
            val h = NaviHelper(context)
            helper = h
            h.initialize({
                main.post {
                    if (gen != generation) return@post
                    ready = true
                    val e = Events(gen)
                    events = e
                    h.addListener(e)
                    h.getDestinationInfo()
                    this.listener?.onNaviReady()
                }
            }, { e -> main.post { if (gen == generation) this.listener?.onNaviUnavailable("initialize: ${e.message}") } })
        } catch (t: Throwable) {
            listener.onNaviUnavailable("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun stop() {
        generation++
        main.removeCallbacksAndMessages(null)
        listener = null
        ready = false
        destination = null
        inFlight = null
        draining = false
        destinationTripQueued = false
        stopTripQueued = null
        destinationDistanceM = 0
        try { events?.let { helper?.removeListener(it) } } catch (_: Throwable) {}
        try { helper?.release() } catch (_: Throwable) {}
        events = null
        helper = null
    }

    override fun requestDestinationTrip() {
        if (!ready || destination == null) return
        // Answers are checked against the remaining distance, which shrinks while driving: refresh it now and
        // then so a later stop on the same route is not judged against the distance at departure.
        val now = SystemClock.elapsedRealtime()
        if (now - destinationAskedAtMs > DESTINATION_REFRESH_MS) {
            destinationAskedAtMs = now
            try { helper?.getDestinationInfo() } catch (_: Throwable) {}
        }
        destinationTripQueued = true
        pump()
    }

    private var destinationAskedAtMs = 0L

    override fun go(viaStop: ChargingStop?) {
        val h = helper
        val d = destination
        if (!ready || h == null || d == null) {
            listener?.onRouteResult(viaStop, false, "no destination")
            return
        }
        val route = routeFor(d)
        try {
            lastGoAtMs = SystemClock.elapsedRealtime()
            lastGoVia = viaStop
            if (viaStop == null) {
                h.requestRoute(route)
            } else {
                val wp = RequestWaypointInfo(viaStop.latitude, viaStop.longitude, viaStop.name, WaypointIndex.FIRST, "", "", "", "")
                h.requestRouteWithWaypoints(RouteWithWaypointsRequest(route, listOf(wp)))
            }
            listener?.onRouteResult(viaStop, true, null)
        } catch (t: Throwable) {
            lastGoAtMs = 0L
            listener?.onRouteResult(viaStop, false, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun pump() {
        if (inFlight != null || draining) return
        val h = helper ?: return
        val d = destination ?: return
        val next = when {
            destinationTripQueued -> {
                destinationTripQueued = false
                Pending(Kind.DESTINATION, d, null)
            }
            else -> {
                val stop = stopTripQueued ?: return
                stopTripQueued = null
                Pending(Kind.STOP, d, stop)
            }
        }
        try {
            inFlight = next
            val (lat, lng, name) = next.stop?.let { Triple(it.latitude, it.longitude, it.name) } ?: Triple(d.latitude, d.longitude, d.name)
            h.requestTrip(TripRequest(lat, lng, "", name, ""))
            main.removeCallbacks(timeoutCheck)
            main.postDelayed(timeoutCheck, REQUEST_TIMEOUT_MS)
        } catch (t: Throwable) {
            inFlight = null
            listener?.onTripFailed("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun onTimeout() {
        val p = inFlight ?: return
        inFlight = null
        drain()
        listener?.onTripFailed("timeout ${p.kind}")
    }

    /** Gives up the request in flight (its destination is gone); its answer, if any, is swallowed. */
    private fun abandonInFlight() {
        destinationTripQueued = false
        stopTripQueued = null
        if (inFlight == null) return
        inFlight = null
        main.removeCallbacks(timeoutCheck)
        drain()
    }

    private fun drain() {
        draining = true
        main.removeCallbacks(drainDone)
        main.postDelayed(drainDone, DRAIN_MS)
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 5_000L
        private const val DRAIN_MS = 5_000L
        private const val ROUTE_ERROR_WINDOW_MS = 10_000L
        private const val DESTINATION_REFRESH_MS = 60_000L

        /** The trip to the destination is about as long as the navigation says the destination is away. */
        fun plausibleDestinationDistance(distanceM: Int, remainDistanceM: Int): Boolean =
            remainDistanceM <= 0 || abs(distanceM - remainDistanceM) <= 0.15 * remainDistanceM

        /**
         * The stop lies at [ratioOnRoute] of the destination route, so the trip to it is about that share of
         * the destination distance. An answer as long as the whole route belongs to another request.
         */
        fun plausibleStopDistance(distanceM: Int, ratioOnRoute: Double, destinationDistanceM: Int): Boolean {
            // The ratio has been observed as a share of the route; a percentage is normalized just in case.
            val ratio = if (ratioOnRoute > 1.0) ratioOnRoute / 100.0 else ratioOnRoute
            if (destinationDistanceM <= 0 || ratio <= 0.0 || ratio >= 1.0) return true
            val expected = ratio * destinationDistanceM
            return abs(distanceM - expected) <= 0.15 * destinationDistanceM && distanceM < 0.97 * destinationDistanceM
        }

        /** The navigation route request for [d], carrying its POI identity (see [Destination]). */
        fun routeFor(d: Destination) = RouteInfo(
            d.latitude, d.longitude, d.displayLatitude, d.displayLongitude,
            d.name, d.poiId, d.address, d.poiSubId, RouteOption.RECOMMENDED, d.poiProvider, false,
        )
    }
}
