package ai.staygo.sim

import ai.staygo.core.ActiveWaypoint
import ai.staygo.core.BandStore
import ai.staygo.core.ChargingStop
import ai.staygo.core.Destination
import ai.staygo.core.DestinationTrip
import ai.staygo.core.NaviSource
import ai.staygo.core.RestrictionSource
import ai.staygo.core.VehicleLinkFailure
import ai.staygo.core.VehicleSource
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Debug-only scenarios. Each one reproduces a row of the proposal's vehicle-state table with the
 * signal behaviour measured on the Pleos Connect emulator (EV_CHARGE_STATE stuck at 2, charge rate 0,
 * battery level at 1 Hz, a charging stop inserted when the direct arrival SOC is below 10 %).
 */
enum class Scenario(val id: String, val config: Config) {
    /** Demo curve: 230 kW until 78 %, 95 kW after 82 %. Stay (rule 2) → both (rule 3) → Go (rule 4). */
    CHARGE("charge", Config()),
    /** Same curve with EV_CHARGE_STATE and the instantaneous charge rate delivered, as on a vehicle. */
    REAL_SIGNALS("real", Config(realSignals = true)),
    /** 50 kW charger, 30 %p short, 200 kW stop ahead: leaving now is about 16 min faster (rule 5). */
    VIA_STOP(
        "via",
        Config(
            startKwh = 45.0, consumptionKwh = 56.6, curve = { 50.0 }, stopRatio = 0.55,
            waypoint = ActiveWaypoint("청도새마을휴게소", 200, 6), seedBand = 15 to 240.0,
        ),
    ),
    /** Opened after the taper started: power only falls, so the SOC rule (rule 6) decides. */
    LATE_OPEN("late", Config(startKwh = 62.7, plugAtS = 0, curve = { soc -> max(60.0, 120.0 - (soc - 81.0) * 40.0) })),
    NO_DESTINATION("nodest", Config(consumptionKwh = null)),
    NAVI_DOWN("navidown", Config(naviAvailable = false)),
    TRIP_FAILS("tripfail", Config(tripsAnswered = false)),
    SOC_UNSUPPORTED("unsupported", Config(socSupported = false)),
    /** Neither the charge state nor the battery level can be read: the driver confirms charging. */
    NO_CHARGE_SIGNALS("nosignal", Config(levelReadable = false)),
    /** The charger stops at 80 %. */
    CHARGER_LIMIT("finish", Config(startKwh = 58.0, stopAtSoc = 80.0)),
    NOT_CHARGING("idle", Config(plugAtS = null)),
    /** Pleos SOC reads 5 %p above the battery-level SOC. */
    SOC_MISMATCH("mismatch", Config(socOffset = 5)),
    /** The charger stops early while the destination is still out of reach. */
    STOPPED_SHORT("stopshort", Config(startKwh = 40.0, stopAtSoc = 61.0)),
    /** The data notice has not been answered yet. */
    CONSENT("consent", Config()),
    /** The platform never reports the driving restriction: the screen stays locked. */
    RESTRICTION_UNKNOWN("nolock", Config(restrictionReported = false)),
    PERMISSION_DENIED("denied", Config());

    fun seedBands(store: BandStore) {
        config.seedBand?.let { (soc, kw) -> store.record(soc, kw) }
    }

    companion object {
        fun of(id: String): Scenario? = entries.firstOrNull { it.id == id }
    }
}

data class Config(
    val capacityKwh: Double = 77.4,
    val startKwh: Double = 57.3,
    /** Energy the route to the destination consumes; null for no destination. */
    val consumptionKwh: Double? = 48.8,
    /** Virtual second at which charging starts; null for never. */
    val plugAtS: Int? = 3,
    /** Charging power (kW) by battery-level SOC (%). */
    val curve: (Double) -> Double = { soc ->
        when {
            soc < 78.0 -> 230.0
            soc < 80.0 -> 230.0 - (soc - 78.0) / 2.0 * 80.0
            soc < 82.0 -> 150.0 - (soc - 80.0) / 2.0 * 40.0
            else -> 95.0
        }
    },
    val stopAtSoc: Double? = null,
    val realSignals: Boolean = false,
    val levelReadable: Boolean = true,
    val socSupported: Boolean = true,
    val naviAvailable: Boolean = true,
    val tripsAnswered: Boolean = true,
    val socOffset: Int = 0,
    /** Position of the inserted charging stop along the route (0..1). */
    val stopRatio: Double = 0.82,
    /** Active-route waypoint data (max power, charger count) delivered with the plan; null as on the emulator. */
    val waypoint: ActiveWaypoint? = null,
    val seedBand: Pair<Int, Double>? = null,
    val restrictionReported: Boolean = true,
)

