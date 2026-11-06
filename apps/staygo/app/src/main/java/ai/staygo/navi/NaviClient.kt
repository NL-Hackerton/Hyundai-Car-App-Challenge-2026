package ai.staygo.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.RoutePointPoiType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * NaviHelper wrapper. Trip requests are serialized because onTripInfo does not say which request it answers:
 * one in flight at a time, the destination trip first and then the trip to the charging stop the navigation inserted.
 */
class NaviClient(context: Context, private val listener: Listener) {

    data class Destination(val name: String, val latitude: Double, val longitude: Double, val remainDistanceM: Int)
    data class ChargingStop(val name: String, val latitude: Double, val longitude: Double, val ratioOnRoute: Double)
    data class DestinationTrip(val estimatedSoc: Int, val energyWh: Double, val distanceM: Int, val chargingStop: ChargingStop?)
    data class ActiveWaypoint(val name: String, val maxPowerKw: Int, val chargerCount: Int)

    interface Listener {
        fun onNaviReady()
        fun onDestination(destination: Destination?)
        fun onDestinationTrip(trip: DestinationTrip)
        fun onChargingStopTrip(stop: ChargingStop, estimatedSoc: Int)
        fun onActiveWaypoints(waypoints: List<ActiveWaypoint>)
        fun onRouteSent(viaStop: ChargingStop?)
        fun onNaviError(message: String)
    }

    private enum class Kind { DESTINATION, STOP }
    private data class Pending(val kind: Kind, val stop: ChargingStop?, val sentAtMs: Long)

    private val main = Handler(Looper.getMainLooper())
    private val helper = NaviHelper(context)
    private var ready = false
    private var destination: Destination? = null
    private var inFlight: Pending? = null
    private var destinationTripQueued = false
    private var stopTripQueued: ChargingStop? = null

    private val events = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = post {
            val d = destinationInfo.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }
                ?.let { Destination(it.name, it.latitude, it.longitude, it.remainDistance) }
            destination = d
            listener.onDestination(d)
        }

        override fun onDestinationChanged() = post { helper.getDestinationInfo() }
        override fun onRouteStarted(routeStartInfo: ai.pleos.playground.navi.data.RouteStartInfo) = post { helper.getDestinationInfo() }
        override fun onRouteCancelled() = post { destination = null; listener.onDestination(null) }

        override fun onTripInfo(tripInfo: TripInfo) = post {
            val p = inFlight ?: return@post
            inFlight = null
            when (p.kind) {
                Kind.DESTINATION -> {
                    val stop = tripInfo.summary.routePoints.firstOrNull { it.poiType == RoutePointPoiType.EV_CHARGING }
                        ?.let { ChargingStop(it.poiName, it.latitude, it.longitude, it.ratioOnRoute) }
                    listener.onDestinationTrip(DestinationTrip(tripInfo.estimatedSocAtDest, tripInfo.energyConsumption, tripInfo.distance, stop))
                    if (stop != null) stopTripQueued = stop
                }
                Kind.STOP -> listener.onChargingStopTrip(p.stop!!, tripInfo.estimatedSocAtDest)
            }
            pump()
        }

        override fun onDrivingInfo(drivingInfo: DrivingInfo) = post {
            listener.onActiveWaypoints(
                drivingInfo.waypoints.filter { it.isChargingStation && it.distance > 0 }
                    .map { ActiveWaypoint(it.name, it.maxChargingPower, it.chargerCount) }
            )
        }

        override fun onError(errorCode: NaviErrorCode) = post {
            inFlight = null
            listener.onNaviError(errorCode.toString())
            pump()
        }
    }

    fun start() {
        helper.initialize({
            post {
                ready = true
                helper.addListener(events)
                helper.getDestinationInfo()
                listener.onNaviReady()
            }
        }, { e -> post { listener.onNaviError("initialize: ${e.message}") } })
    }

    fun release() {
        try { helper.removeListener(events) } catch (_: Throwable) {}
        try { helper.release() } catch (_: Throwable) {}
    }

    fun refreshDestination() { if (ready) helper.getDestinationInfo() }

    /** Ask the navigation for the arrival SOC at the destination with the current battery. */
    fun requestDestinationTrip() {
        if (!ready || destination == null) return
        destinationTripQueued = true
        pump()
    }

    /** Send the destination (optionally via the charging stop) to the navigation; it opens its own route screen. */
    fun go(viaStop: ChargingStop?) {
        val d = destination ?: return listener.onNaviError("목적지 없음")
        val route = RouteInfo(d.latitude, d.longitude, null, null, d.name, "", "", "", RouteOption.RECOMMENDED, "", false)
        try {
            if (viaStop == null) {
                helper.requestRoute(route)
            } else {
                val wp = RequestWaypointInfo(viaStop.latitude, viaStop.longitude, viaStop.name, WaypointIndex.FIRST, "", "", "", "")
                helper.requestRouteWithWaypoints(RouteWithWaypointsRequest(route, listOf(wp)))
            }
            listener.onRouteSent(viaStop)
        } catch (t: Throwable) {
            listener.onNaviError("route: ${t.message}")
        }
    }

    private fun pump() {
        val now = android.os.SystemClock.elapsedRealtime()
        inFlight?.let { if (now - it.sentAtMs > REQUEST_TIMEOUT_MS) inFlight = null else return }
        val d = destination ?: return
        try {
            if (destinationTripQueued) {
                destinationTripQueued = false
                inFlight = Pending(Kind.DESTINATION, null, now)
                helper.requestTrip(TripRequest(d.latitude, d.longitude, "", d.name, ""))
            } else {
                val stop = stopTripQueued ?: return
                stopTripQueued = null
                inFlight = Pending(Kind.STOP, stop, now)
                helper.requestTrip(TripRequest(stop.latitude, stop.longitude, "", stop.name, ""))
            }
        } catch (t: Throwable) {
            inFlight = null
            listener.onNaviError("trip: ${t.message}")
        }
        main.postDelayed({ pump() }, REQUEST_TIMEOUT_MS + 100L)
    }

    private inline fun post(crossinline block: () -> Unit) { main.post { block() } }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 5_000L
    }
}
