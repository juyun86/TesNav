package com.garan.tesnav.export

import com.garan.tesnav.model.GeoPoint
import com.garan.tesnav.model.NavigationState
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class NavAssistModelRouteTest {
    private fun snapshot() = NavAssistV2Mapper.snapshot(NavigationState(), "session-a", 8L, 1_000L, 500L)
        .copy(routeActive = true, routeMatched = true, gpsWeak = false, navigationMode = "realtime", routeRevision = 3L)
    private val route = NavAssistModelRoute(42L, 3L, listOf(GeoPoint(31.2, 121.4), GeoPoint(31.201, 121.401)))

    @Test fun `original geometry and route identity survive serialization`() {
        val encoded = JsonParser.parseString(route.body(snapshot(), 1_100L)!!).asJsonObject
        assertEquals("navigation_route", encoded["messageType"].asString)
        assertEquals("gcj02", encoded["coordinateSystem"].asString)
        assertEquals(3L, encoded["routeRevision"].asLong)
        assertEquals(8L, encoded["sequence"].asLong)
        assertEquals(2, encoded["points"].asJsonArray.size())
        assertEquals(31.201, encoded["points"].asJsonArray[1].asJsonObject["latitude"].asDouble, 0.0)
    }

    @Test fun `inactive mismatched simulated and invalid routes are withheld`() {
        val base = snapshot()
        for (bad in listOf(base.copy(routeActive = false), base.copy(routeMatched = false),
            base.copy(gpsWeak = true), base.copy(navigationMode = "simulation"),
            base.copy(coordinateSystem = "wgs84"), base.copy(routeRevision = 4))) {
            assertNull(route.body(bad, 1_100L))
        }
        assertNull(route.copy(points = emptyList()).body(base, 1_100L))
        assertNull(route.copy(points = listOf(GeoPoint(Double.NaN, 1.0), GeoPoint(2.0, 1.0))).body(base, 1_100L))
        assertNull(route.copy(points = List(20_001) { GeoPoint(1.0, 1.0) }).body(base, 1_100L))
    }

    @Test fun `route receipt must match revision as well as snapshot identity`() {
        val body = route.body(snapshot(), 1_100L)!!
        assertTrue(confirmsNavigationSnapshot(body, """{"accepted":true,"sessionId":"session-a","sequence":8,"routeRevision":3}"""))
        assertFalse(confirmsNavigationSnapshot(body, """{"accepted":true,"sessionId":"session-a","sequence":8,"routeRevision":2}"""))
    }
}
