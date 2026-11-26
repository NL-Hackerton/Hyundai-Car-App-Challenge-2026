package ai.staygo

import ai.staygo.data.PrefsBandStore
import ai.staygo.data.PrefsSettingsStore
import ai.staygo.navi.NaviClient
import ai.staygo.vehicle.DrivingRestrictions
import ai.staygo.vehicle.VehicleSignals
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

/** Release build: the vehicle and the navigation, nothing else. */
object SourceProvider {
    /**
     * The launcher activity is exported, and intent extras become the default values of the saved state the
     * view model restores the session from. The app takes no input from an intent, so every extra is dropped
     * before the view model can see it.
     */
    fun prepare(activity: Activity) {
        activity.intent?.replaceExtras(Bundle())
    }

    @Suppress("UNUSED_PARAMETER")
    fun create(app: Application, handle: SavedStateHandle, onSettingsLoaded: () -> Unit): Sources {
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
}
