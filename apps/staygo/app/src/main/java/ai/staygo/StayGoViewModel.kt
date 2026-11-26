package ai.staygo

import ai.staygo.core.GridStationDirectory
import ai.staygo.core.StayGoEngine
import ai.staygo.core.StayGoUiState
import ai.staygo.core.UiNotice
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the vehicle and navigation sources and the engine. The UI collects [state] and [notices] and calls the
 * action methods; nothing else.
 *
 * The sources run only while the screen is visible and the vehicle permissions are granted: in the background
 * the app holds no listeners, timers or services. The charging session (first seen, session maximum, target
 * SOC) is kept across that gap and, through the saved state, across process recreation.
 */
class StayGoViewModel(app: Application, private val handle: SavedStateHandle) : AndroidViewModel(app) {

    private val sources: Sources = SourceProvider.create(app, handle)
    private val verbose = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val _notices = MutableSharedFlow<UiNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val engine = StayGoEngine(
        navi = sources.navi,
        bands = sources.bands,
        settings = sources.settings,
        clock = sources.clock,
        notify = { _notices.tryEmit(it) },
        // Battery values and place names stay out of release logs unless a developer enables the tag
        // (`adb shell setprop log.tag.StayGo DEBUG`).
        log = { if (verbose || Log.isLoggable(TAG, Log.DEBUG)) Log.i(TAG, it) },
        onChange = ::publish,
    )
    private val _state: MutableStateFlow<StayGoUiState>
    private var granted = false
    private var visible = false
    private var ticker: Job? = null

    val state: StateFlow<StayGoUiState> get() = _state
    val notices: SharedFlow<UiNotice> = _notices
    val permissionMode: PermissionMode get() = sources.permissions

    init {
        handle.get<Bundle>(KEY_SESSION)?.let { engine.restore(it.toSession()) }
        handle.setSavedStateProvider(KEY_SESSION) { engine.sessionState().toBundle() }
        engine.start()
        _state = MutableStateFlow(engine.snapshot())

        // The station snapshot is optional: without the asset, stop output comes from the active route only.
        viewModelScope.launch {
            val directory = withContext(Dispatchers.IO) {
                try {
                    app.assets.open(STATIONS_ASSET).bufferedReader().useLines { GridStationDirectory.parse(it) }
                } catch (e: java.io.IOException) {
                    null
                }
            }
            if (directory != null) engine.stations = directory
        }
        when (sources.permissions) {
            PermissionMode.GRANTED -> onPermissions(granted = true)
            PermissionMode.DENIED -> onPermissions(granted = false)
            PermissionMode.REQUEST -> Unit
        }
    }

    /** Result of the vehicle permission check. Signals run only while the permissions are granted. */
    fun onPermissions(granted: Boolean) {
        this.granted = granted
        if (granted) engine.onPermissionGranted() else engine.onPermissionDenied()
        sync()
    }

    /** The activity is started (visible) or stopped. */
    fun setVisible(visible: Boolean) {
        this.visible = visible
        sync()
    }

    fun stay() = engine.stay()
    fun go() = engine.go()
    fun confirmCharging() = engine.confirmCharging()
    fun adjustReserveSoc(deltaPct: Int) = engine.adjustReserveSoc(deltaPct)
    fun adjustTargetSoc(deltaPct: Int) = engine.adjustTargetSoc(deltaPct)

    private fun sync() {
        val run = granted && visible
        if (run && ticker == null) {
            engine.resume()
            sources.vehicle.start(engine)
            sources.navi.start(engine)
            ticker = viewModelScope.launch {
                while (true) {
                    engine.tick()
                    publish()
                    delay(1_000L)
                }
            }
        } else if (!run && ticker != null) {
            ticker?.cancel()
            ticker = null
            sources.vehicle.stop()
            sources.navi.stop()
            engine.suspend()
            publish()
        }
    }

    private fun publish() {
        _state.value = engine.snapshot()
    }

    override fun onCleared() {
        if (ticker != null) {
            sources.vehicle.stop()
            sources.navi.stop()
        }
    }

    private fun StayGoEngine.SessionState.toBundle() = Bundle().apply {
        chargingSinceMs?.let { putLong("chargingSince", it) }
        putBoolean("wasCharging", wasCharging)
        sessionMaxKw?.let { putDouble("sessionMax", it) }
        putBoolean("pmaxTrusted", pmaxTrusted)
        manualTargetSoc?.let { putInt("targetSoc", it) }
        putBoolean("goNoticeSent", goNoticeSent)
        suspendedAtMs?.let { putLong("suspendedAt", it) }
        levelAtSuspendWh?.let { putDouble("levelAtSuspend", it) }
        putBoolean("chargingAtSuspend", chargingAtSuspend)
    }

    private fun Bundle.toSession() = StayGoEngine.SessionState(
        chargingSinceMs = if (containsKey("chargingSince")) getLong("chargingSince") else null,
        wasCharging = getBoolean("wasCharging"),
        sessionMaxKw = if (containsKey("sessionMax")) getDouble("sessionMax") else null,
        pmaxTrusted = getBoolean("pmaxTrusted", true),
        manualTargetSoc = if (containsKey("targetSoc")) getInt("targetSoc") else null,
        goNoticeSent = getBoolean("goNoticeSent"),
        suspendedAtMs = if (containsKey("suspendedAt")) getLong("suspendedAt") else null,
        levelAtSuspendWh = if (containsKey("levelAtSuspend")) getDouble("levelAtSuspend") else null,
        chargingAtSuspend = getBoolean("chargingAtSuspend"),
    )

    companion object {
        const val TAG = "StayGo"
        /** Built by tools/build_station_snapshot.py; the app works without it. */
        const val STATIONS_ASSET = "stations.tsv"
        private const val KEY_SESSION = "session"
    }
}