/** The simulated car and navigation. Plain Kotlin: [step] advances one virtual second. */
class SimWorld(private val scenario: Scenario) {
    private val c = scenario.config
    private var vehicleListener: VehicleSource.Listener? = null
    private var restrictionListener: ((Boolean) -> Unit)? = null
    private var naviListener: NaviSource.Listener? = null
    private val scheduled = ArrayList<Pair<Long, () -> Unit>>()
    private var levelKwh = c.startKwh
    private var charging = false
    private var driving = false
    private var lastSoc = Int.MIN_VALUE
    private val destination = c.consumptionKwh?.let { Destination("부산시청", 35.1798, 129.0750, 375_000) }
    private val stop = ChargingStop("청도새마을휴게소(부산방향) 전기차충전소", 35.6712, 128.7390, c.stopRatio)

    var nowMs = 0L
        private set

    private val levelSoc get() = levelKwh / c.capacityKwh * 100.0
    private val pleosSoc get() = (levelSoc.toInt() + c.socOffset).coerceIn(0, 100)

    val vehicle = object : VehicleSource {
        override fun start(listener: VehicleSource.Listener) {
            vehicleListener = listener
            if (!c.socSupported) {
                listener.onVehicleLinkFailed(VehicleLinkFailure.SOC_UNSUPPORTED, "simulated")
                return
            }
            if (c.levelReadable) {
                listener.onCapacityWh(c.capacityKwh * 1000.0)
                listener.onBatteryLevelWh(levelKwh * 1000.0)
                listener.onChargeState(if (c.realSignals) CHARGE_STATE_NOT_CHARGING else CHARGE_STATE_FULLY_CHARGED)
                listener.onInstantaneousRateKw(0.0)
            }
            emitSoc()
        }

        override fun stop() {
            vehicleListener = null
        }
    }

    val restrictions = object : RestrictionSource {
        override fun start(onRestricted: (Boolean) -> Unit) {
            restrictionListener = onRestricted
            if (c.restrictionReported) onRestricted(driving)
        }

        override fun stop() {
            restrictionListener = null
        }
    }

    val navi = object : NaviSource {
        override fun start(listener: NaviSource.Listener) {
            naviListener = listener
            if (!c.naviAvailable) {
                listener.onNaviUnavailable("simulated")
                return
            }
            listener.onNaviReady()
            listener.onDestination(destination)
        }

        override fun stop() {
            naviListener = null
        }

        override fun requestDestinationTrip() {
            val consumption = c.consumptionKwh ?: return
            if (!c.tripsAnswered) {
                after(5_000L) { naviListener?.onTripFailed("timeout DESTINATION") }
                return
            }
            after(600L) {
                val direct = (levelKwh - consumption) / c.capacityKwh * 100.0
                val inserted = stop.takeIf { direct < 10.0 }
                naviListener?.onDestinationTrip(
                    DestinationTrip(if (inserted == null) direct.roundToInt() else 10, consumption * 1000.0, 375_000, inserted)
                )
                if (inserted != null) {
                    after(600L) {
                        val atStop = (levelKwh - consumption * c.stopRatio) / c.capacityKwh * 100.0
                        naviListener?.onChargingStopTrip(inserted, atStop.roundToInt())
                        c.waypoint?.let { naviListener?.onActiveWaypoints(listOf(it)) }
                    }
                }
            }
        }

        override fun go(viaStop: ChargingStop?) {
            naviListener?.onRouteResult(viaStop, true, null)
            // The driver unplugs and drives off a few seconds after the route is handed over.
            after(8_000L) {
                charging = false
                driving = true
                restrictionListener?.invoke(true)
            }
        }
    }

    fun step() {
        nowMs += 1_000L
        val due = scheduled.filter { it.first <= nowMs }
        scheduled.removeAll(due)
        due.forEach { it.second() }

        val listener = vehicleListener ?: return
        if (!c.socSupported) return
        if (!driving && c.plugAtS != null && nowMs == c.plugAtS * 1_000L + 1_000L) setCharging(true)
        if (charging) {
            levelKwh += c.curve(levelSoc) / 3600.0
            if (c.stopAtSoc != null && levelSoc >= c.stopAtSoc) setCharging(false)
            if (levelKwh >= c.capacityKwh) { levelKwh = c.capacityKwh; setCharging(false) }
        }
        if (driving) levelKwh = max(0.0, levelKwh - 0.005)
        if (c.levelReadable) {
            listener.onBatteryLevelWh(levelKwh * 1000.0)
            if (c.realSignals) listener.onInstantaneousRateKw(if (charging) c.curve(levelSoc) else 0.0)
        }
        emitSoc()
    }

    private fun setCharging(on: Boolean) {
        charging = on
        if (c.realSignals && c.levelReadable) {
            vehicleListener?.onChargeState(if (on) VehicleSource.CHARGE_STATE_CHARGING else CHARGE_STATE_FULLY_CHARGED)
        }
    }

    private fun emitSoc() {
        val soc = pleosSoc
        if (soc != lastSoc) {
            lastSoc = soc
            vehicleListener?.onSoc(soc)
        }
    }

    private fun after(delayMs: Long, block: () -> Unit) {
        scheduled += (nowMs + delayMs) to block
    }

    private companion object {
        const val CHARGE_STATE_FULLY_CHARGED = 2
        const val CHARGE_STATE_NOT_CHARGING = 3
    }
}
