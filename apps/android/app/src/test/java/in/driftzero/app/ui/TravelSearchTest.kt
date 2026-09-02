package `in`.driftzero.app.ui

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
}
