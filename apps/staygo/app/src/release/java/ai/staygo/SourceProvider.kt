package ai.staygo

import ai.staygo.data.PrefsBandStore
import ai.staygo.data.PrefsSettingsStore
import ai.staygo.navi.NaviClient
import ai.staygo.vehicle.VehicleSignals
import android.app.Activity
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

/** Release build: the vehicle and the navigation, nothing else. */
object SourceProvider {
    @Suppress("UNUSED_PARAMETER")
    fun prepare(activity: Activity) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun create(app: Application, handle: SavedStateHandle): Sources {
        val io = Dispatchers.IO.asExecutor()
        return Sources(
            vehicle = VehicleSignals(app),
            navi = NaviClient(app),
            clock = SystemClock::elapsedRealtime,
            bands = PrefsBandStore(app, io),
            settings = PrefsSettingsStore(app, io),
            permissions = PermissionMode.REQUEST,
        )
    }
}
