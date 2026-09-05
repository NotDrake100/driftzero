package `in`.driftzero.core

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Local GNSS-shadow occupancy on ~50 m cells. Not a cloud map.
 *
 * Cell size is [CELL_M] metres. Northing is from the equator, easting from
 * the prime meridian using the sample latitude for metres-per-degree-lon.
 * occupancy = losses / visits. Missing C/N0 is omitted from the mean, not
 * filled with 0.
 */
data class ShadowCell(
    val visits: Int,
    val losses: Int,
    val sumAccuracyM: Double,
    val accuracyCount: Int,
    val sumCn0DbHz: Double,
    val cn0Count: Int,
) {
    init {
        require(visits >= 0 && losses >= 0 && losses <= visits)
        require(accuracyCount >= 0 && accuracyCount <= visits)
        require(cn0Count >= 0 && cn0Count <= visits)
        require(sumAccuracyM.isFinite() && sumAccuracyM >= 0.0)
        require(sumCn0DbHz.isFinite())
    }

    val occupancy: Double
        get() = if (visits == 0) 0.0 else losses.toDouble() / visits.toDouble()

    val meanAccuracyM: Double?
        get() = if (accuracyCount == 0) null else sumAccuracyM / accuracyCount.toDouble()

    val meanCn0DbHz: Double?
        get() = if (cn0Count == 0) null else sumCn0DbHz / cn0Count.toDouble()
}

data class ShadowCellKey(
    val northIndex: Int,
    val eastIndex: Int,
)

