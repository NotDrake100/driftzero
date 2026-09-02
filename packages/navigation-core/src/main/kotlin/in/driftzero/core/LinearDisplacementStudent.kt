package `in`.driftzero.core

import kotlin.math.exp
import kotlin.math.ln

/**
 * Export schema for `models/learned_imu_v1/linear_dp.json`.
 * Written by `python -m driftzero_ml.learned_imu`. TimesFM is not a field.
 */
object LinearDpConstants {
    const val SCHEMA: String = "driftzero.linear_dp.v1"
    const val HEAD_DIM: Int = 10
    const val LOG_SIGMA_MIN: Double = -8.0
    const val LOG_SIGMA_MAX: Double = 6.0
    const val MAX_SPEED_MPS: Double = 50.0

    /** TLIO χ² 99th percentile, 3 dof (Liu et al., RA-L 2020). */
    const val CHI2_99_3DOF: Double = 11.345

    /** TLIO overlapping-window covariance inflation. */
    const val OVERLAP_R_SCALE: Double = 10.0

    val FEATURE_NAMES: List<String> = CausalImuFeatures.FEATURE_NAMES + listOf(
        "ax_hacf_mean",
        "ay_hacf_mean",
        "az_hacf_mean",
    )
    val HEAD_NAMES: List<String> = listOf(
        "ronin_dx_m",
        "ronin_dy_m",
        "tlio_dz_m",
        "tlio_log_sigma_x",
        "tlio_log_sigma_y",
        "tlio_log_sigma_z",
        "speed_raw",
        "stop_logit",
        "log_speed_variance",
        "ionet_dpsi_rad",
    )
}

/**
 * RIDI-shaped shallow Δp + log σ. Stdlib ridge weights from Python.
 * No ONNX Runtime. Missing or unknown feature names skip inference.
 */
class LinearDisplacementStudent(
    val featureNames: List<String>,
    weights: List<DoubleArray>,
) : DisplacementModel {
    private val weights: Array<DoubleArray> = Array(weights.size) { index ->
        weights[index].copyOf()
    }

    init {
        require(featureNames.isNotEmpty()) { "feature_names must not be empty" }
        require(this.weights.size == LinearDpConstants.HEAD_DIM) {
            "weights must have ${LinearDpConstants.HEAD_DIM} heads"
        }
        val dim = featureNames.size + 1
        for (row in this.weights) {
            require(row.size == dim) { "each head must have $dim weights (bias + features)" }
            require(row.all { it.isFinite() }) { "weights must be finite" }
        }
    }

    override fun infer(window: CausalImuWindow): DisplacementPseudoMeasurement? {
        if (window.samples.size < ImuMotionConstants.MIN_SAMPLES) {
            return null
        }
        val vector = featureVector(window) ?: return null
        val raw = DoubleArray(LinearDpConstants.HEAD_DIM) { head ->
            dot(this.weights[head], vector)
        }
        val heads = decodeRawHeads(raw)
        return DisplacementPseudoMeasurement(
            dxM = heads.dxM,
            dyM = heads.dyM,
            dzM = heads.dzM,
            logSigmaX = heads.logSigmaX,
            logSigmaY = heads.logSigmaY,
            logSigmaZ = heads.logSigmaZ,
            windowStart = window.samples.first().timestamp,
        )
    }

    fun featureVector(window: CausalImuWindow): DoubleArray? {
        val byName = computedFeatures(window)
        val out = DoubleArray(featureNames.size)
        for (index in featureNames.indices) {
            val value = byName[featureNames[index]] ?: return null
            if (!value.isFinite()) {
                return null
            }
            out[index] = value
        }
        return out
    }

    companion object {
        fun parse(json: String): LinearDisplacementStudent {
            val root = LinearDpJson.parseObject(json)
            val schema = root["schema"] as? String
            if (schema != null && schema != LinearDpConstants.SCHEMA) {
                throw IllegalArgumentException("unsupported linear_dp schema: $schema")
            }
            val names = stringList(root["feature_names"], "feature_names")
            val weightRows = numberMatrix(root["weights"], "weights")
            if (names.any { it in GNSS_FEATURE_BAN }) {
                throw IllegalArgumentException("linear_dp feature_names must not include GNSS fields")
            }
            return LinearDisplacementStudent(names, weightRows)
        }
    }
}

internal data class LearnedImuHeads(
    val dxM: Double,
    val dyM: Double,
    val dzM: Double,
    val logSigmaX: Double,
    val logSigmaY: Double,
    val logSigmaZ: Double,
    val forwardSpeedMps: Double,
    val stopLogit: Double,
    val logSpeedVariance: Double,
    val deltaHeadingRad: Double,
)

internal fun decodeRawHeads(raw: DoubleArray): LearnedImuHeads {
    require(raw.size == LinearDpConstants.HEAD_DIM) { "raw heads must have length ${LinearDpConstants.HEAD_DIM}" }
    return LearnedImuHeads(
        dxM = raw[0],
        dyM = raw[1],
        dzM = raw[2],
        logSigmaX = raw[3].coerceIn(LinearDpConstants.LOG_SIGMA_MIN, LinearDpConstants.LOG_SIGMA_MAX),
        logSigmaY = raw[4].coerceIn(LinearDpConstants.LOG_SIGMA_MIN, LinearDpConstants.LOG_SIGMA_MAX),
        logSigmaZ = raw[5].coerceIn(LinearDpConstants.LOG_SIGMA_MIN, LinearDpConstants.LOG_SIGMA_MAX),
        forwardSpeedMps = softplus(raw[6]).coerceIn(0.0, LinearDpConstants.MAX_SPEED_MPS),
        stopLogit = raw[7],
        logSpeedVariance = raw[8].coerceIn(LinearDpConstants.LOG_SIGMA_MIN, LinearDpConstants.LOG_SIGMA_MAX),
        deltaHeadingRad = raw[9],
    )
}

