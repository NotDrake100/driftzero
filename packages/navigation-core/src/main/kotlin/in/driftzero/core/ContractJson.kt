package `in`.driftzero.core

/**
 * Deterministic JSON for contract maps. Integers stay [Long] so
 * `timestamp_ns` (dataset Unix nanoseconds) is not rounded through [Double].
 * [LinearDpJson] is a separate weight-file reader and always yields Doubles.
 */
internal object ContractJson {
    fun parseObject(text: String): Map<String, Any?> {
        val cursor = Cursor(text)
        val value = cursor.parseValue()
        cursor.skipWs()
        if (!cursor.done) {
            throw IllegalArgumentException("trailing JSON after object")
        }
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
            ?: throw IllegalArgumentException("JSON root must be an object")
    }

    fun stringify(value: Any?): String {
        val out = StringBuilder()
        write(out, value)
        return out.toString()
    }

    fun asLong(value: Any?, field: String): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> {
            require(value.isFinite()) { "$field must be a finite integer" }
            val asLong = value.toLong()
            require(asLong.toDouble() == value) { "$field must be an integer" }
            asLong
        }
        else -> throw IllegalArgumentException("$field must be an integer")
    }

    fun asDouble(value: Any?, field: String): Double = when (value) {
        is Double -> {
            require(value.isFinite()) { "$field must be finite" }
            value
        }
        is Long -> value.toDouble()
        is Int -> value.toDouble()
        else -> throw IllegalArgumentException("$field must be a number")
    }

    fun asBoolean(value: Any?, field: String): Boolean {
        require(value is Boolean) { "$field must be a boolean" }
        return value
    }

    fun asString(value: Any?, field: String): String {
        require(value is String) { "$field must be a string" }
        return value
    }

    fun asObject(value: Any?, field: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
            ?: throw IllegalArgumentException("$field must be an object")
    }

    fun asStringList(value: Any?, field: String): List<String> {
        val raw = value as? List<*> ?: throw IllegalArgumentException("$field must be an array")
        val out = ArrayList<String>(raw.size)
        val seen = HashSet<String>()
        for (item in raw) {
            require(item is String) { "$field must contain strings" }
            require(seen.add(item)) { "$field must not contain duplicates" }
            out.add(item)
        }
        return out
    }

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is Long, is Int, is Short, is Byte -> out.append(value.toString())
            is Double -> {
                require(value.isFinite()) { "JSON number must be finite" }
                out.append(value.toString())
            }
            is Float -> write(out, value.toDouble())
            is String -> writeString(out, value)
            is Map<*, *> -> writeObject(out, value)
            is Iterable<*> -> writeArray(out, value)
            else -> throw IllegalArgumentException("cannot encode ${value::class.simpleName}")
        }
    }

    private fun writeObject(out: StringBuilder, value: Map<*, *>) {
        out.append('{')
        var first = true
        for ((key, cell) in value) {
            require(key is String) { "JSON object keys must be strings" }
            if (cell == null) {
                continue
            }
            if (!first) {
                out.append(',')
            }
            first = false
            writeString(out, key)
            out.append(':')
            write(out, cell)
        }
        out.append('}')
    }

    private fun writeArray(out: StringBuilder, value: Iterable<*>) {
        out.append('[')
        var first = true
        for (cell in value) {
            if (!first) {
                out.append(',')
            }
            first = false
            write(out, cell)
        }
        out.append(']')
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) {
                    out.append("\\u")
                    out.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }

    private class Cursor(private val s: String) {
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

        private fun parseNumber(): Any {
            val start = i
            if (peek('-')) {
                i++
            }
            if (done || s[i] !in '0'..'9') {
                throw IllegalArgumentException("invalid number")
            }
            if (s[i] == '0') {
                i++
            } else {
                while (i < s.length && s[i] in '0'..'9') {
                    i++
                }
            }
            var fractional = false
            if (peek('.')) {
                fractional = true
                i++
                if (i >= s.length || s[i] !in '0'..'9') {
                    throw IllegalArgumentException("invalid number")
                }
                while (i < s.length && s[i] in '0'..'9') {
                    i++
                }
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                fractional = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) {
                    i++
                }
                if (i >= s.length || s[i] !in '0'..'9') {
                    throw IllegalArgumentException("invalid number")
                }
                while (i < s.length && s[i] in '0'..'9') {
                    i++
                }
            }
            val raw = s.substring(start, i)
            return if (fractional) {
                raw.toDouble()
            } else {
                raw.toLong()
            }
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

        private fun peek(c: Char): Boolean = i < s.length && s[i] == c
    }
}
