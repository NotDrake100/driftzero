package `in`.driftzero.app.ui

internal sealed class JsonVal {
    data class Obj(val map: Map<String, JsonVal>) : JsonVal()
    data class Arr(val items: List<JsonVal>) : JsonVal()
    data class Str(val value: String) : JsonVal()
    data class Num(val value: Double) : JsonVal()
    data class Bool(val value: Boolean) : JsonVal()
    data object Null : JsonVal()

    fun at(key: String): JsonVal? = (this as? Obj)?.map?.get(key)

    fun arr(): List<JsonVal>? = (this as? Arr)?.items

    fun str(): String? = (this as? Str)?.value

    fun num(): Double? = (this as? Num)?.value

    fun numOrStr(): Double? = num() ?: str()?.toDoubleOrNull()
}

internal fun parseJson(src: String): JsonVal? {
    return try {
        JsonParse(src).readValue()
    } catch (_: Exception) {
        null
    }
}

internal fun parsePhotonPlaces(body: String): List<TravelPlace>? {
    val root = parseJson(body) ?: return null
    val features = root.at("features")?.arr() ?: return null
    val places = ArrayList<TravelPlace>(features.size)
    for (feature in features) {
        val coords = feature.at("geometry")?.at("coordinates")?.arr() ?: continue
        val lon = coords.getOrNull(0)?.num() ?: continue
        val lat = coords.getOrNull(1)?.num() ?: continue
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            continue
        }
        val props = feature.at("properties") ?: JsonVal.Obj(emptyMap())
        val name = props.at("name")?.str()?.takeIf { it.isNotBlank() }
            ?: props.at("street")?.str()?.takeIf { it.isNotBlank() }
            ?: continue
        val detail = listOf(
            props.at("street")?.str(),
            props.at("city")?.str(),
            props.at("state")?.str(),
            props.at("country")?.str(),
        ).filterNotNull().filter { it.isNotBlank() && it != name }.distinct()
            .joinToString(", ")
        places += TravelPlace(
            name = name,
            detail = detail,
            latitudeDeg = lat,
            longitudeDeg = lon,
        )
    }
    return places
}

internal fun parseOsrmRoute(body: String): TravelRoute? {
    val root = parseJson(body) ?: return null
    if (root.at("code")?.str() != "Ok") {
        return null
    }
    val route = root.at("routes")?.arr()?.firstOrNull() ?: return null
    val distance = route.at("distance")?.num() ?: return null
    val duration = route.at("duration")?.num() ?: return null
    if (!distance.isFinite() || distance < 0.0 || !duration.isFinite() || duration < 0.0) {
        return null
    }
    val coords = route.at("geometry")?.at("coordinates")?.arr() ?: return null
    val points = ArrayList<TravelLatLng>(coords.size)
    for (item in coords) {
        val pair = item.arr() ?: continue
        val lon = pair.getOrNull(0)?.num() ?: continue
        val lat = pair.getOrNull(1)?.num() ?: continue
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            continue
        }
        points += TravelLatLng(latitudeDeg = lat, longitudeDeg = lon)
    }
    if (points.size < 2) {
        return null
    }
    return TravelRoute(points = points, distanceM = distance, durationS = duration)
}

internal fun parseNominatimPlaces(body: String): List<TravelPlace>? {
    val root = parseJson(body) ?: return null
    val items = root.arr() ?: return null
    val places = ArrayList<TravelPlace>(items.size)
    for (item in items) {
        val lat = item.at("lat")?.numOrStr() ?: continue
        val lon = item.at("lon")?.numOrStr() ?: continue
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            continue
        }
        val display = item.at("display_name")?.str()?.takeIf { it.isNotBlank() }
        val name = item.at("name")?.str()?.takeIf { it.isNotBlank() }
            ?: display?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
            ?: continue
        val detail = display
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it != name }
            ?.take(3)
            ?.joinToString(", ")
            .orEmpty()
        places += TravelPlace(
            name = name,
            detail = detail,
            latitudeDeg = lat,
            longitudeDeg = lon,
        )
    }
    return places
}

private class JsonParse(private val src: String) {
    private var i = 0

    fun readValue(): JsonVal {
        skipWs()
        if (i >= src.length) {
            error("empty")
        }
        return when (src[i]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonVal.Str(readString())
            't' -> {
                expect("true")
                JsonVal.Bool(true)
            }
            'f' -> {
                expect("false")
                JsonVal.Bool(false)
            }
            'n' -> {
                expect("null")
                JsonVal.Null
            }
            '-', in '0'..'9' -> JsonVal.Num(readNumber())
            else -> error("value at $i")
        }
    }

    private fun readObject(): JsonVal.Obj {
        expect('{')
        val map = LinkedHashMap<String, JsonVal>()
        skipWs()
        if (peek() == '}') {
            i++
            return JsonVal.Obj(map)
        }
        while (true) {
            skipWs()
            val key = readString()
            skipWs()
            expect(':')
            map[key] = readValue()
            skipWs()
            when (peek()) {
                ',' -> i++
                '}' -> {
                    i++
                    return JsonVal.Obj(map)
                }
                else -> error("object at $i")
            }
        }
    }

    private fun readArray(): JsonVal.Arr {
        expect('[')
        val items = ArrayList<JsonVal>()
        skipWs()
        if (peek() == ']') {
            i++
            return JsonVal.Arr(items)
        }
        while (true) {
            items += readValue()
            skipWs()
            when (peek()) {
                ',' -> i++
                ']' -> {
                    i++
                    return JsonVal.Arr(items)
                }
                else -> error("array at $i")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (i < src.length) {
            val c = src[i++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> {
                    if (i >= src.length) {
                        error("escape")
                    }
                    when (val e = src[i++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 4 > src.length) {
                                error("unicode")
                            }
                            val hex = src.substring(i, i + 4)
                            out.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> error("escape $e")
                    }
                }
                else -> out.append(c)
            }
        }
        error("unterminated string")
    }

    private fun readNumber(): Double {
        val start = i
        if (peek() == '-') {
            i++
        }
        while (i < src.length && src[i] in '0'..'9') {
            i++
        }
        if (i < src.length && src[i] == '.') {
            i++
            while (i < src.length && src[i] in '0'..'9') {
                i++
            }
        }
        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
            i++
            if (i < src.length && (src[i] == '+' || src[i] == '-')) {
                i++
            }
            while (i < src.length && src[i] in '0'..'9') {
                i++
            }
        }
        return src.substring(start, i).toDouble()
    }

    private fun expect(token: String) {
        skipWs()
        if (!src.startsWith(token, i)) {
            error("expected $token at $i")
        }
        i += token.length
    }

    private fun expect(c: Char) {
        skipWs()
        if (i >= src.length || src[i] != c) {
            error("expected $c at $i")
        }
        i++
    }

    private fun peek(): Char {
        skipWs()
        if (i >= src.length) {
            error("eof")
        }
        return src[i]
    }

    private fun skipWs() {
        while (i < src.length && src[i].isWhitespace()) {
            i++
        }
    }
}