class ShadowMap(
    val cellSizeM: Double = CELL_M,
) {
    init {
        require(cellSizeM.isFinite() && cellSizeM > 0.0) { "cellSizeM must be finite and > 0" }
    }

    private val cells = HashMap<ShadowCellKey, ShadowCell>()

    fun cellCount(): Int = cells.size

    fun cellKey(latitudeDeg: Double, longitudeDeg: Double): ShadowCellKey {
        require(latitudeDeg.isFinite() && latitudeDeg in -90.0..90.0)
        require(longitudeDeg.isFinite() && longitudeDeg in -180.0..180.0)
        val latRad = latitudeDeg * PI / 180.0
        val northM = latitudeDeg * METRES_PER_DEG_LAT
        val eastM = longitudeDeg * METRES_PER_DEG_LAT * cos(latRad)
        return ShadowCellKey(
            northIndex = floor(northM / cellSizeM).toInt(),
            eastIndex = floor(eastM / cellSizeM).toInt(),
        )
    }

    /** Geographic centre of [key], for neighbourhood tests and debug. */
    fun cellCenter(key: ShadowCellKey): Pair<Double, Double> {
        val northM = (key.northIndex + 0.5) * cellSizeM
        val lat = (northM / METRES_PER_DEG_LAT).coerceIn(-90.0, 90.0)
        val latRad = lat * PI / 180.0
        val cosLat = cos(latRad).let { if (abs(it) < 1e-12) 1e-12 else it }
        val eastM = (key.eastIndex + 0.5) * cellSizeM
        val lon = (eastM / (METRES_PER_DEG_LAT * cosLat)).coerceIn(-180.0, 180.0)
        return lat to lon
    }

    /**
     * One observation in the cell of [latitudeDeg], [longitudeDeg].
     * [lost] increments the loss count. Accuracy and C/N0 update means only
     * when finite and present.
     */
    fun observe(
        latitudeDeg: Double,
        longitudeDeg: Double,
        lost: Boolean,
        accuracyM: Double? = null,
        cn0DbHz: Double? = null,
    ): ShadowCell {
        val key = cellKey(latitudeDeg, longitudeDeg)
        val prev = cells[key] ?: EMPTY
        val accOk = accuracyM != null && accuracyM.isFinite() && accuracyM >= 0.0
        val cn0Ok = cn0DbHz != null && cn0DbHz.isFinite()
        val next = ShadowCell(
            visits = prev.visits + 1,
            losses = prev.losses + if (lost) 1 else 0,
            sumAccuracyM = prev.sumAccuracyM + if (accOk) accuracyM!! else 0.0,
            accuracyCount = prev.accuracyCount + if (accOk) 1 else 0,
            sumCn0DbHz = prev.sumCn0DbHz + if (cn0Ok) cn0DbHz!! else 0.0,
            cn0Count = prev.cn0Count + if (cn0Ok) 1 else 0,
        )
        cells[key] = next
        return next
    }

    fun occupancy(latitudeDeg: Double, longitudeDeg: Double): Double? {
        val cell = cells[cellKey(latitudeDeg, longitudeDeg)] ?: return null
        if (cell.visits == 0) {
            return null
        }
        return cell.occupancy
    }

    /**
     * Highest occupancy of visited cells along [headingRad] (0 = north)
     * out to [lookM]. Current cell is included. Null when no visited cell
     * lies on the look-ahead. Missing C/N0 is not treated as occupancy 0.
     */
    fun occupancyAhead(
        latitudeDeg: Double,
        longitudeDeg: Double,
        headingRad: Double,
        lookM: Double = BlackoutRisk.MAX_LOOK_M,
        stepM: Double = cellSizeM,
    ): Double? {
        if (!headingRad.isFinite()) {
            return null
        }
        if (!lookM.isFinite() || lookM < 0.0) {
            return null
        }
        if (!stepM.isFinite() || stepM <= 0.0) {
            return null
        }
        var best = occupancy(latitudeDeg, longitudeDeg)
        var walked = stepM
        while (walked <= lookM) {
            val northM = walked * cos(headingRad)
            val eastM = walked * sin(headingRad)
            val (lat, lon) = Wgs84.offsetMetres(latitudeDeg, longitudeDeg, northM, eastM)
            val occ = occupancy(lat, lon)
            if (occ != null) {
                best = if (best == null) occ else max(best, occ)
            }
            walked += stepM
        }
        return best
    }

    fun cell(latitudeDeg: Double, longitudeDeg: Double): ShadowCell? =
        cells[cellKey(latitudeDeg, longitudeDeg)]

    fun toJson(): String {
        val rows = ArrayList<Map<String, Any?>>(cells.size)
        for ((key, cell) in cells) {
            rows.add(
                linkedMapOf(
                    "i" to key.northIndex.toLong(),
                    "j" to key.eastIndex.toLong(),
                    "visits" to cell.visits.toLong(),
                    "losses" to cell.losses.toLong(),
                    "sum_acc_m" to cell.sumAccuracyM,
                    "acc_n" to cell.accuracyCount.toLong(),
                    "sum_cn0" to cell.sumCn0DbHz,
                    "cn0_n" to cell.cn0Count.toLong(),
                ),
            )
        }
        return ContractJson.stringify(
            linkedMapOf(
                "schema_version" to SCHEMA_VERSION,
                "cell_m" to cellSizeM,
                "cells" to rows,
            ),
        )
    }

    companion object {
        const val CELL_M: Double = 50.0
        const val SCHEMA_VERSION: String = "shadow_map_1.0.0"
        const val METRES_PER_DEG_LAT: Double = 111_320.0
        private val EMPTY: ShadowCell = ShadowCell(0, 0, 0.0, 0, 0.0, 0)

        fun fromJson(json: String): ShadowMap {
            val map = ContractJson.parseObject(json)
            val schema = ContractJson.asString(map["schema_version"], "schema_version")
            require(schema == SCHEMA_VERSION) { "unsupported shadow map schema '$schema'" }
            val cellM = ContractJson.asDouble(map["cell_m"], "cell_m")
            val out = ShadowMap(cellM)
            val raw = map["cells"] as? List<*> ?: return out
            for (item in raw) {
                val obj = ContractJson.asObject(item, "cells[]")
                val key = ShadowCellKey(
                    northIndex = ContractJson.asLong(obj["i"], "i").toInt(),
                    eastIndex = ContractJson.asLong(obj["j"], "j").toInt(),
                )
                out.cells[key] = ShadowCell(
                    visits = ContractJson.asLong(obj["visits"], "visits").toInt(),
                    losses = ContractJson.asLong(obj["losses"], "losses").toInt(),
                    sumAccuracyM = ContractJson.asDouble(obj["sum_acc_m"], "sum_acc_m"),
                    accuracyCount = ContractJson.asLong(obj["acc_n"], "acc_n").toInt(),
                    sumCn0DbHz = ContractJson.asDouble(obj["sum_cn0"], "sum_cn0"),
                    cn0Count = ContractJson.asLong(obj["cn0_n"], "cn0_n").toInt(),
                )
            }
            return out
        }
    }
}

/**
 * Persist [ShadowMap] as JSON. [file] null is memory only (tests).
 * App path is a file under the app files directory, not the cloud.
 */
class ShadowMapStore(
    private val file: File? = null,
) {
    private var memory: ShadowMap = file?.takeIf { it.exists() }?.let { loadFile(it) } ?: ShadowMap()

    fun current(): ShadowMap = memory

    fun load(): ShadowMap {
        val disk = file
        memory = if (disk != null && disk.exists()) loadFile(disk) else ShadowMap()
        return memory
    }

    fun save(map: ShadowMap = memory) {
        memory = map
        writeText(map.toJson())
    }

    /** Write already-serialized JSON. Caller builds the string off the UI thread. */
    fun writeText(json: String) {
        val disk = file ?: return
        disk.parentFile?.mkdirs()
        disk.writeText(json, Charsets.UTF_8)
    }

    companion object {
        const val FILE_NAME: String = "shadow_map.json"

        private fun loadFile(disk: File): ShadowMap {
            return try {
                ShadowMap.fromJson(disk.readText(Charsets.UTF_8))
            } catch (_: IllegalArgumentException) {
                ShadowMap()
            }
        }
    }
}
