package ai.staygo.data

import ai.staygo.core.BandStore
import ai.staygo.core.InMemoryBandStore
import ai.staygo.core.SettingsStore
import android.content.Context

/** Stored on the device only: max charging power per 5 %p SOC band. No location, time, or station. */
class PrefsBandStore(context: Context) : BandStore {
    private val prefs = context.getSharedPreferences("band_power", Context.MODE_PRIVATE)
    private fun key(soc: Int) = "band_${InMemoryBandStore.bandOf(soc)}"

    override fun record(soc: Int, kw: Double) {
        if (kw > prefs.getFloat(key(soc), 0f)) prefs.edit().putFloat(key(soc), kw.toFloat()).apply()
    }

    override fun max(soc: Int): Double? = prefs.getFloat(key(soc), 0f).toDouble().takeIf { it > 0.0 }
}

class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    override var reserveSocPct: Int
        get() = prefs.getInt("reserve_soc_pct", 15)
        set(value) = prefs.edit().putInt("reserve_soc_pct", value).apply()
}
