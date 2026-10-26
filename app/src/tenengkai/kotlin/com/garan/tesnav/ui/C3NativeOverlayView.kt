package com.garan.tesnav.ui

import android.content.*
import android.graphics.*
import android.os.*
import android.view.View
import com.garan.tesnav.BuildConfig
import com.garan.tesnav.export.NAVASSIST_UDP_RECEIVE_WINDOW_MS
import com.garan.tesnav.export.NavAssistV2Protocol
import com.garan.tesnav.model.*
import com.garan.tesnav.service.NavigationDataSource
import com.garan.tesnav.service.NavigationForegroundService
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** C3 projections above the native display; raw frames sent to AMap remain untouched. */
internal class C3NativeOverlayView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var viewport = RectF()
    private var active = false
    private var scope: CoroutineScope? = null
    private var bound = false
    private var navJob: Job? = null
    private var nav = NavigationState()
    private var feedback = OemVehicleLaneState()
    private var frame: ArVideoFrame? = null
    private var cameraOpen = false
    private var lanes = emptyList<ArRoadLane>()
    private var lines = emptyList<ArRoadLane>()
    private var edges = emptyList<ArRoadLane>()
    private var leads = emptyList<ArRoadLead>()
    private val budget = BuildConfig.NAV_ASSIST_V2_INTERVAL_MS.coerceAtLeast(NavAssistV2Protocol.MIN_INTERVAL_MS) + NAVASSIST_UDP_RECEIVE_WINDOW_MS
    private val expire = Runnable { frame = null; invalidate() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? NavigationForegroundService.LocalBinder)?.getService() ?: return
            navJob?.cancel()
            navJob = scope?.launch {
                combine(service.amapAutoNavigationState, service.navigationDataSource,
                    service.oemVehicleLaneState.withDisplayExpiry(budget, SystemClock::elapsedRealtime)) { state, source, oem ->
                    (if (source == NavigationDataSource.AMAP_AUTO) state ?: NavigationState() else NavigationState()) to oem
                }.collect { (state, oem) -> nav = state; feedback = oem; invalidate() }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            navJob?.cancel(); nav = NavigationState(); feedback = OemVehicleLaneState(); invalidate()
        }
    }
    fun setViewport(left: Float, top: Float, width: Float, height: Float) {
        viewport.set(left, top, left+width, top+height); invalidate()
    }
    fun setActive(value: Boolean) { active = value; if (value) start() else stop() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    private fun start() {
        if (!active || !isAttachedToWindow || scope != null) return
        scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        bound = context.bindService(Intent(context, NavigationForegroundService::class.java), connection, Context.BIND_AUTO_CREATE)
        scope!!.launch {
            combine(ArVideoFrames.latest, ArVideoFrames.nativeCameraOpen,
                ArVideoFrames.nativeArPreferred) { incoming, open, preferNative -> Triple(incoming, open, preferNative) }
                .collect { (incoming, open, preferNative) ->
                    cameraOpen = open
                    val native = incoming != null && runCatching {
                        JsonParser.parseString(incoming.geometry).asJsonObject["inputMode"]?.asString == "native"
                    }.getOrDefault(false)
                    frame = incoming?.takeIf { !preferNative && native && open && it.generation == ArVideoFrames.generation &&
                        SystemClock.elapsedRealtime()-it.received in 0..500 }
                    val json = frame?.geometry.orEmpty()
                    lanes = ArRoadGeometry.parse(json)
                    lines = ArRoadGeometry.parsePaths(json, "linePaths")
                    edges = ArRoadGeometry.parsePaths(json, "edgePaths")
                    leads = ArRoadGeometry.parseLeads(json)
                    removeCallbacks(expire)
                    frame?.let { postDelayed(expire, (501-(SystemClock.elapsedRealtime()-it.received)).coerceAtLeast(0)) }
                    invalidate()
                }
        }
    }
    private fun stop() {
        scope?.cancel(); scope = null; navJob = null
        if (bound) context.unbindService(connection)
        bound = false; frame = null; cameraOpen = false
        lanes = emptyList(); lines = emptyList(); edges = emptyList(); leads = emptyList()
        nav = NavigationState(); feedback = OemVehicleLaneState()
        removeCallbacks(expire); invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = frame ?: return
        val now = SystemClock.elapsedRealtime()
        if (!active || !cameraOpen || current.generation != ArVideoFrames.generation ||
            now-current.received !in 0..500 || viewport.isEmpty) return
        val w = viewport.width(); val h = viewport.height()
        fun path(points: List<Pair<Float, Float>>, closed: Boolean = false) = Path().apply {
            points.forEachIndexed { index, (x,y) ->
                if (index == 0) moveTo(viewport.left+x*w, viewport.top+y*h)
                else lineTo(viewport.left+x*w, viewport.top+y*h)
            }
            if (closed) close()
        }
        canvas.save(); canvas.clipRect(viewport)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2*resources.displayMetrics.density
        for ((paths, color) in listOf(lines to Color.WHITE, edges to Color.rgb(255,70,70))) {
            for (line in paths) {
                paint.color = color; paint.alpha = (line.alpha.coerceIn(0f,.7f)*255).toInt()
                canvas.drawPath(path(line.points), paint)
            }
        }
        paint.alpha = 255
        for (arrow in ArRoadGeometry.navigationArrows(nav, feedback, lanes, now, System.currentTimeMillis(), budget)) {
            val shape = path(arrow.points, true)
            paint.style = Paint.Style.FILL
            paint.color = when (arrow.kind) {
                "intent" -> Color.rgb(60,170,255)
                "recommended" -> Color.rgb(40,230,130)
                "direction" -> Color.rgb(255,195,60)
                else -> Color.WHITE
            }
            canvas.drawPath(shape, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density
            paint.color = Color.argb(210,240,255,255); canvas.drawPath(shape,paint)
        }
        for (lead in leads) {
            val x = viewport.left+lead.x*w; val y = viewport.top+lead.y*h
            val size = (750f/(lead.distance/3+30)).coerceIn(15f,30f)*w/960f
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2*resources.displayMetrics.density
            paint.color = if (lead.radar) Color.rgb(255,215,0) else Color.WHITE
            canvas.drawPath(Path().apply { moveTo(x-size,y-size); lineTo(x,y); lineTo(x+size,y-size) },paint)
        }
        canvas.restore()
    }
}
