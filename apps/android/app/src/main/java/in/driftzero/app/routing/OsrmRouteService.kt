package `in`.driftzero.app.routing

import `in`.driftzero.app.geo.GeoPoint
import `in`.driftzero.app.net.HttpException
import `in`.driftzero.app.net.TextGetter

class OsrmRouteService(
    private val http: TextGetter,
    private val baseUrl: String = "https://router.project-osrm.org",
) {
    fun drivingRoute(origin: GeoPoint, destination: GeoPoint): RouteOutcome {
        val url =
            "$baseUrl/route/v1/driving/" +
                "${origin.longitudeDeg},${origin.latitudeDeg};" +
                "${destination.longitudeDeg},${destination.latitudeDeg}" +
                "?overview=full&geometries=polyline"
        return try {
            OsrmParser.parse(http.get(url))
        } catch (error: HttpException) {
            when (error.statusCode) {
                429 -> RouteOutcome.Failed(RouteFailure.RateLimited)
                else -> RouteOutcome.Failed(RouteFailure.Network)
            }
        } catch (_: java.io.IOException) {
            RouteOutcome.Failed(RouteFailure.Network)
        } catch (_: Exception) {
            RouteOutcome.Failed(RouteFailure.Parse)
        }
    }
}
