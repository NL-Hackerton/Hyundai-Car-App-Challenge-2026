package ai.staygo.core

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/** Fast-charging station from the bundled public-data snapshot. */
data class StationInfo(val name: String, val latitude: Double, val longitude: Double, val maxPowerKw: Int, val chargerCount: Int)

/** Where the max output of a charging stop came from. */
enum class OutputSource {
    /** onDrivingInfo of the route the navigation is guiding. */
    ACTIVE_ROUTE,
    /** Bundled snapshot of public charger data; may be older than the station. */
    SNAPSHOT,
}

interface StationDirectory {
    /** Date of the snapshot (yyyy-MM-dd), shown next to every value taken from it. */
    val asOf: String?
    fun lookup(name: String, latitude: Double, longitude: Double): StationInfo?
}

/**
 * Stations bucketed in a 0.01° grid. A stop matches the stations within [RADIUS_M] of its coordinates;
 * among them the one sharing the leading part of the name wins, otherwise the nearest.
 * Nothing leaves the device: the lookup is local.
 */
class GridStationDirectory(override val asOf: String?, stations: List<StationInfo>) : StationDirectory {

    private val cells: Map<Long, List<StationInfo>> = stations.groupBy { cell(it.latitude, it.longitude) }

    val size: Int = stations.size

    override fun lookup(name: String, latitude: Double, longitude: Double): StationInfo? {
        val row = floor(latitude * 100).toLong()
        val col = floor(longitude * 100).toLong()
        val near = ArrayList<Pair<Double, StationInfo>>()
        for (r in row - 1..row + 1) for (c in col - 1..col + 1) {
            cells[r * CELL_COLUMNS + c]?.forEach { s ->
                val d = distanceM(latitude, longitude, s.latitude, s.longitude)
                if (d <= RADIUS_M) near += d to s
            }
        }
        if (near.isEmpty()) return null
        val key = nameKey(name)
        val named = near.filter { key.length >= 2 && nameKey(it.second.name).let { k -> k.startsWith(key) || key.startsWith(k) } }
        return (named.ifEmpty { near }).minByOrNull { it.first }?.second
    }

    companion object {
        const val RADIUS_M = 300.0
        private const val CELL_COLUMNS = 100_000L

        private fun cell(lat: Double, lng: Double): Long = floor(lat * 100).toLong() * CELL_COLUMNS + floor(lng * 100).toLong()

        private fun nameKey(s: String): String = s.filterNot { it.isWhitespace() }.take(4)

        private fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dy = (lat2 - lat1) * 111_320.0
            val dx = (lng2 - lng1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
            return hypot(dx, dy)
        }

        /**
         * Snapshot format: a first line `#asOf=yyyy-MM-dd`, then one station per line as
         * `latitude<TAB>longitude<TAB>maxPowerKw<TAB>chargerCount<TAB>name`. Malformed lines are skipped.
         */
        fun parse(lines: Sequence<String>): GridStationDirectory {
            var asOf: String? = null
            val stations = ArrayList<StationInfo>()
            for (line in lines) {
                if (line.startsWith("#")) {
                    if (line.startsWith("#asOf=")) asOf = line.removePrefix("#asOf=").trim()
                    continue
                }
                val f = line.split('\t')
                if (f.size < 5) continue
                val lat = f[0].toDoubleOrNull() ?: continue
                val lng = f[1].toDoubleOrNull() ?: continue
                val kw = f[2].toIntOrNull()?.takeIf { it > 0 } ?: continue
                val count = f[3].toIntOrNull() ?: continue
                stations += StationInfo(f[4], lat, lng, kw, count)
            }
            return GridStationDirectory(asOf, stations)
        }
    }
}
