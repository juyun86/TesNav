package com.garan.tesnav.teslable

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Narrow wire adapter for Tesla's public vehicle-command protocol. No vehicle-control actions.
 * Field numbers/metadata: github.com/teslamotors/vehicle-command/pkg/protocol/protobuf.
 * Crypto is implemented by JCA, not by custom elliptic-curve code.
 */
internal object TeslaWire {
    data class Field(val number: Int, val type: Int, val bytes: ByteArray, val value: Long = 0)
    fun fields(data: ByteArray): List<Field> {
        require(data.size <= 65535) { "车辆消息过大" }
        var p = 0
        fun varint(): Long {
            var v = 0L
            for (shift in 0..63 step 7) {
                require(p < data.size) { "车辆消息截断" }
                val b = data[p++].toInt() and 255
                require(shift != 63 || b <= 1) { "无效 varint" }
                v = v or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) return v
            }
            error("无效 varint")
        }
        val result = mutableListOf<Field>()
        while (p < data.size) {
            val tag = varint(); val number = (tag ushr 3).toInt(); val type = (tag and 7).toInt()
            require(number > 0 && number <= 0x1fffffff)
            if (type == 0) result += Field(number, type, byteArrayOf(), varint())
            else {
                val length = when (type) { 1 -> 8L; 2 -> varint(); 5 -> 4L; else -> error("不支持的 wire type") }
                require(length in 0L..(data.size - p).toLong())
                result += Field(number, type, data.copyOfRange(p, p + length.toInt()))
                p += length.toInt()
            }
        }
        return result
    }
    fun bytes(data: ByteArray, number: Int): ByteArray? = fields(data).lastOrNull { it.number == number && it.type == 2 }?.bytes
    fun value(data: ByteArray, number: Int): Long = fields(data).lastOrNull { it.number == number && it.type == 0 }?.value ?: 0
    fun fixed(data: ByteArray, number: Int): Int = fields(data).lastOrNull { it.number == number && it.type == 5 }
        ?.bytes?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int } ?: 0
    private fun varint(v: Long): ByteArray {
        require(v >= 0)
        val out = ByteArrayOutputStream(); var n = v
        do { val b = (n and 127).toInt(); n = n ushr 7; out.write(b or if (n != 0L) 128 else 0) } while (n != 0L)
        return out.toByteArray()
    }
    fun b(n: Int, v: ByteArray) = varint((n.toLong() shl 3) or 2) + varint(v.size.toLong()) + v
    fun v(n: Int, v: Long) = varint(n.toLong() shl 3) + varint(v)
    fun f(n: Int, v: Int) = varint((n.toLong() shl 3) or 5) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}

internal data class TeslaRawDestination(val name: String, val latitude: Double, val longitude: Double)

internal object TeslaNavigationProtocol {
    fun validVin(vin: String) = Regex("[A-HJ-NPR-Z0-9]{17}").matches(vin)
    fun localName(vin: String) = "S" + MessageDigest.getInstance("SHA-1").digest(vin.toByteArray(Charsets.US_ASCII))
        .take(8).joinToString("") { "%02x".format(it.toInt() and 255) } + "C"
    // Action.vehicleAction -> VehicleAction.getVehicleData -> GetVehicleData.getDriveState.
    val readDriveState: ByteArray get() = TeslaWire.b(2, TeslaWire.b(1, TeslaWire.b(4, byteArrayOf())))
    val readDriveAndChargeState: ByteArray get() = TeslaWire.b(2, TeslaWire.b(1,
        TeslaWire.b(4, byteArrayOf()) + TeslaWire.b(2, byteArrayOf())))

