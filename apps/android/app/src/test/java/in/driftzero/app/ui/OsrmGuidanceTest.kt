package `in`.driftzero.app.ui

import `in`.driftzero.core.ManeuverModifier
import `in`.driftzero.core.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OsrmGuidanceTest {
    @Test
    fun mapsOsrmTypesAndModifiers() {
        assertEquals(ManeuverType.TURN, mapOsrmType("turn"))
        assertEquals(ManeuverType.NEW_NAME, mapOsrmType("new name"))
        assertEquals(ManeuverType.ON_RAMP, mapOsrmType("on ramp"))
        assertEquals(ManeuverType.OFF_RAMP, mapOsrmType("off ramp"))
        assertEquals(ManeuverType.END_OF_ROAD, mapOsrmType("end of road"))
        assertEquals(ManeuverType.ROUNDABOUT, mapOsrmType("roundabout"))
        assertEquals(ManeuverType.EXIT_ROUNDABOUT, mapOsrmType("exit roundabout"))
        assertEquals(ManeuverType.ARRIVE, mapOsrmType("arrive"))
        assertEquals(ManeuverType.UNKNOWN, mapOsrmType("notification"))
        assertEquals(ManeuverModifier.LEFT, mapOsrmModifier("left"))
        assertEquals(ManeuverModifier.SHARP_RIGHT, mapOsrmModifier("sharp right"))
        assertEquals(ManeuverModifier.UTURN, mapOsrmModifier("uturn"))
        assertEquals(ManeuverModifier.NONE, mapOsrmModifier(null))
    }

    @Test
    fun parseOsrmRouteReadsStepsAndStartIndex() {
        val body = """
            {"code":"Ok","routes":[{"distance":200.0,"duration":20.0,
              "geometry":{"type":"LineString","coordinates":[[73.8,18.5],[73.85,18.52],[73.9,18.6]]},
              "legs":[{"steps":[
                {"distance":100.0,"duration":10.0,"name":"Start Road",
                 "maneuver":{"type":"depart","modifier":"straight","location":[73.8,18.5]}},
                {"distance":100.0,"duration":10.0,"name":"Senapati Bapat Road",
                 "maneuver":{"type":"turn","modifier":"left","location":[73.85,18.52]}},
                {"distance":0.0,"duration":0.0,"name":"",
                 "maneuver":{"type":"arrive","location":[73.9,18.6]}}
              ]}]}]}
        """.trimIndent()
        val route = parseOsrmRoute(body)!!
        assertEquals(3, route.steps.size)
        assertEquals(ManeuverType.DEPART, route.steps[0].type)
        assertEquals("Start Road", route.steps[0].roadName)
        assertEquals(0, route.steps[0].startIndex)
        assertEquals(ManeuverType.TURN, route.steps[1].type)
        assertEquals(ManeuverModifier.LEFT, route.steps[1].modifier)
        assertEquals("Senapati Bapat Road", route.steps[1].roadName)
        assertEquals(1, route.steps[1].startIndex)
        assertEquals(ManeuverType.ARRIVE, route.steps[2].type)
        assertEquals(null, route.steps[2].roadName)
        assertEquals(2, route.steps[2].startIndex)
        val guidance = route.toGuidance()
        assertEquals(3, guidance.steps.size)
        assertEquals(3, guidance.points.size)
    }

    @Test
    fun parseOsrmRouteWithoutStepsSynthesizesDepartAndArrive() {
        val body = """
            {"code":"Ok","routes":[{"distance":12400.0,"duration":1080.0,
              "geometry":{"type":"LineString","coordinates":[[73.8,18.5],[73.85,18.52],[73.9,18.6]]}}]}
        """.trimIndent()
        val route = parseOsrmRoute(body)!!
        assertEquals(2, route.steps.size)
        assertEquals(ManeuverType.DEPART, route.steps[0].type)
        assertEquals(ManeuverType.ARRIVE, route.steps[1].type)
        assertEquals(2, route.steps[1].startIndex)
        assertTrue(route.toGuidance().steps.isNotEmpty())
    }
}