internal fun computedFeatures(window: CausalImuWindow): Map<String, Double> {
    val pooled = CausalImuFeatures.extract(window)
    val sequence = hacfSixAxis(window.samples)
    val ax = mean(sequence.map { it[0] })
    val ay = mean(sequence.map { it[1] })
    val az = mean(sequence.map { it[2] })
    val out = LinkedHashMap<String, Double>(LinearDpConstants.FEATURE_NAMES.size)
    for (index in CausalImuFeatures.FEATURE_NAMES.indices) {
        out[CausalImuFeatures.FEATURE_NAMES[index]] = pooled.vector[index]
    }
    out["ax_hacf_mean"] = ax
    out["ay_hacf_mean"] = ay
    out["az_hacf_mean"] = az
    return out
}

internal fun dot(weights: DoubleArray, vector: DoubleArray): Double {
    require(weights.size == vector.size + 1)
    var acc = weights[0]
    for (index in vector.indices) {
        acc += weights[index + 1] * vector[index]
    }
    return acc
}

internal fun softplus(value: Double): Double {
    if (value > 40.0) {
        return value
    }
    return ln(1.0 + exp(value))
}

internal fun logistic(value: Double): Double {
    if (value > 40.0) {
        return 1.0
    }
    if (value < -40.0) {
        return 0.0
    }
    return 1.0 / (1.0 + exp(-value))
}

internal fun stringList(value: Any?, field: String): List<String> {
    val rows = value as? List<*> ?: throw IllegalArgumentException("$field must be a string array")
    if (rows.isEmpty()) {
        throw IllegalArgumentException("$field must not be empty")
    }
    return rows.map { item ->
        item as? String ?: throw IllegalArgumentException("$field entries must be strings")
    }
}

internal fun numberList(value: Any?, field: String): DoubleArray {
    val rows = value as? List<*> ?: throw IllegalArgumentException("$field must be an array")
    if (rows.isEmpty()) {
        throw IllegalArgumentException("$field must not be empty")
    }
    return DoubleArray(rows.size) { index ->
        rows[index] as? Double ?: throw IllegalArgumentException("$field must be numeric")
    }
}

private fun numberMatrix(value: Any?, field: String): List<DoubleArray> {
    val rows = value as? List<*> ?: throw IllegalArgumentException("$field must be an array")
    if (rows.isEmpty()) {
        throw IllegalArgumentException("$field must not be empty")
    }
    return rows.map { row ->
        val cells = row as? List<*> ?: throw IllegalArgumentException("$field rows must be arrays")
        DoubleArray(cells.size) { index ->
            val n = cells[index] as? Double ?: throw IllegalArgumentException("$field must be numeric")
            n
        }
    }
}

/**
 * Minimal JSON reader for `linear_dp.json`. Objects, arrays, strings, numbers,
 * true/false/null. Integers become Double so weight matrices stay numeric.
 */
internal object LinearDpJson {
    fun parseObject(text: String): Map<String, Any?> {
        val cursor = JsonCursor(text)
        val value = cursor.parseValue()
        cursor.skipWs()
        if (!cursor.done) {
            throw IllegalArgumentException("trailing JSON after linear_dp object")
        }
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: throw IllegalArgumentException("linear_dp root must be an object")
    }

    private class JsonCursor(private val s: String) {
        var i: Int = 0
        val done: Boolean get() = i >= s.length

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) {
                i++
            }
        }

        fun parseValue(): Any? {
            skipWs()
            if (done) {
                throw IllegalArgumentException("unexpected end of JSON")
            }
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("unexpected JSON at $i ($c)")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek('}')) {
                i++
                return out
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                out[key] = parseValue()
                skipWs()
                when {
                    peek(',') -> i++
                    peek('}') -> {
                        i++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected , or } in object")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWs()
            if (peek(']')) {
                i++
                return out
            }
            while (true) {
                out.add(parseValue())
                skipWs()
                when {
                    peek(',') -> i++
                    peek(']') -> {
                        i++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected , or ] in array")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val buf = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return buf.toString()
                    '\\' -> {
                        if (i >= s.length) {
                            throw IllegalArgumentException("unterminated escape")
                        }
                        buf.append(
                            when (val e = s[i++]) {
                                '"', '\\', '/' -> e
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'u' -> parseHexChar()
                                else -> throw IllegalArgumentException("bad escape")
                            },
                        )
                    }
                    else -> buf.append(c)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun parseHexChar(): Char {
            if (i + 4 > s.length) {
                throw IllegalArgumentException("bad unicode escape")
            }
            val hex = s.substring(i, i + 4)
            i += 4
            return hex.toInt(16).toChar()
        }

        private fun parseNumber(): Double {
            val start = i
            if (peek('-')) {
                i++
            }
            while (i < s.length && s[i] in '0'..'9') {
                i++
            }
            if (peek('.')) {
                i++
                while (i < s.length && s[i] in '0'..'9') {
                    i++
                }
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) {
                    i++
                }
                while (i < s.length && s[i] in '0'..'9') {
                    i++
                }
            }
            return s.substring(start, i).toDouble()
        }

        private fun parseLiteral(token: String, value: Any?): Any? {
            if (i + token.length > s.length || s.substring(i, i + token.length) != token) {
                throw IllegalArgumentException("expected $token")
            }
            i += token.length
            return value
        }

        private fun expect(c: Char) {
            skipWs()
            if (done || s[i] != c) {
                throw IllegalArgumentException("expected $c")
            }
            i++
        }

        private fun peek(c: Char): Boolean {
            skipWs()
            return i < s.length && s[i] == c
        }
    }
}
