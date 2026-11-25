package ai.staygo.core

/** Why the vehicle link cannot be used. The app never shows vehicle values while one of these is set. */
enum class VehicleLinkFailure { PERMISSION_DENIED, SOC_UNSUPPORTED, UNAVAILABLE }

data class Destination(val name: String, val latitude: Double, val longitude: Double, val remainDistanceM: Int)

/** Charging stop the navigation inserted on the planned route. */
data class ChargingStop(val name: String, val latitude: Double, val longitude: Double, val ratioOnRoute: Double)

data class DestinationTrip(val estimatedSoc: Int, val energyWh: Double, val distanceM: Int, val chargingStop: ChargingStop?)

/** Charging waypoint of the route the navigation is actively guiding. */
data class ActiveWaypoint(val name: String, val maxPowerKw: Int, val chargerCount: Int)

/** Vehicle inputs. Every listener call is delivered on the main thread. */
interface VehicleSource {
    interface Listener {
        fun onVehicleLinkFailed(failure: VehicleLinkFailure, detail: String)
        fun onSoc(soc: Int)
        fun onBatteryLevelWh(wh: Double)
        fun onChargeState(state: Int)
        fun onInstantaneousRateKw(kw: Double)
        fun onCapacityWh(wh: Double)
        fun onDrivingRestricted(restricted: Boolean)
    }

    fun start(listener: Listener)
    fun stop()

    companion object {
        /** android.car.hardware.property.EvChargeState.STATE_CHARGING */
        const val CHARGE_STATE_CHARGING = 1
    }
}

/** Navigation inputs and route requests. Every listener call is delivered on the main thread. */
interface NaviSource {
    interface Listener {
        fun onNaviReady()
        fun onNaviUnavailable(detail: String)
        fun onDestination(destination: Destination?)
        fun onDestinationTrip(trip: DestinationTrip)
        fun onChargingStopTrip(stop: ChargingStop, estimatedSoc: Int)
        /** A trip request got an error or no answer within the timeout. */
        fun onTripFailed(detail: String)
        fun onActiveWaypoints(waypoints: List<ActiveWaypoint>)
        fun onRouteResult(viaStop: ChargingStop?, sent: Boolean, detail: String?)
    }

    fun start(listener: Listener)
    fun stop()

    /** Arrival SOC at the destination with the current battery; the stop trip follows when a stop is inserted. */
    fun requestDestinationTrip()

    /** Hand the destination (optionally via the charging stop) to the navigation. */
    fun go(viaStop: ChargingStop?)
}

/** Max charging power this car received per 5 %p SOC band. No location, time, or station. */
interface BandStore {
    fun record(soc: Int, kw: Double)
    fun max(soc: Int): Double?
}

interface SettingsStore {
    var reserveSocPct: Int
}

class InMemoryBandStore : BandStore {
    private val bands = HashMap<Int, Double>()
    override fun record(soc: Int, kw: Double) {
        val k = bandOf(soc)
        if (kw > (bands[k] ?: 0.0)) bands[k] = kw
    }
    override fun max(soc: Int): Double? = bands[bandOf(soc)]

    companion object {
        fun bandOf(soc: Int): Int = Math.floorDiv(soc, 5) * 5
    }
}

class InMemorySettingsStore(override var reserveSocPct: Int = 15) : SettingsStore
