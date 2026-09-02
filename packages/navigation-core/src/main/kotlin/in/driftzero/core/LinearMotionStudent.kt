package `in`.driftzero.core

/**
 * Export schema for `models/motion_student_v1/linear.json`.
 * Written by `python -m driftzero_ml.student.train`. TimesFM is not a field.
 * Speed MAE on held-out IO-VNBD beats freeze. This is not the Δp student.
 */
object LinearSpeedConstants {
    const val SCHEMA: String = "driftzero.linear_speed.v1"
    const val LOG_VAR_MIN: Double = -8.0
    const val LOG_VAR_MAX: Double = 6.0
}

/**
 * RIDI-shaped linear speed / stop / log-variance student. Stdlib ridge
 * weights from Python. Missing or GNSS feature names skip the APK load.
 */
class LinearMotionStudent(
    val featureNames: List<String>,
    weightsSpeed: DoubleArray,
    weightsStop: DoubleArray,
    weightsLogvar: DoubleArray,
) : MotionModel {
    private val weightsSpeed: DoubleArray = weightsSpeed.copyOf()
    private val weightsStop: DoubleArray = weightsStop.copyOf()
    private val weightsLogvar: DoubleArray = weightsLogvar.copyOf()

    init {
        require(featureNames == CausalImuFeatures.FEATURE_NAMES) {
            "linear student feature_names must match CausalImuFeatures.FEATURE_NAMES"
        }
        val dim = featureNames.size + 1
        require(this.weightsSpeed.size == dim) { "weights_speed must have length $dim" }
        require(this.weightsStop.size == dim) { "weights_stop must have length $dim" }
        require(this.weightsLogvar.size == dim) { "weights_logvar must have length $dim" }
        require(this.weightsSpeed.all { it.isFinite() }) { "weights_speed must be finite" }
        require(this.weightsStop.all { it.isFinite() }) { "weights_stop must be finite" }
        require(this.weightsLogvar.all { it.isFinite() }) { "weights_logvar must be finite" }
        require(featureNames.none { it in GNSS_FEATURE_BAN }) {
            "linear student feature_names must not include GNSS fields"
        }
    }

    override fun infer(window: CausalImuWindow): MotionPseudoMeasurement {
        require(window.samples.isNotEmpty()) { "IMU window must not be empty" }
        val features = CausalImuFeatures.extract(window)
        val rawSpeed = dot(weightsSpeed, features.vector)
        val stopLogit = dot(weightsStop, features.vector)
        val logVar = dot(weightsLogvar, features.vector).coerceIn(
            LinearSpeedConstants.LOG_VAR_MIN,
            LinearSpeedConstants.LOG_VAR_MAX,
        )
        val speed = softplus(rawSpeed).coerceIn(0.0, ImuMotionConstants.MAX_SPEED_MPS)
        val stopProbability = logistic(stopLogit).coerceIn(0.0, 1.0)
        val idle = stopProbability >= ImuMotionConstants.IDLE_SCORE_GATE
        return MotionPseudoMeasurement(
            forwardSpeed = MetresPerSecond(speed),
            yawRateRadps = features.gyroZMean,
            stopProbability = stopProbability,
            logSpeedVariance = logVar,
            vibrationEnergy = features.vibrationEnergy,
            idle = idle,
            bump = features.bump,
        )
    }

    companion object {
        fun parse(json: String): LinearMotionStudent {
            val root = LinearDpJson.parseObject(json)
            val schema = root["schema"] as? String
            if (schema != null && schema != LinearSpeedConstants.SCHEMA) {
                throw IllegalArgumentException("unsupported linear speed schema: $schema")
            }
            val names = stringList(root["feature_names"], "feature_names")
            if (names.any { it in GNSS_FEATURE_BAN }) {
                throw IllegalArgumentException("linear student feature_names must not include GNSS fields")
            }
            return LinearMotionStudent(
                featureNames = names,
                weightsSpeed = numberList(root["weights_speed"], "weights_speed"),
                weightsStop = numberList(root["weights_stop"], "weights_stop"),
                weightsLogvar = numberList(root["weights_logvar"], "weights_logvar"),
            )
        }
    }
}
