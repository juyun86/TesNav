package com.garan.tesnav.teslable

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** One GATT connection; all operations are serialized by the client's single coroutine. */
@SuppressLint("MissingPermission")
internal class TeslaBleTransport(private val context: Context) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    private var write: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var connected = CompletableDeferred<Unit>()
    private var discovered = CompletableDeferred<Unit>()
    private var negotiated = CompletableDeferred<Unit>()
    private var subscribed = CompletableDeferred<Unit>()
    private var written = CompletableDeferred<Unit>()
    private var pending = byteArrayOf()
    private val messages = Channel<ByteArray>(16)
    private var scanCallback: ScanCallback? = null
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (closed) { g.close(); return }
            if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                gatt = g; connected.complete(Unit)
            } else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val error = IllegalStateException("原车蓝牙已断开 ($status)")
                connected.completeExceptionally(error); discovered.completeExceptionally(error)
                negotiated.completeExceptionally(error); subscribed.completeExceptionally(error); written.completeExceptionally(error)
                messages.close(error)
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) discovered.complete(Unit)
            else discovered.completeExceptionally(IllegalStateException("蓝牙服务发现失败 ($status)"))
        }
        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = value
            negotiated.complete(Unit)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) subscribed.complete(Unit)
            else subscribed.completeExceptionally(IllegalStateException("蓝牙通知开启失败 ($status)"))
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) written.complete(Unit)
            else written.completeExceptionally(IllegalStateException("蓝牙查询写入失败 ($status)"))
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { receive(c.value ?: return) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { receive(value) }
    }
    @Synchronized private fun receive(bytes: ByteArray) {
        if (pending.size + bytes.size > 65537) { messages.close(IllegalStateException("蓝牙回执过大")); return }
        pending += bytes
        while (pending.size >= 2) {
            val size = ((pending[0].toInt() and 255) shl 8) or (pending[1].toInt() and 255)
            if (size == 0) { messages.close(IllegalStateException("蓝牙回执长度无效")); return }
            if (pending.size < size + 2) return
            if (!messages.trySend(pending.copyOfRange(2, size + 2)).isSuccess) {
                messages.close(IllegalStateException("蓝牙回执队列已满")); return
            }
            pending = pending.copyOfRange(size + 2, pending.size)
        }
    }
    suspend fun connect(vin: String) {
        require(adapter?.isEnabled == true) { "请先开启手机蓝牙" }
        val scanner = adapter.bluetoothLeScanner ?: error("无法使用蓝牙扫描")
        val device = CompletableDeferred<BluetoothDevice>()
        val name = TeslaNavigationProtocol.localName(vin)
        val scan = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                if (result.scanRecord?.deviceName == name) device.complete(result.device)
            }
            override fun onScanFailed(code: Int) { device.completeExceptionally(IllegalStateException("蓝牙扫描失败 ($code)")) }
        }
        scanCallback = scan
        val target = try {
            scanner.startScan(listOf(ScanFilter.Builder().setDeviceName(name).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
            withTimeout(20000) { device.await() }
        } finally { scanner.stopScan(scan); scanCallback = null }
        gatt = target.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) ?: error("蓝牙连接失败")
        withTimeout(15000) { connected.await() }
        val g = checkNotNull(gatt)
        check(g.discoverServices()) { "无法发现蓝牙服务" }
        withTimeout(10000) { discovered.await() }
        if (g.requestMtu(512)) withTimeout(10000) { negotiated.await() }
        val service = g.getService(UUID.fromString("00000211-b2d1-43f0-9b88-960cebf8b91e")) ?: error("车辆缺少 Tesla BLE 服务")
        write = service.getCharacteristic(UUID.fromString("00000212-b2d1-43f0-9b88-960cebf8b91e")) ?: error("缺少查询特征")
        val read = service.getCharacteristic(UUID.fromString("00000213-b2d1-43f0-9b88-960cebf8b91e")) ?: error("缺少回执特征")
        check(g.setCharacteristicNotification(read, true))
        val descriptor = read.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) ?: error("缺少通知描述符")
        require(read.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
        @Suppress("DEPRECATION")
        descriptor.value = if (read.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        check(g.writeDescriptor(descriptor))
        withTimeout(10000) { subscribed.await() }
    }
    suspend fun send(payload: ByteArray) {
        require(payload.size in 1..65535)
        val framed = byteArrayOf((payload.size ushr 8).toByte(), payload.size.toByte()) + payload
        val g = checkNotNull(gatt); val c = checkNotNull(write)
        require(c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) { "车辆蓝牙不支持可靠查询写入" }
        for (part in framed.toList().chunked((mtu - 3).coerceIn(20, 509))) {
            written = CompletableDeferred()
            @Suppress("DEPRECATION")
            c.value = part.toByteArray()
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            check(g.writeCharacteristic(c)) { "蓝牙查询写入被拒绝" }
            withTimeout(5000) { written.await() }
        }
    }
    suspend fun receiveFor(session: TeslaNavigationSession, request: TeslaNavigationSession.Request): ByteArray = withTimeout(10000) {
        while (true) {
            val response = messages.receive()
            if (session.addressedToUs(response)) {
                // A UUID may be absent over BLE. Cryptographic request binding is still mandatory.
                val uuid = TeslaWire.bytes(response, 50)
                if (uuid == null || uuid.isEmpty() || uuid.contentEquals(request.uuid)) return@withTimeout response
            }
        }
        @Suppress("UNREACHABLE_CODE") byteArrayOf()
    }
    fun close() {
        closed = true
        runCatching { scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) } }
        runCatching { gatt?.disconnect() }; runCatching { gatt?.close() }; gatt = null
        messages.close()
    }
}
