package ai.staygo

import ai.staygo.core.BandStore
import ai.staygo.core.NaviSource
import ai.staygo.core.RestrictionSource
import ai.staygo.core.SettingsStore
import ai.staygo.core.VehicleSource

/** How the activity treats the vehicle permissions for this set of sources. */
enum class PermissionMode { REQUEST, GRANTED, DENIED }

/**
 * The inputs the app runs on. The release build always uses the vehicle and the navigation;
 * the debug build can replace them with a simulator (see SourceProvider in src/debug).
 */
class Sources(
    val restrictions: RestrictionSource,
    val vehicle: VehicleSource,
    val navi: NaviSource,
    /** Monotonic milliseconds. */
    val clock: () -> Long,
    val bands: BandStore,
    val settings: SettingsStore,
    val permissions: PermissionMode,
)
