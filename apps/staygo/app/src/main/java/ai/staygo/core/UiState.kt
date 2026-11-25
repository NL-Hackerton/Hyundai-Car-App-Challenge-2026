package ai.staygo.core

import ai.staygo.decision.CandidateComparison
import ai.staygo.decision.ChargingStatus
import ai.staygo.decision.Decision
import ai.staygo.decision.Emphasis

/**
 * Everything a screen needs, with no Android types and no display strings.
 * The UI renders this and calls the actions on StayGoViewModel; it holds no state of its own.
 */
data class StayGoUiState(
    val screen: Screen,
    val vehicleLinkFailure: VehicleLinkFailure?,
    val charging: ChargingStatus,
    val chargingBasis: ChargingBasis?,
    val socPercent: Int?,
    val socMismatch: SocMismatch?,
    val power: PowerState?,
    val powerHistory: List<PowerSample>,
    val destination: DestinationState?,
    val arrival: ArrivalState?,
    val route: RouteState,
    val decision: Decision,
    val go: GoAvailability,
    val reserveSocPct: Int,
    val manualTargetSoc: Int?,
    val occupancy: Occupancy?,
    val lateRatio: Double,
    val stopOverheadMin: Double,
) {
    val stayEmphasized: Boolean get() = decision.emphasis == Emphasis.STAY || decision.emphasis == Emphasis.BOTH_STAY
    val goEmphasized: Boolean get() = decision.emphasis == Emphasis.GO
}

/** One entry per row of the proposal's vehicle-state table. DRIVING overrides every other screen. */
enum class Screen {
    /** Driving restriction is on: no controls, one summary line. */
    DRIVING,
    /** Permission denied, SOC unsupported, or vehicle services missing. No vehicle values are shown. */
    VEHICLE_LINK_UNAVAILABLE,
    /** First vehicle values have not arrived yet. */
    CONNECTING,
    /** Neither the charge state nor the battery level can be read; the driver confirms charging by hand. */
    CHARGING_UNCONFIRMED,
    NOT_CHARGING,
    /** Charging ended (full, or the charger stopped at its limit). */
    CHARGE_FINISHED,
    /** Charging, no destination in the navigation: Go is locked. */
    NO_DESTINATION,
    /** Charging, the navigation gives no arrival SOC: the driver enters a target SOC. */
    TARGET_SOC_NEEDED,
    /** Charging with an arrival estimate: the Stay/Go decision. */
    DECISION,
}

enum class ChargingBasis { CHARGE_STATE, BATTERY_LEVEL_RISE, DRIVER_CONFIRMED }

/** Pleos SOC and the SOC computed from the battery level differ by more than 3 %p; the navigation follows the level. */
data class SocMismatch(val pleosSoc: Int, val levelSoc: Double)

data class PowerState(
    val kw: Double,
    /** True when computed from the battery level change instead of the instantaneous charge rate. */
    val estimated: Boolean,
    val sessionMaxKw: Double?,
    /** False when the power only fell during the first minute: the app was opened after the taper started. */
    val sessionMaxTrusted: Boolean,
    /** Null while the battery capacity is unknown. */
    val secondsPerPercent: Double?,
)

data class PowerSample(val elapsedMs: Long, val kw: Double)

data class DestinationState(val name: String, val remainDistanceM: Int)

enum class ArrivalBasis {
    /** The navigation's arrival SOC for a direct route. */
    NAVI_DIRECT,
    /** The navigation inserted a charging stop; arrival without charging is computed from route energy. */
    NAVI_ROUTE_ENERGY,
    /** The navigation gave no estimate; the driver's target SOC replaces it. */
    DRIVER_TARGET,
}

data class ArrivalState(
    val basis: ArrivalBasis,
    /** Arrival SOC at the destination without further charging; null for DRIVER_TARGET. */
    val socPct: Double?,
    val reserveMet: Boolean,
    /** Percent points still missing (to the reserve, or to the driver's target). */
    val deficitPct: Double?,
    /** Lower bound of the extra wait at the current power, minutes. */
    val extraWaitMinutes: Double?,
)

sealed interface RouteState {
    /** No destination or no plan yet. */
    data object None : RouteState
    data object Direct : RouteState
    data class ViaStop(
        val stopName: String,
        val arrivalSocPct: Double?,
        val maxPowerKw: Double?,
        val chargerCount: Int?,
        val comparison: CandidateComparison?,
        /** Where maxPowerKw and chargerCount came from; null when unknown. */
        val outputSource: OutputSource? = null,
        /** Date of the bundled snapshot, to show when outputSource is SNAPSHOT. */
        val snapshotAsOf: String? = null,
    ) : RouteState
}

enum class GoAvailability { DIRECT, VIA_STOP, LOCKED_NO_DESTINATION, LOCKED_NAVI_UNAVAILABLE, LOCKED_NOT_CHARGING }

/** Minutes since the app first observed charging. The real start is unknown when the app was opened later. */
data class Occupancy(val minutesObserved: Int, val limitMinutes: Int, val warning: Boolean)

/** One-shot outcomes of an action; the UI shows them once. */
sealed interface UiNotice {
    data class RouteSent(val viaStopName: String?) : UiNotice
    data class RouteFailed(val detail: String?) : UiNotice
    data object StayAcknowledged : UiNotice
    data object DestinationRequired : UiNotice
    data object NaviUnavailable : UiNotice
}
