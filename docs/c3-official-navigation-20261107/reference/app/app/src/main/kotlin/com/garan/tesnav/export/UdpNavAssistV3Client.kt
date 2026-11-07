package com.garan.tesnav.export

import android.os.SystemClock

import com.google.gson.JsonParser
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import com.garan.tesnav.model.OemLanePosition
import com.garan.tesnav.model.OemVehicleLaneState
import com.garan.tesnav.model.C3LaneDecision
import com.garan.tesnav.model.OemNavigationTelemetry
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections
import kotlin.math.min

internal const val NAVASSIST_UDP_RECEIVE_WINDOW_MS = 350L

internal fun interface NavAssistV3UdpClient {
    /** Discover C3, then send directly; a matching UDP ACK does not authenticate C3. */
    fun send(body: ByteArray, sessionId: String, sequence: Long): NavAssistV3UdpAck?
}

data class LaneAnnouncement(val id: String, val sessionId: String, val direction: String, val receivedAtElapsedMs: Long)

internal data class NavAssistV3UdpAck(val host: String, val vehicleLane: OemVehicleLaneState?, val announcement: LaneAnnouncement? = null,
    val modelRouteRevision: Long? = null)

/** Reads the existing ACK and flat feedback shape; malformed packets never escape the receive loop. */
internal object NavAssistV3AckParser {
    const val MAX_ACK_BYTES = 2 * 1024
    private val REQUIRED_ACK_KEYS = setOf("messageType", "schemaVersion", "sessionId", "sequence")
    private val ALLOWED_ACK_KEYS = REQUIRED_ACK_KEYS + setOf("vehicleLane", "vehicleTelemetry", "laneAnnouncement", "laneDecision", "modelRouteRevision")
    private val TELEMETRY_KEYS = setOf(
        "gpsStatus", "latitude", "longitude", "accuracyValue", "hdop", "headingDeg", "speedValue",
        "mapAvailable", "controllerHealth", "alcState", "forkState", "abortReason", "navAvailable",
        "navUsage", "autosteerHealth", "plannerState", "navDistanceM",
    )
    private val OPTIONAL_TELEMETRY_KEYS = setOf("roadEstimator", "oemLaneChangeState",
        "gpsTimeMs", "gpsTimeAgeMs", "gpsPositionAgeMs", "gpsMotionAgeMs", "gpsAccuracyRaw", "gpsAccuracyAgeMs")
    private val VEHICLE_LANE_KEYS = setOf(
        "position", "positionValid", "leftAllowed", "rightAllowed", "permissionValid", "autoLaneChangeState",
        "blindspotValid", "leftBlindspot", "rightBlindspot", "radarValid", "leadPresent", "leadDistanceM",
        "leadSpeedKph", "leadRelativeSpeedKph", "vehicleStateValid", "egoSpeedKph", "lateralActive",
        "brakePressed", "gasPressed", "laneChangeState", "laneChangeDirection",
    )

