package `in`.driftzero.app.ui

/**
 * Phone evidence facts. Default About must not show these numbers.
 * About Lab (title tapped [LAB_UNLOCK_TAPS] times) may. On-screen sentences live in strings.xml.
 * Numbers match the leak-free kotlin_eskf_v5 row and persist on IO-VNBD.
 */
object EvidenceCopy {
    const val LAB_UNLOCK_TAPS = 5
    const val PERSIST_DRIFT_P50 = "0.5168"
    const val GATE = "0.10"
    const val KOTLIN_V5_DRIFT_P50 = "0.611"
    const val KOTLIN_V5_ENDPOINT_M = "187.5"
    const val KOTLIN_V5_1HZ = "0.257"
    const val LEAKY_KOTLIN_DRIFT = "0.541"

    fun gateMetOnIoVnbd(): Boolean = false

    fun timesFmOnPhone(): Boolean = false

    fun persistHeadline(): String = "persist $PERSIST_DRIFT_P50"

    fun gateLine(): String = "gate $GATE not met on IO-VNBD"

    fun timesFmLine(): String = "TimesFM is not on the phone"

    fun kotlinV5LeakFreeLine(): String =
        "kotlin_eskf_v5 leak-free: drift p50 $KOTLIN_V5_DRIFT_P50, endpoint p50 $KOTLIN_V5_ENDPOINT_M m, 1 Hz $KOTLIN_V5_1HZ"

    fun screeningHeadline(): String = "${persistHeadline()}. ${gateLine()}."

    fun screeningBody(): String =
        "Held-out persist drift p50 is $PERSIST_DRIFT_P50 on 35 intervals. " +
            "Official gate $GATE is not met on IO-VNBD. " +
            "${kotlinV5LeakFreeLine()}. " +
            "Do not cite leaky-frame $LEAKY_KOTLIN_DRIFT as the headline."

    fun judgeScoreOnlyLine(): String = "Score-only overlay. Hold GNSS is not a tunnel."

    /** Leaky 0.541 may appear only as a banned citation, never as the kotlin headline. */
    fun citesLeakyKotlinAsHeadline(text: String): Boolean {
        if (!text.contains(LEAKY_KOTLIN_DRIFT)) {
            return false
        }
        val beforeBan = text.substringBefore("Do not cite", missingDelimiterValue = text)
        return beforeBan.contains(LEAKY_KOTLIN_DRIFT)
    }
}
