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

/**
 * NaviHelper wrapper. Trip requests are serialized because onTripInfo does not say which request it answers:
 * one in flight at a time, the destination trip first and then the trip to the charging stop the navigation inserted.
 * A request without an answer within [REQUEST_TIMEOUT_MS] is reported as failed and the queue moves on.
 */
class NaviClient(private val context: Context) : NaviSource {

    private enum class Kind { DESTINATION, STOP }
    private data class Pending(val kind: Kind, val stop: ChargingStop?, val sentAtMs: Long)

    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var listener: NaviSource.Listener? = null
    private var ready = false
    private var destination: Destination? = null
    private var inFlight: Pending? = null
    private var destinationTripQueued = false
    private var stopTripQueued: ChargingStop? = null
    private var destinationDistanceM = 0
    private val timeoutCheck = Runnable { onTimeoutCheck() }

    private val events = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = post {
            val d = destinationInfo.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }
                ?.let {
                    Destination(
                        it.name, it.latitude, it.longitude, it.remainDistance,
                        it.poiId.orEmpty(), it.poiSubId.orEmpty(), it.address.orEmpty(), it.poiProvider.orEmpty(),
                        it.displayLatitude, it.displayLongitude,
                    )
                }
            destination = d
            listener?.onDestination(d)
        }

        override fun onDestinationChanged() = post { helper?.getDestinationInfo() }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = post { helper?.getDestinationInfo() }
        override fun onRouteCancelled() = post { destination = null; listener?.onDestination(null) }

        override fun onTripInfo(tripInfo: TripInfo) = post {
            val p = inFlight ?: return@post
            inFlight = null
            main.removeCallbacks(timeoutCheck)
            when (p.kind) {
                Kind.DESTINATION -> {
                    val stop = tripInfo.summary.routePoints.firstOrNull { it.poiType == RoutePointPoiType.EV_CHARGING }
                        ?.let { ChargingStop(it.poiName, it.latitude, it.longitude, it.ratioOnRoute) }
                    destinationDistanceM = tripInfo.distance
                    listener?.onDestinationTrip(DestinationTrip(tripInfo.estimatedSocAtDest, tripInfo.energyConsumption, tripInfo.distance, stop))
                    stopTripQueued = stop
                }
                Kind.STOP -> p.stop?.let {
                    // A late answer to an earlier, timed-out destination request would be read as the stop's.
                    if (plausibleStopDistance(tripInfo.distance, it.ratioOnRoute, destinationDistanceM)) {
                        listener?.onChargingStopTrip(it, tripInfo.estimatedSocAtDest)
                    } else {
                        listener?.onTripFailed("stop answer does not match the stop")
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
            // Route-search errors belong to go(); only a pending trip request is failed here.
            if (inFlight != null) {
                inFlight = null
                main.removeCallbacks(timeoutCheck)
                listener?.onTripFailed(errorCode.toString())
                pump()
            }
        }
    }

    override fun start(listener: NaviSource.Listener) {
        this.listener = listener
        try {
            val h = NaviHelper(context)
            helper = h
            h.initialize({
                post {
                    ready = true
                    h.addListener(events)
                    h.getDestinationInfo()
                    this.listener?.onNaviReady()
                }
            }, { e -> post { this.listener?.onNaviUnavailable("initialize: ${e.message}") } })
        } catch (t: Throwable) {
            listener.onNaviUnavailable("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun stop() {
        main.removeCallbacksAndMessages(null)
        listener = null
        ready = false
        inFlight = null
        try { helper?.removeListener(events) } catch (_: Throwable) {}
        try { helper?.release() } catch (_: Throwable) {}
        helper = null
    }

    override fun requestDestinationTrip() {
        if (!ready || destination == null) return
        destinationTripQueued = true
        pump()
    }

    override fun go(viaStop: ChargingStop?) {
        val h = helper
        val d = destination
        if (!ready || h == null || d == null) {
            listener?.onRouteResult(viaStop, false, "no destination")
            return
        }
        val route = routeFor(d)
        try {
            if (viaStop == null) {
                h.requestRoute(route)
            } else {
                val wp = RequestWaypointInfo(viaStop.latitude, viaStop.longitude, viaStop.name, WaypointIndex.FIRST, "", "", "", "")
                h.requestRouteWithWaypoints(RouteWithWaypointsRequest(route, listOf(wp)))
            }
            listener?.onRouteResult(viaStop, true, null)
        } catch (t: Throwable) {
            listener?.onRouteResult(viaStop, false, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun pump() {
        if (inFlight != null) return
        val h = helper ?: return
        val d = destination ?: return
        val now = SystemClock.elapsedRealtime()
        val next = when {
            destinationTripQueued -> {
                destinationTripQueued = false
                Pending(Kind.DESTINATION, null, now)
            }
            else -> {
                val stop = stopTripQueued ?: return
                stopTripQueued = null
                Pending(Kind.STOP, stop, now)
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

    private fun onTimeoutCheck() {
        val p = inFlight ?: return
        inFlight = null
        listener?.onTripFailed("timeout ${p.kind}")
        pump()
    }

    private inline fun post(crossinline block: () -> Unit) { main.post { block() } }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 5_000L

        /**
         * The stop lies at [ratioOnRoute] of the destination route, so the trip to it is about that share of
         * the destination distance. An answer as long as the whole route belongs to another request.
         */
        fun plausibleStopDistance(distanceM: Int, ratioOnRoute: Double, destinationDistanceM: Int): Boolean {
            // The ratio has been observed as a share of the route; a percentage is normalized just in case.
            val ratio = if (ratioOnRoute > 1.0) ratioOnRoute / 100.0 else ratioOnRoute
            if (destinationDistanceM <= 0 || ratio <= 0.0 || ratio >= 1.0) return true
            val expected = ratio * destinationDistanceM
            return kotlin.math.abs(distanceM - expected) <= 0.15 * destinationDistanceM && distanceM < 0.97 * destinationDistanceM
        }

        /** The navigation route request for [d], carrying its POI identity (see [Destination]). */
        fun routeFor(d: Destination) = RouteInfo(
            d.latitude, d.longitude, d.displayLatitude, d.displayLongitude,
            d.name, d.poiId, d.address, d.poiSubId, RouteOption.RECOMMENDED, d.poiProvider, false,
        )
    }
}
