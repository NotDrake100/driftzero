package `in`.driftzero.core

/**
 * Public contract JSON for trip files. [ContractJson] stays internal so
 * replay adapters keep one parser. Integer nanoseconds stay [Long].
 */
object ContractWrite {
    fun stringify(value: Any?): String = ContractJson.stringify(value)

    fun parseObject(text: String): Map<String, Any?> = ContractJson.parseObject(text)

    fun sensorLine(frame: SensorFrame): String = stringify(ContractMaps.sensorFrame(frame))

    fun stateLine(state: NavigationState): String = stringify(ContractMaps.navigationState(state))
}
