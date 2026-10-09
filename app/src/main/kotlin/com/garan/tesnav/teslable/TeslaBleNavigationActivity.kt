package com.garan.tesnav.teslable

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import com.garan.tesnav.BuildConfig
import com.garan.tesnav.NavigationActivity
import com.garan.tesnav.service.NavigationForegroundService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** Setup and live status for the one supported BLE feature: original-car destination sync. */
class TeslaBleNavigationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var service: NavigationForegroundService? = null
    private var bound = false
    private var observing: Job? = null
    private var pairing: Job? = null
    private lateinit var vin: EditText
    private lateinit var coordinates: Spinner
    private lateinit var status: TextView
    private lateinit var toggle: Switch
    private lateinit var pairButton: Button
    private var rendering = false
    private val systems = listOf("UNKNOWN", "GCJ02", "WGS84")
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val runtime = (binder as NavigationForegroundService.LocalBinder).getService()
            service = runtime
            vin.setText(runtime.teslaBleClient.vin)
            coordinates.setSelection(systems.indexOf(runtime.teslaBleClient.coordinateSystem).coerceAtLeast(0))
            renderToggle(runtime.teslaBleSyncEnabled.value)
            observing = scope.launch {
                launch { combine(runtime.teslaBleClient.state, runtime.selectedNavigationStateFlow, runtime.teslaAutoSyncStatus) {
                    ble, nav, autoStatus -> Triple(ble, nav, autoStatus) }.collect { (state, nav, autoStatus) ->
                    status.text = state.status + (if (state.destination != null) "\n原车目的地：${state.name.ifBlank { "坐标目的地" }}\n${state.destination.latitude}, ${state.destination.longitude}" else "") +
                        (if (runtime.teslaBleSyncEnabled.value) "\n${runtime.navigationDataSource.value.displayName}：${nav.errorMessage ?: when {
                            nav.navigationMode == com.garan.tesnav.model.NavigationMode.REALTIME -> "正在导航"
                            nav.routePlanned -> "路线已规划"
                            else -> "等待目的地和有效定位"
                        }}" else "") + autoStatus.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty() +
                        state.queryDurationMs?.let { "\n最近一次原车查询耗时：${it} ms（读取后等待 1 秒）" }.orEmpty()
                } }
                launch { runtime.teslaBleSyncEnabled.collect(::renderToggle) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; observing?.cancel(); toggle.isEnabled = false }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.EXTENDED_NAV_SOURCES) { finish(); return }
        val dp = resources.displayMetrics.density
        vin = EditText(this).apply { hint = "原车 VIN（17 位）"; setSingleLine(true) }
        coordinates = Spinner(this).apply {
            adapter = ArrayAdapter(this@TeslaBleNavigationActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("请选择已确认的目的地坐标系", "GCJ02（高德坐标）", "WGS84（GPS 坐标，转换为高德）"))
        }
        status = TextView(this).apply { text = "正在连接导航服务…"; textSize = 16f }
        @Suppress("DEPRECATION")
        toggle = Switch(this).apply {
            text = "自动同步原车目的地（按当前导航来源）"; textSize = 17f; isEnabled = false
            setOnCheckedChangeListener { _, enabled ->
                if (!rendering) {
                    if (enabled) enableSync() else service?.setTeslaBleSyncEnabled(false)
                    service?.let { renderToggle(it.teslaBleSyncEnabled.value) }
                }
            }
        }
        pairButton = Button(this).apply {
            text = "首次配钥"
            setOnClickListener {
                if (!hasPermissions()) { requestPermissions(requiredPermissions(), 1); return@setOnClickListener }
                val runtime = service ?: return@setOnClickListener
                if (runtime.teslaBleSyncEnabled.value) { toast("请先关闭自动同步再配钥"); return@setOnClickListener }
                if (!saveConfiguration()) return@setOnClickListener
                isEnabled = false
                status.text = "正在发送配钥请求…请按原车提示刷卡确认"
                pairing = scope.launch {
                    try { withContext(Dispatchers.IO) { runtime.teslaBleClient.pair() } }
                    catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        status.text = if (e is TimeoutCancellationException) "配钥未能确认，请按原车提示刷卡后重试" else e.message ?: "配钥请求失败"
                    }
                    finally { isEnabled = true }
                }
            }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding((20*dp).toInt(), (16*dp).toInt(), (20*dp).toInt(), (16*dp).toInt())
            addView(TextView(context).apply { text = "原车导航目的地同步"; textSize = 22f })
            addView(TextView(context).apply { text = "原车设置或更换目的地后，高德 API 自动算路；高德车机接收目的地并由车机导航，请按车机提示确认。首次使用需在原车确认配钥。\n请先核对目的地坐标系，避免落点偏移。配置和权限齐全后默认开启，启动应用自动恢复，切换高德来源保持同步；手动关闭后保持关闭。"; textSize = 15f })
            addView(vin); addView(coordinates); addView(pairButton); addView(toggle); addView(status)
            addView(Button(context).apply { text = "查看高德导航"; setOnClickListener { startActivity(Intent(context, NavigationActivity::class.java)) } })
            addView(Button(context).apply { text = "返回"; setOnClickListener { finish() } })
        }
        setContentView(ScrollView(this).apply { addView(body) })
    }
    private fun saveConfiguration(): Boolean = try {
        checkNotNull(service).teslaBleClient.configure(vin.text.toString().trim().uppercase(java.util.Locale.US), systems[coordinates.selectedItemPosition])
        true
    } catch (e: Exception) { toast(e.message ?: "配置保存失败"); false }
    private fun enableSync() {
        if (pairing?.isActive == true) { toast("请等待配钥请求完成"); return }
        if (!hasPermissions()) { requestPermissions(requiredPermissions(), 2); return }
        if (saveConfiguration()) service?.setTeslaBleSyncEnabled(true)?.let(::toast)
    }
    private fun renderToggle(enabled: Boolean) {
        rendering = true; toggle.isChecked = enabled; toggle.isEnabled = service != null
        vin.isEnabled = !enabled; coordinates.isEnabled = !enabled; pairButton.isEnabled = !enabled && pairing?.isActive != true
        rendering = false
    }
    private fun requiredPermissions() = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun hasPermissions() = requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 && hasPermissions()) enableSync()
        else toast(if (hasPermissions()) "蓝牙权限已授予，请点击首次配钥" else "需要蓝牙权限才能读取原车目的地")
        service?.let { renderToggle(it.teslaBleSyncEnabled.value) }
    }
    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, NavigationForegroundService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    override fun onStop() {
        observing?.cancel(); observing = null; pairing?.cancel(); pairing = null
        if (bound) unbindService(connection)
        bound = false; service = null
        super.onStop()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
