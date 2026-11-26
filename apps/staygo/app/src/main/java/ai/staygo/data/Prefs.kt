package ai.staygo.data

import ai.staygo.core.BandStore
import ai.staygo.core.InMemoryBandStore
import ai.staygo.core.SettingsStore
import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Stored on the device only: max charging power per 5 %p SOC band. No location, time, or station.
 * Values are loaded on [io] and served from memory, so the main thread never waits for the disk; a lookup
 * before the load has finished answers "no history yet".
 */
class PrefsBandStore(context: Context, io: Executor) : BandStore {
    private val prefs by lazy { context.getSharedPreferences("band_power", Context.MODE_PRIVATE) }
    private val bands = ConcurrentHashMap<Int, Double>()

    init {
        io.execute {
            for ((key, value) in prefs.all) {
                val band = key.removePrefix(PREFIX).toIntOrNull() ?: continue
                val kw = (value as? Float)?.toDouble() ?: continue
                bands.merge(band, kw) { a, b -> maxOf(a, b) }
            }
        }
    }

    override fun record(soc: Int, kw: Double) {
        val band = InMemoryBandStore.bandOf(soc)
        if (kw <= (bands[band] ?: 0.0)) return
        bands[band] = kw
        prefs.edit().putFloat("$PREFIX$band", kw.toFloat()).apply()
    }

    override fun max(soc: Int): Double? = bands[InMemoryBandStore.bandOf(soc)]?.takeIf { it > 0.0 }

    private companion object {
        const val PREFIX = "band_"
    }
}

/** The reserve SOC is served from memory (15 %p until the stored value has been loaded on [io]). */
class PrefsSettingsStore(context: Context, io: Executor, onLoaded: () -> Unit = {}) : SettingsStore {
    private val prefs by lazy { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    @Volatile private var reserve = DEFAULT_RESERVE
    @Volatile private var changed = false

    init {
        io.execute {
            val stored = prefs.getInt(KEY_RESERVE, DEFAULT_RESERVE)
            if (!changed) reserve = stored
            onLoaded()
        }
    }

    override var reserveSocPct: Int
        get() = reserve
        set(value) {
            changed = true
            reserve = value
            prefs.edit().putInt(KEY_RESERVE, value).apply()
        }

    private companion object {
        const val KEY_RESERVE = "reserve_soc_pct"
        const val DEFAULT_RESERVE = 15
    }
}
