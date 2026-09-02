package `in`.driftzero.app.net

const val USER_AGENT = "DriftZero/0.1"

class HttpException(val statusCode: Int, message: String) : Exception(message)

fun interface TextGetter {
    fun get(url: String): String
}

fun encodeQuery(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
