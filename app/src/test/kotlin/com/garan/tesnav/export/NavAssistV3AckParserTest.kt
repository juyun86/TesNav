package com.garan.tesnav.export

import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NavAssistV3AckParserTest {
    @Test fun `optional model route receipt permits cache miss and preserves legacy ACK`() {
        val base = """{"messageType":"navassist_udp_ack","schemaVersion":3,"sessionId":"s","sequence":1"""
        fun parseReceipt(suffix: String) = NavAssistV3AckParser.parse((base + suffix).toByteArray(), "host", "s", 1L, 1L, 1L)
        assertEquals(null, parseReceipt("}")!!.modelRouteRevision)
        assertEquals(-1L, parseReceipt(",\"modelRouteRevision\":-1}")!!.modelRouteRevision)
        assertEquals(7L, parseReceipt(",\"modelRouteRevision\":7}")!!.modelRouteRevision)
        assertNull(parseReceipt(",\"modelRouteRevision\":-2}"))
    }
    @Test fun `lane decision is session bound and cannot corrupt vehicle feedback`() {
        val base = JsonParser.parseString(fixture().decodeToString()).asJsonObject
        val decision = """{"sessionId":"$session","reason":"efficiencySafetyBlocked","signalRequested":false,"direction":"none"}"""
        base.add("laneDecision", JsonParser.parseString(decision))
        assertEquals("efficiencySafetyBlocked", parse(base.toString())!!.vehicleLane!!.c3LaneDecision!!.reason)
        for (bad in listOf(decision.replace(session, "other-session"),
                           decision.replace("efficiencySafetyBlocked", "bad reason"),
                           decision.replace("false", "\"false\""))) {
            base.add("laneDecision", JsonParser.parseString(bad))
            val lane = parse(base.toString())!!.vehicleLane!!
            assertNull(lane.c3LaneDecision)
            assertEquals(123L, lane.receivedAtMs)
        }
    }
    @Test fun `speech request binds exact session direction and bounded identifier`() {
        val speech = "\"laneAnnouncement\":{\"id\":\"${"a".repeat(32)}\",\"sessionId\":\"$session\",\"direction\":\"left\"}"
        val body = core().dropLast(1) + "," + speech + "}"
        assertEquals("a".repeat(32), parse(body)!!.announcement!!.id)
        assertEquals("left", parse(body)!!.announcement!!.direction)
        for (bad in listOf(body.replace("\"left\"", "\"none\""),
                           body.replace("a".repeat(32), "x".repeat(32)),
                           core().dropLast(1) + "," + speech.replace(session, "wrong-session") + "}")) {
            assertNull(parse(bad)!!.announcement)
        }
        assertNull(parse(core())!!.announcement)
    }
    private val session = "00000000-0000-0000-0000-000000000001"
    private fun core(sequence: String = "1") =
        """{"messageType":"navassist_udp_ack","schemaVersion":3,"sessionId":"$session","sequence":$sequence}"""

    private fun parse(bytes: ByteArray, sequence: Long = 1L) =
        NavAssistV3AckParser.parse(bytes, "192.168.53.232", session, sequence, 123L, 456L)
    private fun parse(text: String, sequence: Long = 1L) = parse(text.toByteArray(), sequence)
    private fun fixture() = checkNotNull(javaClass.getResourceAsStream("/navassist/c3-vehicle-lane-ack.json")).use { it.readBytes() }

    @Test fun `core ACK and real local C3 feedback fixture are accepted`() {
        assertNull(parse(core())!!.vehicleLane)
        val bytes = fixture()
        assertEquals(592, bytes.size)
        val ack = parse(bytes)!!
        assertEquals("192.168.53.232", ack.host)
        assertNotNull(ack.vehicleLane)
        assertFalse(ack.vehicleLane!!.permissionValid)
        assertEquals(123L, ack.vehicleLane!!.receivedAtMs)
        assertEquals(456L, ack.vehicleLane!!.receivedAtElapsedMs)
    }

    @Test fun `size is checked on the complete datagram`() {
        for (size in listOf(512, 592, 2048)) assertNotNull(parse(core().padEnd(size, ' ')))
        assertNull(parse(core().padEnd(2049, ' ')))
        assertNull(parse(byteArrayOf()))
        val utf8 = core().replace(session, "中".repeat(700))
        assertTrue(utf8.length < 2048)
        assertTrue(utf8.toByteArray().size > 2048)
        assertNull(NavAssistV3AckParser.parse(utf8.toByteArray(), "host", "中".repeat(700), 1L, 1L, 1L))
    }

    @Test fun `core fields cannot coerce strings booleans fractions or objects`() {
        for (field in listOf("schemaVersion", "sequence")) {
            for (bad in listOf("true", "false", "null", "[]", "{}", "\"1\"", "1.0", "1.9", "1e0", "9223372036854775808")) {
                val json = JsonParser.parseString(core()).asJsonObject
                json.add(field, JsonParser.parseString(bad))
                assertNull("$field=$bad", parse(json.toString()))
            }
        }
        for (field in listOf("messageType", "sessionId")) {
            val json = JsonParser.parseString(core()).asJsonObject
            json.addProperty(field, 1)
            assertNull(parse(json.toString()))
        }
    }

    @Test fun `sequence preserves full signed 64 bit precision and rejects mismatch`() {
        assertNotNull(parse(core(Long.MAX_VALUE.toString()), Long.MAX_VALUE))
        assertNull(parse(core((Long.MAX_VALUE - 1).toString()), Long.MAX_VALUE))
        for (bad in listOf("0", "-1", "2")) assertNull(parse(core(bad)))
        assertNull(parse(core().replace(session, "old-session")))
        assertNull(parse(core().replace("\"schemaVersion\":3", "\"schemaVersion\":2")))
    }

    @Test fun `duplicate escaped keys unknown keys and trailing content are rejected`() {
        for (tail in listOf(
            ",\"sequence\":1}", ",\"sequen\\u0063e\":1}", ",\"control\":true}",
        )) assertNull(parse(core().dropLast(1) + tail))
        assertNull(parse(core() + core()))
        assertNull(parse(core().dropLast(1) + ",}"))
        assertNull(parse("/* comment */" + core()))
        assertNull(parse(core().replace("\"sequence\"", "sequence")))
        assertNull(parse(fixture().decodeToString().replace("\"position\":\"unknown\"", "\"position\":\"unknown\",\"position\":\"middle\"")))
    }

    @Test fun `bad UTF8 and non object payloads do not escape parser`() {
        val bytes = core().toByteArray()
        bytes[5] = 0xff.toByte()
        assertNull(parse(bytes))
        for (value in listOf("[]", "true", "null", "\"ack\"", "{", "")) assertNull(parse(value))
        assertNotNull(parse(core())) // A bad packet does not poison a subsequent valid packet.
    }

    @Test fun `invalid optional feedback does not invalidate an otherwise valid core ACK`() {
        for (bad in listOf("null", "[]", "true", "1", "\"vehicle\"", "{}")) {
            val ack = parse(core().dropLast(1) + ",\"vehicleLane\":$bad}")
            assertNotNull(ack)
            assertNull(ack!!.vehicleLane)
        }
    }

    @Test fun `feedback booleans must be actual JSON booleans`() {
        val root = JsonParser.parseString(fixture().decodeToString()).asJsonObject
        val fields = root.getAsJsonObject("vehicleLane").entrySet().filter { it.value.isJsonPrimitive && it.value.asJsonPrimitive.isBoolean }.map { it.key }
        for (field in fields) for (bad in listOf("0", "1", "\"false\"", "{}", "null")) {
            val json = root.deepCopy()
            json.getAsJsonObject("vehicleLane").add(field, JsonParser.parseString(bad))
            assertNull("$field=$bad", parse(json.toString())!!.vehicleLane)
        }
    }

    @Test fun `feedback finite numbers nullable values and signed relative speed remain distinct`() {
        val root = JsonParser.parseString(fixture().decodeToString()).asJsonObject
        for (field in listOf("leadDistanceM", "leadSpeedKph", "leadRelativeSpeedKph", "egoSpeedKph")) {
            for (bad in listOf("true", "\"NaN\"", "\"Infinity\"", "\"30\"", "1e999", "[]", "{}")) {
                val json = root.deepCopy()
                json.getAsJsonObject("vehicleLane").add(field, JsonParser.parseString(bad))
                assertNull("$field=$bad", parse(json.toString())!!.vehicleLane)
            }
        }
        root.getAsJsonObject("vehicleLane").addProperty("leadRelativeSpeedKph", -10.5)
        assertEquals(-10.5f, parse(root.toString())!!.vehicleLane!!.leadRelativeSpeedKph)
        for (field in listOf("leadDistanceM", "egoSpeedKph")) {
            val json = root.deepCopy()
            json.getAsJsonObject("vehicleLane").addProperty(field, -1)
            assertNull(parse(json.toString())!!.vehicleLane)
        }
    }

    @Test fun `feedback enum ranges and complete shape are enforced`() {
        for ((field, invalid) in listOf("autoLaneChangeState" to 32, "laneChangeState" to 4, "laneChangeDirection" to 3)) {
            for (bad in listOf(JsonPrimitive(invalid), JsonPrimitive(-1), JsonPrimitive(0.5), JsonNull.INSTANCE)) {
                val root = JsonParser.parseString(fixture().decodeToString()).asJsonObject
                root.getAsJsonObject("vehicleLane").add(field, bad)
                assertNull(parse(root.toString())!!.vehicleLane)
            }
        }
        val root = JsonParser.parseString(fixture().decodeToString()).asJsonObject
        root.getAsJsonObject("vehicleLane").addProperty("positionValid", true)
        assertNull(parse(root.toString())!!.vehicleLane)
        root.getAsJsonObject("vehicleLane").addProperty("position", "middle")
        assertTrue(parse(root.toString())!!.vehicleLane!!.positionValid)
        root.getAsJsonObject("vehicleLane").remove("leftAllowed")
        assertNull(parse(root.toString())!!.vehicleLane)
    }
}
