package ai.staygo

import ai.staygo.core.InMemoryBandStore
import ai.staygo.core.InMemorySettingsStore
import ai.staygo.data.PrefsBandStore
import ai.staygo.data.PrefsSettingsStore
import ai.staygo.navi.NaviClient
import ai.staygo.sim.Scenario
import ai.staygo.sim.Simulator
import ai.staygo.vehicle.VehicleSignals
import android.app.Activity
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle

/**
 * Debug build: the vehicle and the navigation by default; a simulator when the activity is started with
 * `--es sim <scenario>` (optionally `--ei speed <n>`) or through the "스테이고 시뮬레이터" launcher entry.
 */
object SourceProvider {
    private const val SIM_LAUNCHER = "ai.staygo.SimLauncher"
    const val EXTRA_SIM = "sim"
    const val EXTRA_SPEED = "speed"

    fun prepare(activity: Activity) {
        val intent = activity.intent
        if (intent.component?.className == SIM_LAUNCHER && !intent.hasExtra(EXTRA_SIM)) {
            intent.putExtra(EXTRA_SIM, Scenario.CHARGE.id)
        }
    }

    fun create(app: Application, handle: SavedStateHandle): Sources {
        val scenario = handle.get<String>(EXTRA_SIM)?.let(Scenario::of)
        if (scenario == null) {
            return Sources(
                vehicle = VehicleSignals(app),
                navi = NaviClient(app),
                clock = SystemClock::elapsedRealtime,
                bands = PrefsBandStore(app),
                settings = PrefsSettingsStore(app),
                permissions = PermissionMode.REQUEST,
            )
        }
        val sim = Simulator(scenario, speed = (handle.get<Int>(EXTRA_SPEED) ?: 1).coerceIn(1, 20))
        return Sources(
            vehicle = sim.vehicle,
            navi = sim.navi,
            clock = sim::nowMs,
            bands = InMemoryBandStore().apply { scenario.seedBands(this) },
            settings = InMemorySettingsStore(),
            permissions = if (scenario == Scenario.PERMISSION_DENIED) PermissionMode.DENIED else PermissionMode.GRANTED,
        )
    }
}
