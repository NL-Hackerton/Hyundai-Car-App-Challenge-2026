package ai.staygo

import ai.staygo.core.InMemoryBandStore
import ai.staygo.core.InMemorySettingsStore
import ai.staygo.data.PrefsBandStore
import ai.staygo.data.PrefsSettingsStore
import ai.staygo.navi.NaviClient
import ai.staygo.sim.Scenario
import ai.staygo.sim.Simulator
import ai.staygo.vehicle.DrivingRestrictions
import ai.staygo.vehicle.VehicleSignals
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.StrictMode
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

/**
 * Debug build: the vehicle and the navigation by default; a simulator when the activity is started with
 * `--es sim <scenario>` (optionally `--ei speed <n>`) or through the "스테이고 시뮬레이터" launcher entry.
 * StrictMode logs every violation (tag StrictMode).
 */
object SourceProvider {
    private const val SIM_LAUNCHER = "ai.staygo.SimLauncher"
    const val EXTRA_SIM = "sim"
    const val EXTRA_SPEED = "speed"

    fun prepare(activity: Activity) {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        // Only the two simulator extras reach the view model's saved state; everything else is dropped.
        val intent = activity.intent ?: return
        val sim = intent.getStringExtra(EXTRA_SIM) ?: Scenario.CHARGE.id.takeIf { intent.component?.className == SIM_LAUNCHER }
        val speed = intent.getIntExtra(EXTRA_SPEED, 1)
        intent.replaceExtras(Bundle().apply { if (sim != null) { putString(EXTRA_SIM, sim); putInt(EXTRA_SPEED, speed) } })
    }

    fun create(app: Application, handle: SavedStateHandle, onSettingsLoaded: () -> Unit): Sources {
        val scenario = handle.get<String>(EXTRA_SIM)?.let(Scenario::of)
        if (scenario == null) {
            val io = Dispatchers.IO.asExecutor()
            return Sources(
                restrictions = DrivingRestrictions(app),
                vehicle = VehicleSignals(app),
                navi = NaviClient(app),
                clock = SystemClock::elapsedRealtime,
                bands = PrefsBandStore(app, io),
                settings = PrefsSettingsStore(app, io, onSettingsLoaded),
                permissions = PermissionMode.REQUEST,
            )
        }
        val sim = Simulator(scenario, speed = (handle.get<Int>(EXTRA_SPEED) ?: 1).coerceIn(1, 20))
        return Sources(
            restrictions = sim.restrictions,
            vehicle = sim.vehicle,
            navi = sim.navi,
            clock = sim::nowMs,
            bands = InMemoryBandStore().apply { scenario.seedBands(this) },
            settings = InMemorySettingsStore(consent = if (scenario == Scenario.CONSENT) null else true),
            permissions = if (scenario == Scenario.PERMISSION_DENIED) PermissionMode.DENIED else PermissionMode.GRANTED,
        )
    }
}