    /** Tesla vehicle.proto: optional fields retain absence, speed/range are miles based. */
    fun dashboard(response: ByteArray, receivedAt: Long, queryMs: Long, previous: VehicleDashboardData): VehicleDashboardData {
        val status = TeslaWire.bytes(response, 1) ?: error("车辆没有返回查询结果")
        require(TeslaWire.value(status, 1) == 0L)
        val vehicle = TeslaWire.bytes(response, 2) ?: error("车辆没有返回 vehicle_data")
        fun floatField(data: ByteArray, n: Int): Float? = TeslaWire.fields(data)
            .lastOrNull { it.number == n && it.type == 5 }?.bytes?.let {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).float.takeIf { v -> v.isFinite() }
            }
        fun intField(data: ByteArray, n: Int): Long? = TeslaWire.fields(data)
            .lastOrNull { it.number == n && it.type == 0 }?.value
        val drive = TeslaWire.bytes(vehicle, 5)
        val charge = TeslaWire.bytes(vehicle, 3)
        val mph = drive?.let { floatField(it, 106) ?: intField(it, 102)?.toFloat() }
        val speed = mph?.times(1.609344f)?.takeIf { it in 0f..350f }
        val gear = drive?.let { TeslaWire.bytes(it, 1) }?.let { shift ->
            TeslaWire.fields(shift).lastOrNull { it.type == 2 && it.number in 1..6 }?.number
        }?.let { mapOf(2 to "P", 3 to "R", 4 to "N", 5 to "D")[it] }
        val battery = charge?.let { intField(it, 114) }?.takeIf { it in 0..100 }?.toInt()
        val range = charge?.let { floatField(it, 111) }?.times(1.609344f)?.takeIf { it in 0f..1500f }
        // These are estimates for the ORIGINAL-car route, not the AMap route.
        // A retained energy estimate alone must not revive an ended navigation route.
        val routeActive = runCatching { destination(response) }.getOrNull() != null
        val arrivalBattery = if (routeActive) drive?.let { floatField(it, 11) }
            ?.takeIf { it in -100f..100f } else null
        val minutesToArrival = if (routeActive) drive?.let { floatField(it, 8) }
            ?.takeIf { it >= 0f } else null
        return VehicleDashboardData(
            speed = speed?.let { DashboardObservation(it, receivedAt, queryMs, 3000) },
            gear = gear?.let { DashboardObservation(it, receivedAt, queryMs, 3000) },
            arrivalBattery = arrivalBattery?.let { DashboardObservation(it, receivedAt, queryMs, 3000) },
            minutesToArrival = minutesToArrival?.let { DashboardObservation(it, receivedAt, queryMs, 3000) },
            battery = if (charge == null) previous.battery else battery?.let { DashboardObservation(it, receivedAt, queryMs, 15000) },
            range = if (charge == null) previous.range else range?.let { DashboardObservation(it, receivedAt, queryMs, 15000) },
        )
    }
    fun destination(response: ByteArray): TeslaRawDestination? {
        val status = TeslaWire.bytes(response, 1) ?: error("车辆没有返回查询结果")
        require(TeslaWire.value(status, 1) == 0L) { "原车拒绝导航信息查询" }
        val vehicle = TeslaWire.bytes(response, 2) ?: error("车辆没有返回 vehicle_data")
        val drive = TeslaWire.bytes(vehicle, 5) ?: error("车辆没有返回 drive_state")
        val name = TeslaWire.bytes(drive, 7)?.toString(Charsets.UTF_8)?.trim().orEmpty()
        val coords = TeslaWire.bytes(drive, 12) ?: return null
        val fields = TeslaWire.fields(coords)
        fun coordinate(n: Int) = fields.lastOrNull { it.number == n && it.type == 5 }?.bytes
            ?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).float.toDouble() }
        val lat = coordinate(1) ?: return null; val lon = coordinate(2) ?: return null
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0)) {
            "原车目的地坐标无效"
        }
        if (destinationAbsent(response)) return null
        return TeslaRawDestination(name, lat, lon)
    }
    /** This vehicle retains old coordinates after clearing all active route fields. */
    fun destinationAbsent(response: ByteArray): Boolean {
        val status = TeslaWire.bytes(response, 1) ?: error("车辆没有返回查询结果")
        require(TeslaWire.value(status, 1) == 0L) { "原车拒绝导航信息查询" }
        val vehicle = TeslaWire.bytes(response, 2) ?: error("车辆没有返回 vehicle_data")
        val drive = TeslaWire.bytes(vehicle, 5) ?: error("车辆没有返回 drive_state")
        if (!TeslaWire.bytes(drive, 7)?.toString(Charsets.UTF_8)?.trim().isNullOrEmpty() ||
            TeslaWire.fields(drive).any { it.number in 8..9 }) return false
        val coords = TeslaWire.bytes(drive, 12) ?: return true
        val fields = TeslaWire.fields(coords)
        fun coordinate(n: Int) = fields.lastOrNull { it.number == n && it.type == 5 }?.bytes?.let {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).float.toDouble()
        }
        val lat = coordinate(1) ?: return false
        val lon = coordinate(2) ?: return false
        return lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
            (lat != 0.0 || lon != 0.0)
    }

    fun pairRequest(publicKey: ByteArray): ByteArray {
        require(publicKey.size == 65 && publicKey[0] == 4.toByte())
        // VCSEC whitelist add, OWNER role, ANDROID form factor. Sent only by explicit setup action.
        val permissions = TeslaWire.b(1, TeslaWire.b(1, publicKey)) + TeslaWire.v(4, 2)
        val whitelist = TeslaWire.b(5, permissions) + TeslaWire.b(6, TeslaWire.v(1, 7))
        val unsigned = TeslaWire.b(16, whitelist)
        return TeslaWire.b(1, TeslaWire.b(2, unsigned) + TeslaWire.v(3, 2))
    }
    fun publicBytes(key: PublicKey): ByteArray {
        val point = (key as ECPublicKey).w
        fun fixed(x: BigInteger) = x.toByteArray().takeLast(32).toByteArray().let { ByteArray(32-it.size) + it }
        return byteArrayOf(4) + fixed(point.affineX) + fixed(point.affineY)
    }
    fun sharedKey(privateKey: PrivateKey, remote: ByteArray): ByteArray {
        require(remote.size == 65 && remote[0] == 4.toByte())
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, remote.copyOfRange(1, 33)), BigInteger(1, remote.copyOfRange(33, 65)))
        val public = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        val secret = KeyAgreement.getInstance("ECDH").run { init(privateKey); doPhase(public, true); generateSecret() }
        return MessageDigest.getInstance("SHA-1").digest(secret).copyOf(16)
    }
    fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
    fun be32(n: Long) = ByteBuffer.allocate(4).putInt(n.toInt()).array()
    fun metadata(vararg pairs: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tag, bytes) in pairs) { require(bytes.size <= 255); out.write(tag); out.write(bytes.size); out.write(bytes) }
        out.write(255)
        return out.toByteArray()
    }
    fun hmac(key: ByteArray, input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(input)
    }
    fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray {
        require(nonce.size == 12)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(data)
        }
    }
}

