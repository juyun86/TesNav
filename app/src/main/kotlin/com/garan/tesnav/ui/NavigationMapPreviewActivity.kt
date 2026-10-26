package com.garan.tesnav.ui

import android.app.Activity
import android.content.*
import android.graphics.*
import android.os.*
import android.view.Gravity
import android.widget.*
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.CustomMapStyleOptions
import com.amap.api.maps.model.LatLng
import com.garan.tesnav.BuildConfig
import com.garan.tesnav.config.AmapConfiguration
import com.garan.tesnav.model.GeoPoint
import com.garan.tesnav.service.NavigationDataSource
import com.garan.tesnav.service.NavigationForegroundService
import com.google.gson.Gson
import java.io.File

/** Foreground, manually triggered diagnostic capture. No model/control exporter. */
class NavigationMapPreviewActivity : Activity() {
    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var capture: Button
    private lateinit var latitude: EditText
    private lateinit var longitude: EditText
    private lateinit var bearing: EditText
    private lateinit var styleId: EditText
    private var service: NavigationForegroundService? = null
    private var bound = false
    private var resumed = false
    private var mapLoaded = false
    private var calibrated = false
    private var styleRequested = false
    private var styleFailure = false
    private var styleSource = "none"
    private var pose: NavigationMapGeometry.Pose? = null
    private var route = emptyList<GeoPoint>()
    private var revision: Long? = null
    private var sourceSnapshot: Map<String, Any?>? = null
    private var calibrationRounds = 0
    private var generation = 0
    private var pending = false
    private var captureId = 0
    private val bundledStyleDir by lazy {
        runCatching {
            File(filesDir, "navigation-map-style-bundled").apply {
                check(isDirectory || mkdirs())
                for (name in listOf("style.data", "style_extra.data")) {
                    assets.open("navigation-map-style/$name").use { input ->
                        File(this, name).outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }.getOrNull()
    }
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { pending = false; capture.isEnabled = calibrated; status.text = "截图超时，可手动重试" }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? NavigationForegroundService.LocalBinder)?.getService()
            readCurrent()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG || !AmapConfiguration.prepare(applicationContext)) { finish(); return }
        status = TextView(this).apply { text = "底图比较原型：仅手动导图，尚未验证模型兼容性" }
        latitude = field("GCJ-02 纬度")
        longitude = field("GCJ-02 经度")
        bearing = field("朝向角度").apply { setText("0") }
        styleId = field("高德样式 ID（可选；留空使用内置实验样式）")
        preview = ImageView(this)
        mapView = MapView(this)
        capture = Button(this).apply { text = "保存对照图片"; isEnabled = false; setOnClickListener { captureMap() } }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        content.addView(status)
        content.addView(latitude); content.addView(longitude); content.addView(bearing); content.addView(styleId)
        content.addView(Button(this).apply { text = "读取当前高德路线"; setOnClickListener { readCurrent() } })
        content.addView(Button(this).apply { text = "加载样式并定位"; setOnClickListener { positionMap() } })
        content.addView(mapView, LinearLayout.LayoutParams(512, 512)) // Physical pixels, independent of dp density.
        content.addView(capture)
        content.addView(preview, LinearLayout.LayoutParams(256, 256))
        root.addView(ScrollView(this).apply { addView(content) })
        setContentView(root)
        mapView.onCreate(savedInstanceState)
        mapView.map.apply {
            uiSettings.setAllGesturesEnabled(false)
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isScaleControlsEnabled = false
            uiSettings.isCompassEnabled = false
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.isIndoorSwitchEnabled = false
            isTrafficEnabled = false
            isMyLocationEnabled = false
            showMapText(false); showBuildings(false); showIndoorMap(false)
            setOnMapLoadedListener { mapLoaded = true; calibrate() }
            setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                override fun onCameraChange(position: CameraPosition?) { calibrated = false; capture.isEnabled = false }
                override fun onCameraChangeFinish(position: CameraPosition?) { calibrate() }
            })
        }
    }

    private fun field(hintText: String) = EditText(this).apply { hint = hintText; setSingleLine(true) }

    private fun readCurrent() {
        if (pending) return
        generation++; calibrated = false; capture.isEnabled = false; pose = null
        route = emptyList(); revision = null; sourceSnapshot = null
        val current = service ?: run { status.text = "导航服务未连接，也可手动填写坐标"; return }
        if (current.navigationDataSource.value != NavigationDataSource.AMAP_API) {
            status.text = "当前不是高德 API 来源，可手动填写坐标；不会使用车机简化路线"
            return
        }
        val state = current.stateStore.state.value
        val lat = state.latitude ?: return
        val lon = state.longitude ?: return
        sourceSnapshot = mapOf(
            "navigationMode" to state.navigationMode.name,
            "simulationPaused" to state.simulationPaused,
            "locationObservedAtMs" to state.locationObservedAtMs,
            "navAssistControlAllowed" to state.navAssistControlAllowed,
        )
        route = current.modelMapPreviewRoute()?.points.orEmpty()
        revision = state.routeRevision.takeIf { route.isNotEmpty() }
        val snapshotPose = NavigationMapGeometry.routePose(route, GeoPoint(lat, lon))
            ?: NavigationMapGeometry.Pose(GeoPoint(lat, lon), state.bearing?.toDouble() ?: 0.0)
        latitude.setText(snapshotPose.point.latitude.toString())
        longitude.setText(snapshotPose.point.longitude.toString())
        bearing.setText(snapshotPose.bearing.toString())
        status.text = "已冻结 ${state.navigationMode.name} 快照，原始路线 ${route.size} 点；可编辑坐标检查其它路口"
    }

    private fun positionMap() {
        if (pending) return
        val lat = latitude.text.toString().toDoubleOrNull()
        val lon = longitude.text.toString().toDoubleOrNull()
        val heading = bearing.text.toString().toDoubleOrNull()
        if (lat == null || lon == null || heading == null || !heading.isFinite() ||
            !NavigationMapGeometry.valid(GeoPoint(lat, lon))) { status.text = "坐标或朝向无效"; return }
        generation++
        pose = NavigationMapGeometry.Pose(GeoPoint(lat, lon), (heading % 360 + 360) % 360)
        calibrated = false; capture.isEnabled = false; calibrationRounds = 0
        val externalStyleDir = File(getExternalFilesDir(null) ?: filesDir, "navigation-map-style")
        val styleDir = if (File(externalStyleDir, "style.data").isFile) externalStyleDir else bundledStyleDir ?: externalStyleDir
        val style = File(styleDir, "style.data")
        val onlineStyle = styleId.text.toString().trim()
        if (onlineStyle.length > 128) { status.text = "样式 ID 过长"; return }
        val localStyle = style.isFile && style.length() in 1..2_097_152
        styleRequested = onlineStyle.isNotEmpty() || localStyle
        styleSource = when {
            onlineStyle.isNotEmpty() -> "online-with-local-fallback"
            localStyle && styleDir == externalStyleDir -> "external-file"
            localStyle -> "bundled-prototype"
            else -> "sdk-night"
        }
        styleFailure = false
        mapView.map.mapType = if (styleRequested) AMap.MAP_TYPE_NORMAL else AMap.MAP_TYPE_NIGHT
        val options = CustomMapStyleOptions().setEnable(styleRequested)
        if (onlineStyle.isNotEmpty()) options.setStyleId(onlineStyle)
        if (localStyle) {
            options.setStyleDataPath(style.absolutePath)
            File(styleDir, "style_extra.data").takeIf { it.isFile && it.length() in 1..2_097_152 }
                ?.let { options.setStyleExtraPath(it.absolutePath) }
        }
        val requestGeneration = generation
        mapView.map.setCustomMapStyle(options) { _, _ ->
            if (requestGeneration == generation) { styleFailure = true; status.text = "高德样式加载失败，只能保存诊断图片" }
        }
        mapView.map.setPointToCenter(256, 320) // Crop (128,128)..(384,384) -> ego (128,192).
        mapView.map.moveCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition(LatLng(lat, lon), 17f, 0f, pose!!.bearing.toFloat())))
    }

    private fun calibrate() {
        if (!resumed || !mapLoaded || pose == null || pending) return
        val scale = measuredScale(pose!!)
        if (!scale.isFinite() || scale <= 0) return
        if (!NavigationMapGeometry.scaleMatches(scale) && calibrationRounds++ < 3) {
            mapView.map.moveCamera(CameraUpdateFactory.zoomTo(
                NavigationMapGeometry.correctedZoom(mapView.map.cameraPosition.zoom, scale)))
            return
        }
        calibrated = NavigationMapGeometry.scaleMatches(scale)
        capture.isEnabled = calibrated
        status.text = if (!calibrated) "比例未达到 1米/像素，暂不能导出" else
            "比例 ${scale}米/像素；" + if (styleRequested && !styleFailure) "已请求自定义样式，效果待核验" else "厂商默认样式，仅可生成诊断图"
    }

    private fun measuredScale(snapshotPose: NavigationMapGeometry.Pose): Float {
        val point = snapshotPose.point
        val northPoint = GeoPoint(point.latitude + 100 / 111319.49079327357, point.longitude)
        val ego = mapView.map.projection.toScreenLocation(LatLng(point.latitude, point.longitude))
        val north = mapView.map.projection.toScreenLocation(LatLng(northPoint.latitude, northPoint.longitude))
        val reference = NavigationMapGeometry.project(northPoint, snapshotPose)
        return runCatching {
            NavigationMapGeometry.measuredScale(
                kotlin.math.hypot(reference.x - 128, reference.y - 192),
                kotlin.math.hypot((north.x - ego.x).toDouble(), (north.y - ego.y).toDouble()),
            )
        }.getOrDefault(Float.NaN)
    }

    private fun captureMap() {
        val snapshotPose = pose ?: return
        if (!calibrated || pending || !resumed) return
        pending = true; capture.isEnabled = false
        val requestId = ++captureId
        val requestGeneration = generation
        val started = SystemClock.elapsedRealtime()
        handler.postDelayed(timeout, 5_000)
        mapView.map.getMapScreenShot(object : AMap.OnMapScreenShotListener {
            override fun onMapScreenShot(bitmap: Bitmap?) = Unit // Use the callback with render status only.
            override fun onMapScreenShot(bitmap: Bitmap?, renderStatus: Int) {
                if (!pending || !resumed || requestGeneration != generation || requestId != captureId) return
                pending = false; handler.removeCallbacks(timeout); capture.isEnabled = calibrated
                if (bitmap == null || renderStatus == 0 || bitmap.width != 512 || bitmap.height != 512) {
                    status.text = "地图截图未保存：renderStatus=$renderStatus，尺寸=${bitmap?.width}×${bitmap?.height}"; return
                }
                val ego = mapView.map.projection.toScreenLocation(LatLng(snapshotPose.point.latitude, snapshotPose.point.longitude))
                val northPoint = GeoPoint(snapshotPose.point.latitude + 100 / 111319.49079327357, snapshotPose.point.longitude)
                val north = mapView.map.projection.toScreenLocation(LatLng(northPoint.latitude, northPoint.longitude))
                val referenceNorth = NavigationMapGeometry.project(northPoint, snapshotPose)
                val referenceError = kotlin.math.hypot(north.x - 128.0 - referenceNorth.x, north.y - 128.0 - referenceNorth.y)
                val measured100mPixels = kotlin.math.hypot((north.x - ego.x).toDouble(), (north.y - ego.y).toDouble())
                val scale = measuredScale(snapshotPose)
                if (!NavigationMapGeometry.scaleMatches(scale) || kotlin.math.abs(measured100mPixels - 100) > 5 || referenceError > 5 ||
                    kotlin.math.abs(ego.x - 256) > 2 || kotlin.math.abs(ego.y - 320) > 2) {
                    status.text = "截图未保存：比例=$scale，北向距离=$measured100mPixels，投影误差=$referenceError，车辆=(${ego.x},${ego.y})"; return
                }
                runCatching {
                    saveCapture(bitmap, snapshotPose, scale, ego, measured100mPixels, referenceError, SystemClock.elapsedRealtime() - started)
                }.onFailure { status.text = "保存图片失败：${it.javaClass.simpleName}" }
            }
        })
    }

    private fun saveCapture(raw: Bitmap, snapshotPose: NavigationMapGeometry.Pose, scale: Float, ego: Point,
        measured100mPixels: Double, referenceError: Double, latency: Long) {
        val output = File(getExternalFilesDir(null) ?: filesDir, "navigation-map-captures/${System.currentTimeMillis()}")
        check(output.mkdirs())
        fun save(bitmap: Bitmap, name: String) { File(output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        save(raw, "sdk.png") // Preserve the unmodified SDK output, including its logo.
        val crop = Bitmap.createBitmap(raw, 128, 128, 256, 256)
        val grey = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        Canvas(grey).drawBitmap(crop, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        })
        save(grey, "basemap-grey.png")
        val canvas = Canvas(grey)
        val path = Path()
        route.forEachIndexed { index, point ->
            val pixel = mapView.map.projection.toScreenLocation(LatLng(point.latitude, point.longitude))
            if (index == 0) path.moveTo(pixel.x - 128f, pixel.y - 128f) else path.lineTo(pixel.x - 128f, pixel.y - 128f)
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(128, 128, 128); style = Paint.Style.STROKE; strokeWidth = 5f
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        })
        val arrow = Path().apply { moveTo(128f, 183f); lineTo(135f, 199f); lineTo(128f, 195f); lineTo(121f, 199f); close() }
        canvas.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f })
        save(grey, "comparison.png")
        File(output, "metadata.json").writeText(Gson().toJson(mapOf(
            "provider" to "AMAP_SDK", "coordinateSystem" to "gcj02", "pose" to snapshotPose,
            "scaleMetersPerPixel" to scale, "egoSdkPixel" to mapOf("x" to ego.x, "y" to ego.y),
            "sdkReportedScaleMetersPerPixel" to mapView.map.scalePerPixel,
            "north100mPixels" to measured100mPixels,
            "referenceProjectionErrorPixels" to referenceError, "sdkMapType" to mapView.map.mapType,
            "size" to 256, "routeRevision" to revision, "routePoints" to route, "captureLatencyMs" to latency,
            "sourceSnapshot" to sourceSnapshot,
            "styleSource" to styleSource, "styleRequested" to styleRequested, "styleFailure" to styleFailure, "modelCompatible" to false)))
        preview.setImageBitmap(grey)
        crop.recycle()
        status.text = "已保存对照图：${output.name}；模型兼容性仍待核验"
    }

    override fun onStart() {
        super.onStart()
        if (::mapView.isInitialized) bound = bindService(Intent(this, NavigationForegroundService::class.java), connection, 0)
    }
    override fun onResume() { super.onResume(); if (::mapView.isInitialized) { resumed = true; mapView.onResume(); calibrate() } }
    override fun onPause() {
        resumed = false; pending = false; generation++; handler.removeCallbacks(timeout)
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }
    override fun onStop() { if (bound) { unbindService(connection); bound = false }; service = null; super.onStop() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); if (::mapView.isInitialized) mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); if (::mapView.isInitialized) mapView.onSaveInstanceState(outState) }
}
