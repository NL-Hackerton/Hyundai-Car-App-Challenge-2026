package ai.staygo

import ai.staygo.core.StayGoEngine
import ai.staygo.core.StayGoUiState
import ai.staygo.core.UiNotice
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the vehicle and navigation sources and the engine for the lifetime of the screen (it survives
 * configuration changes). The UI collects [state] and [notices] and calls the action methods; nothing else.
 */
class StayGoViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {

    private val sources: Sources = SourceProvider.create(app, handle)
    private val _notices = MutableSharedFlow<UiNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val engine = StayGoEngine(
        navi = sources.navi,
        bands = sources.bands,
        settings = sources.settings,
        clock = sources.clock,
        notify = { _notices.tryEmit(it) },
        log = { Log.i(TAG, it) },
        onChange = ::publish,
    )
    private val _state = MutableStateFlow(engine.snapshot())
    private var started = false

    val state: StateFlow<StayGoUiState> = _state
    val notices: SharedFlow<UiNotice> = _notices
    val permissionMode: PermissionMode get() = sources.permissions

    init {
        when (sources.permissions) {
            PermissionMode.GRANTED -> onPermissions(granted = true)
            PermissionMode.DENIED -> onPermissions(granted = false)
            PermissionMode.REQUEST -> Unit
        }
    }

    /** Result of the vehicle permission check. Signals start only once the permissions are granted. */
    fun onPermissions(granted: Boolean) {
        if (!granted) {
            engine.onPermissionDenied()
            return
        }
        engine.onPermissionGranted()
        if (started) return
        started = true
        engine.start()
        sources.vehicle.start(engine)
        sources.navi.start(engine)
        viewModelScope.launch {
            while (true) {
                engine.tick()
                publish()
                delay(1_000L)
            }
        }
    }

    fun stay() = engine.stay()
    fun go() = engine.go()
    fun confirmCharging() = engine.confirmCharging()
    fun adjustReserveSoc(deltaPct: Int) = engine.adjustReserveSoc(deltaPct)
    fun adjustTargetSoc(deltaPct: Int) = engine.adjustTargetSoc(deltaPct)

    private fun publish() {
        _state.value = engine.snapshot()
    }

    override fun onCleared() {
        sources.vehicle.stop()
        sources.navi.stop()
    }

    companion object {
        const val TAG = "StayGo"
    }
}
