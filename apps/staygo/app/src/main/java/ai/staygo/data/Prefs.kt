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
                val kw = (value as? Float)?.toDouble()?.takeIf { it > 0.0 && it <= MAX_KW } ?: continue
                bands.merge(band, kw) { a, b -> maxOf(a, b) }
            }
        }
    }

    override fun record(soc: Int, kw: Double) {
        if (kw <= 0.0 || kw > MAX_KW) return
        val band = InMemoryBandStore.bandOf(soc)
        if (kw <= (bands[band] ?: 0.0)) return
        bands[band] = kw
        prefs.edit().putFloat("$PREFIX$band", kw.toFloat()).apply()
    }

    override fun max(soc: Int): Double? = bands[InMemoryBandStore.bandOf(soc)]?.takeIf { it > 0.0 }

    override fun clear() {
        bands.clear()
        prefs.edit().clear().apply()
    }

    override fun isEmpty(): Boolean = bands.isEmpty()

    private companion object {
        const val PREFIX = "band_"
        const val MAX_KW = 400.0
    }
}

/**
 * The reserve SOC and the answer to the data notice, served from memory. Until the stored values have been
 * loaded on [io], [loaded] is false and the consent is unknown, so nothing is read from the vehicle.
 */
class PrefsSettingsStore(context: Context, io: Executor, private val onLoaded: () -> Unit = {}) : SettingsStore {
    private val prefs by lazy { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    @Volatile private var reserve = DEFAULT_RESERVE
    @Volatile private var reserveChanged = false
    @Volatile private var consentValue: Boolean? = null
    @Volatile private var consentChanged = false
    @Volatile override var loaded = false
        private set

    init {
        io.execute {
            val storedReserve = prefs.getInt(KEY_RESERVE, DEFAULT_RESERVE)
            // An answer given to an earlier version of the notice does not cover this one: ask again.
            val current = prefs.getInt(KEY_NOTICE_VERSION, 0) == NOTICE_VERSION
            val storedConsent = if (current && prefs.contains(KEY_CONSENT)) prefs.getBoolean(KEY_CONSENT, false) else null
            if (!reserveChanged) reserve = storedReserve
            if (!consentChanged) consentValue = storedConsent
            loaded = true
            onLoaded()
        }
    }

    override var reserveSocPct: Int
        get() = reserve
        set(value) {
            reserveChanged = true
            reserve = value
            prefs.edit().putInt(KEY_RESERVE, value).apply()
        }

    override var consent: Boolean?
        get() = consentValue
        set(value) {
            consentChanged = true
            consentValue = value
            prefs.edit().apply {
                if (value == null) remove(KEY_CONSENT) else putBoolean(KEY_CONSENT, value).putInt(KEY_NOTICE_VERSION, NOTICE_VERSION)
            }.apply()
        }

    private companion object {
        const val KEY_RESERVE = "reserve_soc_pct"
        const val KEY_CONSENT = "data_notice_accepted"
        const val KEY_NOTICE_VERSION = "data_notice_version"
        /** Raised whenever the notice changes what it says is read, kept or handed over. */
        const val NOTICE_VERSION = 2
        const val DEFAULT_RESERVE = 15
    }
}