internal class TeslaNavigationSession(private val vin: String, private val identity: KeyPair, private val elapsed: () -> Long) {
    private val route = TeslaNavigationProtocol.random(16)
    private var key: ByteArray? = null
    private var epoch = byteArrayOf()
    private var counter = 0L
    private var clock = 0L
    private var establishedAt = 0L
    data class Request(val bytes: ByteArray, val uuid: ByteArray, val responseId: ByteArray = byteArrayOf()) {
        // Tesla response counters increase per request ID, not across the whole session.
        internal var responseCounter = -1L
    }
    private fun envelope(payload: ByteArray, uuid: ByteArray) =
        TeslaWire.b(6, TeslaWire.v(1, 3)) + TeslaWire.b(7, TeslaWire.b(2, route)) + payload + TeslaWire.b(51, uuid)
    fun handshake(): Request {
        val uuid = TeslaNavigationProtocol.random(16)
        return Request(envelope(TeslaWire.b(14, TeslaWire.b(1, TeslaNavigationProtocol.publicBytes(identity.public))), uuid), uuid)
    }
    private fun checkEnvelope(data: ByteArray, uuid: ByteArray) {
        require(TeslaWire.bytes(TeslaWire.bytes(data, 6) ?: byteArrayOf(), 2)?.contentEquals(route) == true) { "原车回执地址不匹配" }
        require(TeslaWire.value(TeslaWire.bytes(data, 7) ?: byteArrayOf(), 1) == 3L) { "原车回执来源不匹配" }
        TeslaWire.bytes(data, 50)?.takeIf { it.isNotEmpty() }?.let { require(it.contentEquals(uuid)) { "原车回执请求不匹配" } }
    }
    fun acceptHandshake(response: ByteArray, request: Request) {
        checkEnvelope(response, request.uuid)
        val encoded = TeslaWire.bytes(response, 15) ?: error("原车未返回认证会话，请先配钥")
        require(TeslaWire.value(encoded, 5) == 0L) { "钥匙未获原车授权，请在车机确认配钥" }
        val remote = TeslaWire.bytes(encoded, 2) ?: error("原车会话缺少公钥")
        val derived = TeslaNavigationProtocol.sharedKey(identity.private, remote)
        val tag = TeslaWire.bytes(TeslaWire.bytes(TeslaWire.bytes(response, 13) ?: byteArrayOf(), 6) ?: byteArrayOf(), 1)
            ?: error("原车会话缺少认证标签")
        val expected = TeslaNavigationProtocol.hmac(TeslaNavigationProtocol.hmac(derived, "session info".toByteArray()),
            TeslaNavigationProtocol.metadata(0 to byteArrayOf(6), 2 to vin.toByteArray(), 6 to request.uuid) + encoded)
        require(MessageDigest.isEqual(tag, expected)) { "原车会话认证失败" }
        epoch = TeslaWire.bytes(encoded, 3) ?: error("原车会话缺少 epoch")
        require(epoch.size == 16)
        counter = TeslaWire.value(encoded, 1); clock = TeslaWire.fixed(encoded, 4).toLong() and 0xffffffffL
        establishedAt = elapsed(); key = derived
    }
    fun query(includeCharge: Boolean = false): Request {
        val k = checkNotNull(key); require(counter < 0xffffffffL); counter++
        val expires = clock + (elapsed() - establishedAt) / 1000 + 10
        val aad = MessageDigest.getInstance("SHA-256").digest(TeslaNavigationProtocol.metadata(
            0 to byteArrayOf(5), 1 to byteArrayOf(3), 2 to vin.toByteArray(), 3 to epoch,
            4 to TeslaNavigationProtocol.be32(expires), 5 to TeslaNavigationProtocol.be32(counter), 7 to TeslaNavigationProtocol.be32(2)))
        val nonce = TeslaNavigationProtocol.random(12)
        val encrypted = TeslaNavigationProtocol.cipher(Cipher.ENCRYPT_MODE, k, nonce, aad, if (includeCharge) TeslaNavigationProtocol.readDriveAndChargeState else TeslaNavigationProtocol.readDriveState)
        val tag = encrypted.takeLast(16).toByteArray()
        val signature = TeslaWire.b(1, TeslaWire.b(1, TeslaNavigationProtocol.publicBytes(identity.public))) +
            TeslaWire.b(5, TeslaWire.b(1, epoch) + TeslaWire.b(2, nonce) + TeslaWire.v(3, counter) +
                TeslaWire.f(4, expires.toInt()) + TeslaWire.b(5, tag))
        val uuid = TeslaNavigationProtocol.random(16)
        return Request(envelope(TeslaWire.b(10, encrypted.dropLast(16).toByteArray()) + TeslaWire.b(13, signature), uuid) +
            TeslaWire.v(52, 2), uuid, byteArrayOf(5) + tag)
    }
    fun decrypt(response: ByteArray, request: Request): ByteArray {
        checkEnvelope(response, request.uuid)
        val status = TeslaWire.bytes(response, 12) ?: byteArrayOf()
        val fault = TeslaWire.value(status, 2)
        if (fault != 0L) throw TeslaQueryFault(fault)
        val sig = TeslaWire.bytes(TeslaWire.bytes(response, 13) ?: byteArrayOf(), 9) ?: error("原车回执未加密")
        val count = TeslaWire.value(sig, 2)
        require(count > request.responseCounter) { "原车回执重复或过期" }
        val aad = MessageDigest.getInstance("SHA-256").digest(TeslaNavigationProtocol.metadata(
            0 to byteArrayOf(9), 1 to byteArrayOf(3), 2 to vin.toByteArray(), 5 to TeslaNavigationProtocol.be32(count),
            7 to TeslaNavigationProtocol.be32(TeslaWire.value(response, 52)), 8 to request.responseId, 9 to TeslaNavigationProtocol.be32(0)))
        val plain = TeslaNavigationProtocol.cipher(Cipher.DECRYPT_MODE, checkNotNull(key),
            TeslaWire.bytes(sig, 1) ?: error("回执缺少 nonce"), aad,
            (TeslaWire.bytes(response, 10) ?: error("回执缺少数据")) + (TeslaWire.bytes(sig, 3) ?: error("回执缺少 tag")))
        request.responseCounter = count
        return plain
    }
    fun addressedToUs(data: ByteArray) = TeslaWire.bytes(TeslaWire.bytes(data, 6) ?: byteArrayOf(), 2)?.contentEquals(route) == true
}
