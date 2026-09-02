package `in`.driftzero.app.search

fun readFixture(path: String): String {
    val loader = checkNotNull(Thread.currentThread().contextClassLoader) { "missing classloader" }
    val stream = checkNotNull(loader.getResourceAsStream(path)) { "missing fixture $path" }
    return stream.bufferedReader().use { it.readText() }
}
