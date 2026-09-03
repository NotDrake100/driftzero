package `in`.driftzero.app.ui

import `in`.driftzero.core.GuidanceRoute
import `in`.driftzero.core.ManeuverModifier
import `in`.driftzero.core.ManeuverType
import `in`.driftzero.core.RoutePoint
import `in`.driftzero.core.RouteStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelSearchTest {
    @Test
    fun photonUrlEncodesQueryAndOptionalBias() {
        val url = photonUrl("pune station", 18.52, 73.85)
        assertTrue(url.startsWith(StreetMapConfig.PHOTON_API))
        assertTrue(url.contains("q=pune+station") || url.contains("q=pune%20station"))
        assertTrue(url.contains("lat=18.52"))
        assertTrue(url.contains("lon=73.85"))
        assertTrue(url.contains("zoom=14"))
        assertTrue(url.contains("location_bias_scale=0.1"))
        assertFalse(url.contains("SIH"))
        assertFalse(url.contains("LastKnown"))
    }

    @Test
    fun photonUrlOmitsBiasWhenUnknown() {
        val url = photonUrl("starbucks", null, null)
        assertTrue(url.startsWith(StreetMapConfig.PHOTON_API))
        assertFalse(url.contains("lat="))
        assertFalse(url.contains("lon="))
        assertFalse(url.contains("zoom="))
        assertFalse(url.contains("location_bias_scale"))
        assertFalse(url.contains("18.5362"))
        assertFalse(url.contains("73.8938"))
        assertFalse(url.contains("Pune"))
    }

    @Test
    fun nominatimUrlBiasesWithViewboxNotBounded() {
        val url = nominatimUrl("starbucks", 40.758, -73.985)
        assertTrue(url.startsWith(StreetMapConfig.NOMINATIM_SEARCH))
        assertTrue(url.contains("viewbox="))
        assertTrue(url.contains("bounded=0"))
        assertFalse(url.contains("bounded=1"))
        assertFalse(url.contains("18.5362"))
        assertFalse(url.contains("Pune"))
    }

    @Test
    fun osrmUrlUsesLonLatOrder() {
        val url = osrmUrl(TravelLatLng(18.5, 73.8), TravelLatLng(18.6, 73.9))
        assertTrue(url.startsWith(StreetMapConfig.OSRM_ROUTE))
        assertTrue(url.contains("73.8,18.5;73.9,18.6"))
        assertTrue(url.contains("geometries=geojson"))
        assertTrue(url.contains("steps=true"))
    }

    @Test
    fun photonParserReadsNameAndCoordinates() {
        val body = """
            {"type":"FeatureCollection","features":[{
              "geometry":{"type":"Point","coordinates":[73.8744,18.5285]},
              "properties":{"name":"Pune Station","city":"Pune","state":"Maharashtra","country":"India"}
            }]}
        """.trimIndent()
        val places = parsePhotonPlaces(body)!!
        assertEquals(1, places.size)
        assertEquals("Pune Station", places[0].name)
        assertEquals(18.5285, places[0].latitudeDeg, 0.00001)
        assertEquals(73.8744, places[0].longitudeDeg, 0.00001)
        assertTrue(places[0].detail.contains("Pune"))
    }

    @Test
    fun photonParserSkipsMissingCoordinates() {
        val body = """{"type":"FeatureCollection","features":[{"properties":{"name":"Nowhere"}}]}"""
        val places = parsePhotonPlaces(body)!!
        assertTrue(places.isEmpty())
    }

    @Test
    fun osrmParserReadsLineAndMetrics() {
        val body = """
            {"code":"Ok","routes":[{"distance":12400.0,"duration":1080.0,
              "geometry":{"type":"LineString","coordinates":[[73.8,18.5],[73.85,18.52],[73.9,18.6]]}}]}
        """.trimIndent()
        val route = parseOsrmRoute(body)!!
        assertEquals(12400.0, route.distanceM, 0.01)
        assertEquals(1080.0, route.durationS, 0.01)
        assertEquals(3, route.points.size)
        assertEquals(18.5, route.points[0].latitudeDeg, 0.00001)
        assertEquals(73.8, route.points[0].longitudeDeg, 0.00001)
    }

    @Test
    fun osrmParserRejectsNonOk() {
        assertEquals(null, parseOsrmRoute("""{"code":"NoRoute","routes":[]}"""))
    }

    @Test
    fun searchClientUsesInjectedFetch() {
        val client = TravelSearchClient { url ->
            assertTrue(url.contains("photon.komoot.io"))
            """{"type":"FeatureCollection","features":[{
              "geometry":{"coordinates":[73.8,18.5]},
              "properties":{"name":"Koregaon Park","city":"Pune"}
            }]}"""
        }
        val result = client.search("koregaon", 18.53, 73.89)
        assertTrue(result is PlaceQuery.Hits)
        assertEquals("Koregaon Park", (result as PlaceQuery.Hits).places[0].name)
    }

    @Test
    fun searchClientMapsNetworkFailure() {
        val client = TravelSearchClient { throw TravelHttpException(503) }
        assertEquals(PlaceQuery.Network, client.search("pune", null, null))
    }

    @Test
    fun searchFallsBackToNominatimWhenPhotonEmpty() {
        val client = TravelSearchClient { url ->
            if (url.contains("photon")) {
                """{"type":"FeatureCollection","features":[]}"""
            } else {
                """[{"lat":"40.758","lon":"-73.985","name":"Starbucks","display_name":"Starbucks, New York, United States"}]"""
            }
        }
        val result = client.search("Starbucks", 40.75, -73.98)
        assertTrue(result is PlaceQuery.Hits)
        assertEquals("Starbucks", (result as PlaceQuery.Hits).places[0].name)
        assertEquals(40.758, result.places[0].latitudeDeg, 0.0001)
        assertEquals(-73.985, result.places[0].longitudeDeg, 0.0001)
    }

    @Test
    fun nominatimParserReadsStringCoordinates() {
        val places = parseNominatimPlaces(
            """[{"lat":"51.5074","lon":"-0.1278","name":"Trafalgar Square","display_name":"Trafalgar Square, London, UK"}]""",
        )!!
        assertEquals(1, places.size)
        assertEquals("Trafalgar Square", places[0].name)
        assertEquals(51.5074, places[0].latitudeDeg, 0.0001)
        assertTrue(places[0].detail.contains("London"))
    }

    @Test
    fun shortQueryDoesNotFetch() {
        var fetched = false
        val client = TravelSearchClient {
            fetched = true
            "{}"
        }
        assertEquals(PlaceQuery.Empty, client.search("p", null, null))
        assertFalse(fetched)
    }

    @Test
    fun searchAndRouteSkipFetchWhenOffline() {
        var fetched = false
        val client = TravelSearchClient(
            online = { false },
            fetch = {
                fetched = true
                "{}"
            },
        )
        assertEquals(PlaceQuery.Network, client.search("pune station", null, null))
        assertEquals(
            RouteQuery.Network,
            client.route(TravelLatLng(18.5, 73.8), TravelLatLng(18.6, 73.9)),
        )
        assertFalse(fetched)
    }

    @Test
    fun localMissWhileOfflineIsFailedNotNetwork() {
        var fetched = false
        val client = TravelSearchClient(
            online = { false },
            fetch = {
                fetched = true
                "{}"
            },
            localRoute = { _, _ -> null },
        )
        assertEquals(
            RouteQuery.Failed,
            client.route(TravelLatLng(18.5, 73.8), TravelLatLng(18.6, 73.9)),
        )
        assertFalse(fetched)
    }

    @Test
    fun localMissWhileOnlineFallsThroughToOsrm() {
        val client = TravelSearchClient(
            online = { true },
            fetch = {
                """{"code":"Ok","routes":[{"distance":50.0,"duration":8.0,
                  "geometry":{"type":"LineString","coordinates":[[73.8,18.5],[73.9,18.6]]}}]}"""
            },
            localRoute = { _, _ -> null },
        )
        val result = client.route(TravelLatLng(18.5, 73.8), TravelLatLng(18.6, 73.9))
        assertTrue(result is RouteQuery.Ok)
        val ok = result as RouteQuery.Ok
        assertEquals(50.0, ok.route.distanceM, 0.01)
        assertFalse(ok.fromLocal)
    }

    @Test
    fun localRouteIsPreferredOverNetwork() {
        var fetched = false
        val built = GuidanceRoute(
            points = listOf(
                RoutePoint(18.51, 73.85),
                RoutePoint(18.52, 73.86),
            ),
            steps = listOf(
                RouteStep(ManeuverType.DEPART, ManeuverModifier.STRAIGHT, null, null, 100.0, 10.0, 0),
                RouteStep(ManeuverType.ARRIVE, ManeuverModifier.NONE, null, null, 0.0, 0.0, 1),
            ),
            totalDistanceM = 100.0,
            totalDurationS = 10.0,
        )
        val client = TravelSearchClient(
            online = { true },
            fetch = {
                fetched = true
                "{}"
            },
            localRoute = { _, _ -> built },
        )
        val result = client.route(TravelLatLng(18.51, 73.85), TravelLatLng(18.52, 73.86))
        assertTrue(result is RouteQuery.Ok)
        val ok = result as RouteQuery.Ok
        assertEquals(100.0, ok.route.distanceM, 0.01)
        assertTrue(ok.fromLocal)
        assertFalse(fetched)
    }

    @Test
    fun geoJsonBuildersAreLonLat() {
        val line = lineStringGeoJson(
            listOf(TravelLatLng(18.5, 73.8), TravelLatLng(18.6, 73.9)),
        )
        assertTrue(line.contains("[73.8,18.5]"))
        assertTrue(line.contains("LineString"))
        val point = pointGeoJson(TravelLatLng(18.5, 73.8))
        assertTrue(point.contains("[73.8,18.5]"))
        assertTrue(point.contains("Point"))
    }

    @Test
    fun reverseUsesPhotonThenNominatim() {
        val client = TravelSearchClient { url ->
            if (url.contains("photon")) {
                """{"type":"FeatureCollection","features":[{
                  "geometry":{"coordinates":[73.8567,18.5196]},
                  "properties":{"name":"Caring Clinic","city":"Pune"}
                }]}"""
            } else {
                error("nominatim should not run")
            }
        }
        val result = client.reverse(18.5196, 73.8567)
        assertTrue(result is PlaceQuery.Hits)
        assertEquals("Caring Clinic", (result as PlaceQuery.Hits).places[0].name)
    }

    @Test
    fun reverseFallsBackToNominatimObject() {
        val client = TravelSearchClient { url ->
            if (url.contains("photon")) {
                """{"type":"FeatureCollection","features":[]}"""
            } else {
                """{"lat":"18.52","lon":"73.85","name":"Somwar Peth","display_name":"Somwar Peth, Pune"}"""
            }
        }
        val result = client.reverse(18.52, 73.85)
        assertTrue(result is PlaceQuery.Hits)
        assertEquals("Somwar Peth", (result as PlaceQuery.Hits).places[0].name)
    }

    @Test
    fun reverseOfflineIsNetwork() {
        var fetched = false
        val client = TravelSearchClient(
            online = { false },
            fetch = {
                fetched = true
                "{}"
            },
        )
        assertEquals(PlaceQuery.Network, client.reverse(18.52, 73.85))
        assertFalse(fetched)
    }

    @Test
    fun nominatimReverseParserReadsObject() {
        val place = parseNominatimReverse(
            """{"lat":"18.5204","lon":"73.8567","name":"Mangalwar Peth","display_name":"Mangalwar Peth, Pune, India"}""",
        )!!
        assertEquals("Mangalwar Peth", place.name)
        assertEquals(18.5204, place.latitudeDeg, 0.0001)
        assertTrue(place.detail.contains("Pune"))
    }
}
