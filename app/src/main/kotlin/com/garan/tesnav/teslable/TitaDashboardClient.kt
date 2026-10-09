package com.garan.tesnav.teslable

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

/** Only SOC/range are enabled; speed has no proven CAN freshness in this firmware. */
internal data class TitaDashboardSample(
    val batteryPercent: Int?, val rangeKm: Float?,
    val receivedAtMs: Long = 0, val refreshCostMs: Long = 100,
)
internal object TitaTrialPacket {
    fun decode(bytes: ByteArray): TitaDashboardSample? {
        if (bytes.size != 76) return null
        val charge = (0..3).fold(0L) { value, i ->
            value or ((bytes[40 + i].toLong() and 255) shl (i * 8))
        }
        val battery = ((charge shr 10) and 127).toInt()
        val range = (charge and 1023).toInt()
        return TitaDashboardSample(
            battery.takeIf { it <= 100 },
            range.takeIf { it != 1023 }?.let { it * 1.609344f },
        )
    }
}

@SuppressLint("MissingPermission")
internal class TitaDashboardClient(context: Context, private val update: (TitaDashboardSample?, String) -> Unit) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var active = false
    private var generation = 0
    private var scanner: BluetoothLeScanner? = null
    private var scan: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var failures = 0
    private var receivedAt = 0L
    private var refreshCostMs = 100L
    private val service = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val characteristic = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    fun start() { if (active) return; active = true; connect() }
    fun stop() { active = false; generation++; handler.removeCallbacksAndMessages(null); close(); update(null, "TITA 已停止") }
    private fun close() {
        try { scan?.let { scanner?.stopScan(it) } } catch (_: Exception) { }
        scan = null
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) { }
        gatt = null
    }
    private fun retry(id: Int, message: String) {
        if (!active || id != generation) return
        generation++; close(); handler.removeCallbacksAndMessages(null)
        update(null, message)
        val delay = longArrayOf(5000, 10000, 20000, 30000)[failures.coerceAtMost(3)]
        failures++
        handler.postDelayed({ if (active) connect() }, delay)
    }
    private fun connect() {
        if (!active) return
        val id = ++generation
        update(null, "正在连接 TITA…")
        try {
            scanner = adapter?.bluetoothLeScanner
            if (scanner == null) { retry(id, "TITA 蓝牙不可用"); return }
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                    handler.post {
                        if (id != generation || !active) { g.close(); return@post }
                        if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                            if (!g.discoverServices()) retry(id, "TITA 服务读取失败")
                        } else if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) retry(id, "TITA 连接中断")
                    }
                }
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    handler.post {
                        if (id != generation || !active) return@post
                        try {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                retry(id, "TITA 服务读取失败"); return@post
                            }
                            // ATT payload must hold the complete 76-byte packet before subscribing.
                            if (!g.requestMtu(185)) retry(id, "TITA 数据长度协商失败")
                        } catch (_: Exception) { retry(id, "TITA 数据长度协商失败") }
                    }
                }
                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    handler.post {
                        if (id != generation || !active) return@post
                        if (status != BluetoothGatt.GATT_SUCCESS || mtu < 79) {
                            retry(id, "TITA 数据长度不足"); return@post
                        }
                        try {
                            val c = g.getService(service)?.getCharacteristic(characteristic)
                            val d = c?.getDescriptor(cccd)
                            if (c == null || d == null || !g.setCharacteristicNotification(c, true)) {
                                retry(id, "TITA 数据服务不匹配"); return@post
                            }
                            @Suppress("DEPRECATION")
                            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            val written = g.writeDescriptor(d)
                            if (!written) retry(id, "TITA 订阅失败")
                        } catch (_: Exception) { retry(id, "TITA 订阅失败") }
                    }
                }
                override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                    handler.post { if (status != 0) retry(id, "TITA 订阅失败") }
                }
                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                    if (c.uuid == characteristic) receive(id, c.value?.clone() ?: return)
                }
                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                    if (c.uuid == characteristic) receive(id, value.clone())
                }
            }
            val scanCallback = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) {
                    val name = result.scanRecord?.deviceName ?: return
                    if (!name.startsWith("TITA", true)) return
                    handler.post {
                        if (id != generation || !active || gatt != null) return@post
                        try {
                            scanner?.stopScan(this)
                            scan = null
                            gatt = result.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                            handler.postDelayed({ if (id == generation && receivedAt == 0L) retry(id, "TITA 等待数据超时") }, 15000)
                        } catch (_: Exception) { retry(id, "TITA 连接失败") }
                    }
                }
                override fun onScanFailed(code: Int) { handler.post { retry(id, "TITA 搜索失败") } }
            }
            receivedAt = 0L
            scan = scanCallback
            scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            handler.postDelayed({ if (id == generation && gatt == null) retry(id, "未发现 TITA") }, 12000)
        } catch (_: SecurityException) { retry(id, "TITA 缺少蓝牙或定位权限") }
        catch (_: Exception) { retry(id, "TITA 连接失败") }
    }
    private val expire = Runnable {
        if (active) retry(generation, "TITA 数据过期 · 已回退 C3")
    }
    private fun receive(id: Int, bytes: ByteArray) {
        val sample = TitaTrialPacket.decode(bytes)
        handler.post {
            if (!active || id != generation) return@post
            if (sample == null) { update(null, "TITA 数据格式待适配"); return@post }
            val now = SystemClock.elapsedRealtime()
            if (receivedAt > 0 && now - receivedAt in 1..2000) refreshCostMs = (refreshCostMs * 3 + now - receivedAt) / 4
            receivedAt = now; failures = 0
            update(sample.copy(receivedAtMs = now, refreshCostMs = refreshCostMs), "TITA 已连接")
            handler.removeCallbacks(expire); handler.postDelayed(expire, 2000)
        }
    }
}
