package com.garan.tesnav.ui

import com.garan.tesnav.model.GeoPoint
import org.junit.Assert.*
import org.junit.Test

class NavigationMapGeometryTest {
    private val origin = GeoPoint(31.2, 121.4)

    @Test fun projectionMatchesOfficialScaleAndHeading() {
        val north = GeoPoint(origin.latitude + 100.0 / 111319.49079327357, origin.longitude)
        val straight = NavigationMapGeometry.project(north, NavigationMapGeometry.Pose(origin, 0.0))
        assertEquals(128.0, straight.x, 1e-5)
        assertEquals(92.0, straight.y, .002)
        val eastHeading = NavigationMapGeometry.project(north, NavigationMapGeometry.Pose(origin, 90.0))
        assertEquals(28.0, eastHeading.x, .002)
        assertEquals(192.0, eastHeading.y, .002)
        assertEquals(NavigationMapGeometry.Pixel(128.0, 192.0),
            NavigationMapGeometry.project(origin, NavigationMapGeometry.Pose(origin, 0.0)))
    }

    @Test fun zoomCorrectionUsesMeasuredPhysicalPixels() {
        assertEquals(18f, NavigationMapGeometry.correctedZoom(17f, 2f), 1e-5f)
        assertEquals(16f, NavigationMapGeometry.correctedZoom(17f, .5f), 1e-5f)
        assertTrue(NavigationMapGeometry.scaleMatches(1.01f))
        assertFalse(NavigationMapGeometry.scaleMatches(2f))
        assertFalse(NavigationMapGeometry.scaleMatches(Float.NaN))
    }

    @Test fun physicalProjectionCorrectsTabletSdkScaleMismatch() {
        val measured = NavigationMapGeometry.measuredScale(100.0, 131.18688958886096)
        assertFalse(NavigationMapGeometry.scaleMatches(measured))
        val zoom = NavigationMapGeometry.correctedZoom(17f, measured)
        assertTrue(zoom < 17f)
        assertEquals(100.0, 131.18688958886096 * Math.pow(2.0, (zoom - 17).toDouble()), .001)
        assertTrue(NavigationMapGeometry.scaleMatches(NavigationMapGeometry.measuredScale(100.0, 100.0)))
        assertThrows(IllegalArgumentException::class.java) { NavigationMapGeometry.measuredScale(100.0, 0.0) }
    }

    @Test fun routePoseSnapsToRouteAndUsesTangentRatherThanGpsHeading() {
        val route = listOf(origin, GeoPoint(31.202, 121.4))
        val pose = NavigationMapGeometry.routePose(route, GeoPoint(31.201, 121.4001))!!
        assertEquals(31.201, pose.point.latitude, 1e-9)
        assertEquals(121.4, pose.point.longitude, 1e-9)
        assertEquals(0.0, pose.bearing, 1e-9)
    }

    @Test fun unsupportedOrDegenerateGeometryIsNotUsed() {
        assertNull(NavigationMapGeometry.routePose(emptyList(), origin))
        assertNull(NavigationMapGeometry.routePose(listOf(origin, origin), origin))
        assertNull(NavigationMapGeometry.routePose(listOf(origin, GeoPoint(Double.NaN, 121.4)), origin))
        assertFalse(NavigationMapGeometry.valid(GeoPoint(86.0, 121.4)))
    }
}
