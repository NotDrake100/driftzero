package `in`.driftzero.app.search

fun readFixture(path: String): String {
    val stream = checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(path)) {
        "missing fixture $path"
    }
    return stream.bufferedReader().use { it.readText() }
}