    fun parse(
        payload: ByteArray, host: String, sessionId: String, sequence: Long,
        receivedAtMs: Long, receivedAtElapsedMs: Long,
    ): NavAssistV3UdpAck? = runCatching {
        require(payload.isNotEmpty() && payload.size <= MAX_ACK_BYTES && sequence > 0)
        val ack = JsonReader(StringReader(payload.decodeToString(throwOnInvalidSequence = true))).use { reader ->
            reader.strictness = Strictness.STRICT
            val result = readObject(reader, allowFeedback = true)
            require(reader.peek() == JsonToken.END_DOCUMENT)
            result
        }
        require(ack.keySet().containsAll(REQUIRED_ACK_KEYS) && ALLOWED_ACK_KEYS.containsAll(ack.keySet()))
        require(string(ack, "messageType") == "navassist_udp_ack")
        require(integer(ack, "schemaVersion") == NavAssistV2Protocol.SCHEMA_VERSION.toLong())
        require(string(ack, "sessionId") == sessionId && integer(ack, "sequence") == sequence)
        val feedback = runCatching feedback@{
            val lane = ack.getAsJsonObject("vehicleLane") ?: return@feedback null
            require(lane.keySet() == VEHICLE_LANE_KEYS)
            val position = when (string(lane, "position")) {
                "leftmost" -> OemLanePosition.LEFTMOST
                "middle" -> OemLanePosition.MIDDLE
                "rightmost" -> OemLanePosition.RIGHTMOST
                "single" -> OemLanePosition.SINGLE
                "unknown" -> OemLanePosition.UNKNOWN
                else -> error("unknown position")
            }
            val positionValid = boolean(lane, "positionValid")
            require(!positionValid || position != OemLanePosition.UNKNOWN)
            OemVehicleLaneState(
                position = position, positionValid = positionValid,
                leftAllowed = boolean(lane, "leftAllowed"), rightAllowed = boolean(lane, "rightAllowed"),
                permissionValid = boolean(lane, "permissionValid"),
                autoLaneChangeState = integer(lane, "autoLaneChangeState", 0L..31L).toInt(),
                blindspotValid = boolean(lane, "blindspotValid"),
                leftBlindspot = boolean(lane, "leftBlindspot"), rightBlindspot = boolean(lane, "rightBlindspot"),
                radarValid = boolean(lane, "radarValid"), leadPresent = boolean(lane, "leadPresent"),
                leadDistanceM = number(lane, "leadDistanceM", nonNegative = true),
                leadSpeedKph = number(lane, "leadSpeedKph"),
                leadRelativeSpeedKph = number(lane, "leadRelativeSpeedKph"),
                vehicleStateValid = boolean(lane, "vehicleStateValid"),
                egoSpeedKph = number(lane, "egoSpeedKph", nonNegative = true),
                lateralActive = boolean(lane, "lateralActive"),
                brakePressed = boolean(lane, "brakePressed"), gasPressed = boolean(lane, "gasPressed"),
                laneChangeState = integer(lane, "laneChangeState", 0L..3L).toInt(),
                laneChangeDirection = integer(lane, "laneChangeDirection", 0L..2L).toInt(),
                receivedAtMs = receivedAtMs, receivedAtElapsedMs = receivedAtElapsedMs,
            )
        }.getOrNull()
        val telemetry = runCatching { parseTelemetry(ack.getAsJsonObject("vehicleTelemetry")) }.getOrNull()
        val decision = runCatching {
            ack.getAsJsonObject("laneDecision")?.let { value ->
                require(value.keySet() == setOf("sessionId", "reason", "signalRequested", "direction"))
                require(string(value, "sessionId") == sessionId)
                val reason = string(value, "reason")
                val direction = string(value, "direction")
                require(reason.length in 1..80 && reason.matches(Regex("[A-Za-z0-9:]+")))
                require(direction in setOf("none", "left", "right"))
                C3LaneDecision(reason, boolean(value, "signalRequested"), direction)
            }
        }.getOrNull()
        val announcement = runCatching {
            ack.getAsJsonObject("laneAnnouncement")?.let {
                require(it.keySet() == setOf("id", "sessionId", "direction"))
                val id = string(it, "id"); val direction = string(it, "direction")
                require(id.matches(Regex("[0-9a-f]{32}")) && string(it, "sessionId") == sessionId)
                require(direction in setOf("left", "right"))
                LaneAnnouncement(id, sessionId, direction, receivedAtElapsedMs)
            }
        }.getOrNull()
        NavAssistV3UdpAck(host, if (telemetry == null && decision == null) feedback else
            (feedback ?: OemVehicleLaneState(receivedAtMs = receivedAtMs, receivedAtElapsedMs = receivedAtElapsedMs))
                .copy(navigationTelemetry = telemetry, c3LaneDecision = decision), announcement,
            if (ack.has("modelRouteRevision")) integer(ack, "modelRouteRevision", -1L..Long.MAX_VALUE) else null)
    }.getOrNull()

