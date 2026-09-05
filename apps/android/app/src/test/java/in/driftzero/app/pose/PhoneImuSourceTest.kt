package `in`.driftzero.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI

class PhoneImuSourceTest {
    @Test
    fun measuredHzFromSensorTimestampsIsNotAClaimedHundred() {
        assertNull(PhoneImuSource.measuredHz(1, 0L, 1_000_000_000L))
        assertNull(PhoneImuSource.measuredHz(40, 10L, 10L))
        val hz = PhoneImuSource.measuredHz(51, 0L, 1_000_000_000L)
        assertEquals(50.0, hz!!, 1e-9)
        assertEquals("SENSOR_DELAY_FASTEST", PhoneImuSource.REQUESTED_DELAY_NAME)
    }

    @Test
    fun bearingAccuracyDegreesConvertToRadians() {
        val rad = bearingAccuracyDegToRad(180.0)
        assertEquals(PI, rad!!, 1e-12)
        assertNull(optionalNonNegAccuracy(Double.NaN))
        assertNull(optionalNonNegAccuracy(-0.1))
        assertEquals(0.25, optionalNonNegAccuracy(0.25)!!, 0.0)
    }
}
