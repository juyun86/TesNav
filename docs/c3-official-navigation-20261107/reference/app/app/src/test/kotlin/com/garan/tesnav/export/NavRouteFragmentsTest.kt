package com.garan.tesnav.export

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class NavRouteFragmentsTest {
    @Test fun `route fragments stay bounded and identify one immutable transfer`() {
        val random = Random(4)
        val body = ByteArray(30000).also(random::nextBytes)
        val chunks = NavRouteFragments.encode(body, "session-1", 3)
        assertTrue(chunks.size > 1)
        val decoded = chunks.map { JsonParser.parseString(it.toString(Charsets.UTF_8)).asJsonObject }
        val digest = decoded.first()["digest"].asString
        decoded.forEachIndexed { index, value ->
            assertTrue(chunks[index].size <= NavRouteFragments.MAX_PACKET_BYTES)
            assertEquals("route_fragment", value["messageType"].asString)
            assertEquals(index, value["index"].asInt)
            assertEquals(chunks.size, value["count"].asInt)
            assertEquals(digest, value["digest"].asString)
            assertEquals(3, value["routeRevision"].asInt)
        }
    }

    @Test fun `invalid identity and empty payload are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { NavRouteFragments.encode(byteArrayOf(), "s", 1) }
        assertThrows(IllegalArgumentException::class.java) { NavRouteFragments.encode(byteArrayOf(1), "s/other", 1) }
        assertThrows(IllegalArgumentException::class.java) { NavRouteFragments.encode(byteArrayOf(1), "s", -1) }
    }

    @Test fun `fragment matches Python receiver fixture`() {
        val body = """{"messageType":"navigation_route","steps":[]}""".toByteArray()
        val value = JsonParser.parseString(NavRouteFragments.encode(body, "session-1", 3).single().toString(Charsets.UTF_8)).asJsonObject
        assertEquals("aa8d61736236dfb9ab221c157625c69b71696f1f21e8e9257aebd7a0c94d0729", value["digest"].asString)
        assertEquals("eJyrVspNLS5OTE8NqSxIVbJSykssy0xPLMnMz4svyi8tSVXSUSouSS0oVrKKjq0FAIJFEJE=", value["data"].asString)
    }
}
