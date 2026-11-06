package ai.staygo.decision

import kotlin.math.min

enum class ChargingStatus { CHARGING, FINISHED, NOT_CHARGING, UNKNOWN }

enum class Emphasis { NONE, STAY, BOTH_STAY, GO }

/** Charging stop the navigation inserted on the route (RoutePointPoiType.EV_CHARGING). */
data class Candidate(
    val name: String,
    /** Arrival SOC at the candidate from a separate requestTrip (Ac), percent. */
    val arrivalSoc: Double?,
    /** Max charger output from onDrivingInfo (active route) or the optional backend, kW. */
    val maxPowerKw: Double?,
    val chargerCount: Int?,
)

data class Params(
    /** m: reserve SOC at the destination, percent points. */
    val reserveSocPct: Double = 15.0,
    /** k: late-phase ratio of current power to session max. */
    val lateRatio: Double = 0.5,
    /** s: extra stop overhead of charging at a waypoint, minutes. */
    val stopOverheadMin: Double = 5.0,
    /** T: published 10→80 % fast-charge time, minutes (fallback average power 0.7×C÷T). */
    val fastChargeMinutes10to80: Double = 18.0,
    /** SOC that replaces the power condition when power is unavailable (rule 6). */
    val fallbackLateSoc: Int = 80,
)

data class Inputs(
    val charging: ChargingStatus,
    val soc: Int?,
    val powerKw: Double?,
    val pmaxKw: Double?,
    val pmaxTrusted: Boolean,
    val hasDestination: Boolean,
    /** A: arrival SOC at the destination without further charging, percent. */
    val arrivalSoc: Double?,
    val candidate: Candidate?,
    /** Max power this car received earlier in the candidate's SOC band, kW. */
    val bandPowerKw: Double?,
    val capacityKwh: Double,
    val params: Params = Params(),
)

data class CandidateComparison(
    val hereMinutes: Double,
    val candidateMinutes: Double,
    val expectedPowerKw: Double,
    /** True when Pc came from the published 10→80 % time instead of this car's history. */
    val expectedPowerEstimated: Boolean,
) {
    val gainMinutes: Double get() = hereMinutes - candidateMinutes
}

data class Decision(
    /** Rule number from the proposal (1–6) that produced the emphasis; null while waiting for inputs. */
    val rule: Int?,
    val emphasis: Emphasis,
    val state: State,
    val deficitPct: Double? = null,
    val extraWaitMinutes: Double? = null,
    val powerRatio: Double? = null,
    val comparison: CandidateComparison? = null,
) {
    enum class State {
        NOT_CHARGING,
        CHARGE_FINISHED,
        NO_DESTINATION,
        WAITING_NAVI,
        SHORT_OF_RESERVE,
        RESERVE_MET_POWER_HIGH,
        RESERVE_MET_POWER_LOW,
        RESERVE_MET_SOC_FALLBACK_LOW,
        RESERVE_MET_SOC_FALLBACK_HIGH,
    }
}

object DecisionModel {

    fun decide(i: Inputs): Decision {
        val p = i.params
        when (i.charging) {
            ChargingStatus.FINISHED -> return Decision(1, Emphasis.GO, Decision.State.CHARGE_FINISHED)
            ChargingStatus.NOT_CHARGING, ChargingStatus.UNKNOWN ->
                return Decision(1, Emphasis.NONE, Decision.State.NOT_CHARGING)
            ChargingStatus.CHARGING -> Unit
        }
        if (!i.hasDestination) return Decision(null, Emphasis.NONE, Decision.State.NO_DESTINATION)
        val a = i.arrivalSoc ?: return Decision(null, Emphasis.NONE, Decision.State.WAITING_NAVI)
        val power = i.powerKw?.takeIf { it > 0.0 }
        val ratio = if (power != null && i.pmaxKw != null && i.pmaxKw > 0.0) power / i.pmaxKw else null

        if (a < p.reserveSocPct) {
            val deficit = p.reserveSocPct - a
            val deltaKwh = deficit / 100.0 * i.capacityKwh
            val wait = power?.let { deltaKwh / it * 60.0 }
            val comparison = compareCandidate(i, deltaKwh, power)
            return Decision(
                rule = if (comparison != null && comparison.gainMinutes > 0.0) 5 else 2,
                emphasis = Emphasis.STAY,
                state = Decision.State.SHORT_OF_RESERVE,
                deficitPct = deficit,
                extraWaitMinutes = wait,
                powerRatio = ratio,
                comparison = comparison,
            )
        }

        if (power != null && i.pmaxKw != null && i.pmaxTrusted && ratio != null) {
            return if (power >= p.lateRatio * i.pmaxKw) {
                Decision(3, Emphasis.BOTH_STAY, Decision.State.RESERVE_MET_POWER_HIGH, powerRatio = ratio)
            } else {
                Decision(4, Emphasis.GO, Decision.State.RESERVE_MET_POWER_LOW, powerRatio = ratio)
            }
        }
        val soc = i.soc ?: return Decision(6, Emphasis.BOTH_STAY, Decision.State.RESERVE_MET_SOC_FALLBACK_LOW)
        return if (soc >= p.fallbackLateSoc) {
            Decision(6, Emphasis.GO, Decision.State.RESERVE_MET_SOC_FALLBACK_HIGH, powerRatio = ratio)
        } else {
            Decision(6, Emphasis.BOTH_STAY, Decision.State.RESERVE_MET_SOC_FALLBACK_LOW, powerRatio = ratio)
        }
    }

    /** Rule 5: fill the deficit here (ΔE÷P) or at the candidate (ΔE÷Pc + s). Skipped when Ac < m or max power is unknown. */
    private fun compareCandidate(i: Inputs, deltaKwh: Double, power: Double?): CandidateComparison? {
        val c = i.candidate ?: return null
        val ac = c.arrivalSoc ?: return null
        val maxPower = c.maxPowerKw?.takeIf { it > 0.0 } ?: return null
        if (power == null || ac < i.params.reserveSocPct) return null
        val fallback = 0.7 * i.capacityKwh / (i.params.fastChargeMinutes10to80 / 60.0)
        val history = i.bandPowerKw?.takeIf { it > 0.0 }
        val expected = min(maxPower, history ?: fallback)
        return CandidateComparison(
            hereMinutes = deltaKwh / power * 60.0,
            candidateMinutes = deltaKwh / expected * 60.0 + i.params.stopOverheadMin,
            expectedPowerKw = expected,
            expectedPowerEstimated = history == null,
        )
    }

    /** Seconds to add one SOC percent point at the current power. */
    fun secondsPerPercent(capacityKwh: Double, powerKw: Double): Double = capacityKwh / 100.0 / powerKw * 3600.0
}