    private fun readObject(reader: JsonReader, allowFeedback: Boolean = false): JsonObject {
        reader.beginObject()
        val result = JsonObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            require(!result.has(key)) { "duplicate field" }
            val value = when (reader.peek()) {
                JsonToken.STRING -> JsonPrimitive(reader.nextString())
                JsonToken.NUMBER -> JsonParser.parseString(reader.nextString())
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                JsonToken.BEGIN_OBJECT -> if (allowFeedback && key in setOf("vehicleLane", "vehicleTelemetry", "laneAnnouncement", "laneDecision")) readObject(reader) else {
                    reader.skipValue(); JsonArray() // Preserve an invalid composite, not a nullable number.
                }
                else -> { reader.skipValue(); JsonArray() }
            }
            result.add(key, value)
        }
        reader.endObject()
        return result
    }

    private fun string(obj: JsonObject, key: String): String = obj[key].asJsonPrimitive.let {
        require(it.isString); it.asString
    }

    private fun parseTelemetry(obj: JsonObject?): OemNavigationTelemetry? {
        if (obj == null) return null
        require(obj.keySet().containsAll(TELEMETRY_KEYS) &&
            (TELEMETRY_KEYS + OPTIONAL_TELEMETRY_KEYS).containsAll(obj.keySet()))
        fun decimal(key: String, min: Double, max: Double): Double? {
            val value = obj[key]
            if (value.isJsonNull) return null
            require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            return value.asDouble.also { require(it.isFinite() && it in min..max) }
        }
        fun code(key: String, max: Long): Int? = if (obj[key].isJsonNull) null else integer(obj, key, 0L..max).toInt()
        fun flag(key: String): Boolean? = if (obj[key].isJsonNull) null else boolean(obj, key)
        fun optionalLong(key: String, range: LongRange): Long? =
            if (!obj.has(key) || obj[key].isJsonNull) null else integer(obj, key, range)
        val gpsTime = optionalLong("gpsTimeMs", 1L..253402300799999L)
        val gpsTimeAge = optionalLong("gpsTimeAgeMs", 0L..2499L)
        val accuracyRaw = if (!obj.has("gpsAccuracyRaw") || obj["gpsAccuracyRaw"].isJsonNull) null else
            string(obj, "gpsAccuracyRaw").also { require(it.matches(Regex("(?:[0-9a-f]{10}|[0-9a-f]{16})"))) }
        val accuracyAge = optionalLong("gpsAccuracyAgeMs", 0L..2499L)
        require((gpsTime == null) == (gpsTimeAge == null))
        require((accuracyRaw == null) == (accuracyAge == null))
        val status = string(obj, "gpsStatus")
        require(status in setOf("missing", "stale", "invalid", "jump", "observed"))
        val latitude = decimal("latitude", -90.0, 90.0)
        val longitude = decimal("longitude", -180.0, 180.0)
        val accuracy = decimal("accuracyValue", 0.2, 25.2)
        val hdop = decimal("hdop", 0.1, 25.4)
        if (status == "observed") require(latitude != null && longitude != null && accuracy != null && hdop != null)
        else require(latitude == null && longitude == null && accuracy == null)
        return OemNavigationTelemetry(
            status, latitude, longitude, accuracy, hdop,
            decimal("headingDeg", 0.0, 359.999), decimal("speedValue", 0.0, 255.993),
            flag("mapAvailable"), code("controllerHealth", 3), code("alcState", 15),
            code("forkState", 31), code("abortReason", 255), flag("navAvailable"),
            code("navUsage", 3), code("autosteerHealth", 7), code("plannerState", 15), code("navDistanceM", 25400),
            roadEstimator = if (obj.has("roadEstimator")) code("roadEstimator", 3) else null,
            oemLaneChangeState = if (obj.has("oemLaneChangeState")) code("oemLaneChangeState", 63) else null,
            gpsTimeMs = gpsTime, gpsTimeAgeMs = gpsTimeAge,
            gpsPositionAgeMs = optionalLong("gpsPositionAgeMs", 0L..2499L),
            gpsMotionAgeMs = optionalLong("gpsMotionAgeMs", 0L..2499L),
            gpsAccuracyRaw = accuracyRaw, gpsAccuracyAgeMs = accuracyAge,
        )
    }

    private fun boolean(obj: JsonObject, key: String): Boolean = obj[key].asJsonPrimitive.let {
        require(it.isBoolean); it.asBoolean
    }

    private fun integer(obj: JsonObject, key: String, range: LongRange = 0L..Long.MAX_VALUE): Long =
        obj[key].asJsonPrimitive.let {
            require(it.isNumber)
            val value = it.asString.toLongOrNull() ?: error("not an exact integer")
            require(value in range)
            value
        }

    private fun number(obj: JsonObject, key: String, nonNegative: Boolean = false): Float? {
        val value = obj[key]
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return value.asFloat.also { require(it.isFinite() && (!nonNegative || it >= 0f)) }
    }
}

internal class JvmUdpNavAssistV3Client(private val trace: ((String) -> Unit)? = null) : NavAssistV3UdpClient {
    private var discoveredHost: InetAddress? = null
    private var discoveredNetwork: String? = null
    private var consecutiveFailures = 0

    internal fun deliveryTargets(network: String, discover: () -> List<InetAddress>): List<InetAddress> {
        if (network.isEmpty() || network != discoveredNetwork) {
            discoveredHost = null
            consecutiveFailures = 0
            discoveredNetwork = network
        }
        return discoveredHost?.let { listOf(it) } ?: discover()
    }

    internal fun recordDelivery(ack: NavAssistV3UdpAck?) {
        if (ack != null) {
            discoveredHost = InetAddress.getByName(ack.host)
            consecutiveFailures = 0
        } else if (++consecutiveFailures >= 2) {
            discoveredHost = null
        }
    }

    override fun send(body: ByteArray, sessionId: String, sequence: Long): NavAssistV3UdpAck? {
        require(body.isNotEmpty() && body.size <= MAX_SNAPSHOT_BYTES) { "UDP snapshot is too large" }
        val startNs = System.nanoTime()
        val startElapsedMs = if (trace != null) SystemClock.elapsedRealtime() else 0L
        val events = if (trace != null) StringBuilder() else null
        var localPort = -1
        var outcome = "unfinished"
        fun mark(event: String) {
            if (events != null && events.length < 4096) events.append("$event@${(System.nanoTime()-startNs)/NANOS_PER_MILLISECOND};")
        }
        fun finish(ack: NavAssistV3UdpAck?, reason: String): NavAssistV3UdpAck? {
            outcome = "${if (ack == null) "missing" else "ack"}:$reason"
            recordDelivery(ack)
            return ack
        }
        try {
            DatagramSocket().use { socket ->
                localPort = socket.localPort
                socket.broadcast = true
                val targets = deliveryTargets(networkIdentity(), ::broadcastTargets)
                val directHost = discoveredHost
                mark("mode=${if (directHost == null) "discovery" else "direct"}")
                val sent = targets.count { target ->
                    runCatching { socket.send(DatagramPacket(body, body.size, target, UDP_PORT)) }
                        .onSuccess { mark("send=${target.hostAddress}:ok") }
                        .onFailure { mark("send=${target.hostAddress}:${it.javaClass.simpleName}") }.isSuccess
                }
                if (sent == 0) error("no usable broadcast interface")

                var deadlineNs = System.nanoTime() + NAVASSIST_UDP_RECEIVE_WINDOW_MS * NANOS_PER_MILLISECOND
                mark("receive_window=${NAVASSIST_UDP_RECEIVE_WINDOW_MS}")
                var legacyAck: NavAssistV3UdpAck? = null
                while (true) {
                    val remainingMs = (deadlineNs - System.nanoTime()) / NANOS_PER_MILLISECOND
                    if (remainingMs <= 0L) return finish(legacyAck, "deadline")
                    socket.soTimeout = min(remainingMs, Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                    val buffer = ByteArray(NavAssistV3AckParser.MAX_ACK_BYTES + 1)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        return finish(legacyAck, "timeout")
                    }
                    val receivedAtElapsedMs = SystemClock.elapsedRealtime()
                    mark("receive=${packet.address?.hostAddress}:${packet.port}:${packet.length}")
                    if (packet.length > NavAssistV3AckParser.MAX_ACK_BYTES) { mark("reject=oversize"); continue }
                    val source = packet.address as? Inet4Address ?: continue
                    if (source.isAnyLocalAddress || source.isLoopbackAddress || source.isMulticastAddress) continue
                    if (directHost != null && source != directHost) { mark("reject=host"); continue }
                    val ack = NavAssistV3AckParser.parse(
                        packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                        source.hostAddress ?: continue, sessionId, sequence,
                        System.currentTimeMillis(), receivedAtElapsedMs,
                    )
                    if (ack == null) { mark("reject=parse_or_sequence"); continue }
                    if (legacyAck != null && ack.host != legacyAck.host) { mark("reject=host"); continue }
                    mark("accepted=${ack.host}")
                    if (ack.announcement != null) return finish(ack, "announcement")
                    if (legacyAck == null || ack.vehicleLane?.navigationTelemetry != null || ack.modelRouteRevision != null) {
                        legacyAck = ack.copy(modelRouteRevision = ack.modelRouteRevision ?: legacyAck?.modelRouteRevision)
                        // Bound a short grace window for UDP reordering; old C3 remains compatible.
                        deadlineNs = minOf(deadlineNs, System.nanoTime() + 30 * NANOS_PER_MILLISECOND)
                    }
                }
            }
        } catch (error: Exception) {
            recordDelivery(null)
            outcome = "error:${error.javaClass.simpleName}"
            throw error
        } finally {
            // One bounded asynchronous record per transaction; never payloads or credentials.
            runCatching { trace?.invoke("sessionId=$sessionId sequence=$sequence localPort=$localPort " +
                "startElapsedMs=$startElapsedMs durationMs=${(System.nanoTime()-startNs)/NANOS_PER_MILLISECOND} " +
                "result=$outcome events=$events") }
        }
    }

    private fun networkIdentity(): String = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { network -> network.interfaceAddresses.mapNotNull { address ->
                (address.address as? Inet4Address)?.let {
                    "${network.name}:${it.hostAddress}/${address.networkPrefixLength}"
                }
            } }.sorted().joinToString("|")
    }.getOrDefault("")

    private fun broadcastTargets(): List<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
        interfaces.asSequence()
            .filter { network -> runCatching { network.isUp && !network.isLoopback }.getOrDefault(false) }
            .flatMap { network -> runCatching { network.interfaceAddresses }.getOrDefault(emptyList()).asSequence() }
            .mapNotNull { it.broadcast as? Inet4Address }
            .take(MAX_BROADCAST_TARGETS)
            .forEach(targets::add)
        if (targets.isEmpty()) targets += InetAddress.getByName("255.255.255.255")
        return targets.take(MAX_BROADCAST_TARGETS)
    }

    private companion object {
        const val UDP_PORT = 4213
        const val MAX_SNAPSHOT_BYTES = 8 * 1024
        const val MAX_BROADCAST_TARGETS = 8
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
