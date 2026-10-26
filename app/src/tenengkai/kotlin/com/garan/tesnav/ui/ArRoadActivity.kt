package com.garan.tesnav.ui

import android.app.Activity
import android.content.*
import android.graphics.*
import android.os.*
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.garan.tesnav.BuildConfig
import com.garan.tesnav.export.NAVASSIST_UDP_RECEIVE_WINDOW_MS
import com.garan.tesnav.export.NavAssistV2Protocol
import com.garan.tesnav.model.*
import com.garan.tesnav.service.NavigationDataSource
import com.garan.tesnav.service.NavigationForegroundService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Own display surface: never paints into images consumed by AMap's native recognizer. */
class ArRoadActivity : Activity() {
    private lateinit var road: RoadView
    private lateinit var guidance: AutoNavigationGuidanceView
    private var scope: CoroutineScope? = null
    private var observations: Job? = null
    private var bound = false
    private var decodeLoggedAt = 0L
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? NavigationForegroundService.LocalBinder)?.getService() ?: return
            observations?.cancel()
            observations = scope?.launch {
                combine(service.amapAutoNavigationState, service.navigationDataSource,
                    service.oemVehicleLaneState.withDisplayExpiry(road.budget, SystemClock::elapsedRealtime)) { nav, source, feedback ->
                    (if (source == NavigationDataSource.AMAP_AUTO) nav ?: NavigationState() else NavigationState()) to feedback
                }.collect { (nav, feedback) ->
                    road.nav = nav; road.feedback = feedback; road.invalidate()
                    guidance.render(nav, AutoNavigationDisplayPolicy.guidanceFresh(nav, SystemClock.elapsedRealtime()))
                    guidance.visibility = if (nav.routePlanned) View.VISIBLE else View.GONE
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            observations?.cancel(); road.nav = NavigationState(); road.feedback = OemVehicleLaneState(); road.invalidate()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        road = RoadView()
        guidance = AutoNavigationGuidanceView(this, compact = true).apply { visibility = View.GONE }
        setContentView(object : FrameLayout(this) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                val d = resources.displayMetrics.density
                guidance.layoutParams = guidance.layoutParams.apply {
                    width = minOf((300*d).toInt(), (w-128*d).toInt().coerceAtLeast(1))
                }
            }
        }.apply {
            addView(road, FrameLayout.LayoutParams(-1,-1))
            val d = resources.displayMetrics.density
            addView(guidance, FrameLayout.LayoutParams((300*d).toInt(),(84*d).toInt(), Gravity.TOP or Gravity.START).apply {
                leftMargin=(12*d).toInt(); topMargin=(12*d).toInt()
            })
            addView(TextView(this@ArRoadActivity).apply {
                text = "返回高德"; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(225, 13, 25, 33))
                contentDescription = "返回高德界面，保留当前导航"
                setOnClickListener { finish() }
            }, FrameLayout.LayoutParams((92*d).toInt(), (48*d).toInt(), Gravity.TOP or Gravity.END).apply {
                rightMargin=(12*d).toInt(); topMargin=(104*d).toInt()
            })
            addView(TextView(this@ArRoadActivity).apply {
                text = "详情"; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                contentDescription = "展开或收起实景选道详情"
                setOnClickListener {
                    road.details = !road.details
                    text = if (road.details) "收起" else "详情"
                    road.invalidate()
                }
            }, FrameLayout.LayoutParams((64*d).toInt(), (48*d).toInt(), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin=(12*d).toInt(); bottomMargin=(12*d).toInt()
            })
        })
    }
    override fun onStart() {
        super.onStart()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        startForegroundService(Intent(this, com.garan.tesnav.ar.C3ArCameraService::class.java).setAction("ROAD_START"))
        bound = bindService(Intent(this, NavigationForegroundService::class.java), connection, Context.BIND_AUTO_CREATE)
        scope!!.launch {
            // StateFlow conflates pending frames; finish one decode instead of cancelling
            // every decode when the source is faster than this phone.
            ArVideoFrames.latest.collect { incoming ->
                val frame = incoming
                val decodeStarted = SystemClock.elapsedRealtime()
                val decoded = if (frame == null) null else withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
                }
                val decodedAt = SystemClock.elapsedRealtime()
                if (frame != null && decodedAt-decodeLoggedAt >= 5_000) {
                    decodeLoggedAt = decodedAt
                    android.util.Log.i("Tenengkai-ArDecode", "decodeMs=${decodedAt-decodeStarted} frameAgeMs=${decodedAt-frame.received}")
                }
                if (frame != null && (frame.generation != ArVideoFrames.generation ||
                        SystemClock.elapsedRealtime()-frame.received !in 0..500)) {
                    decoded?.recycle()
                    return@collect
                }
                road.image = decoded; road.received = frame?.received ?: 0
                road.lanes = frame?.let { ArRoadGeometry.parse(it.geometry) }.orEmpty()
                road.linePaths = frame?.let { ArRoadGeometry.parsePaths(it.geometry, "linePaths") }.orEmpty()
                road.edgePaths = frame?.let { ArRoadGeometry.parsePaths(it.geometry, "edgePaths") }.orEmpty()
                road.leads = frame?.let { ArRoadGeometry.parseLeads(it.geometry) }.orEmpty()
                road.removeCallbacks(road.expire)
                road.postDelayed(road.expire, (501-(SystemClock.elapsedRealtime()-road.received)).coerceAtLeast(0))
                road.invalidate()
            }
        }
    }
    override fun onStop() {
        startService(Intent(this, com.garan.tesnav.ar.C3ArCameraService::class.java).setAction("ROAD_STOP"))
        observations?.cancel(); scope?.cancel(); scope = null
        if (bound) unbindService(connection)
        bound = false
        road.removeCallbacks(road.expire); road.image = null; road.lanes = emptyList()
        road.linePaths = emptyList(); road.edgePaths = emptyList()
        road.leads = emptyList()
        road.feedback = OemVehicleLaneState(); road.nav = NavigationState()
        ArVideoFrames.clear()
        super.onStop()
    }

    private inner class RoadView : View(this@ArRoadActivity) {
        val budget = BuildConfig.NAV_ASSIST_V2_INTERVAL_MS.coerceAtLeast(NavAssistV2Protocol.MIN_INTERVAL_MS) + NAVASSIST_UDP_RECEIVE_WINDOW_MS
        var nav = NavigationState()
        var feedback = OemVehicleLaneState()
        var image: Bitmap? = null
        var received = 0L
        var lanes = emptyList<ArRoadLane>()
        var linePaths = emptyList<ArRoadLane>()
        var edgePaths = emptyList<ArRoadLane>()
        var leads = emptyList<ArRoadLead>()
        var details = false
        val expire = Runnable {
            if (image != null) android.util.Log.i("Tenengkai-ArDecode", "expired frameAgeMs=${SystemClock.elapsedRealtime()-received}")
            image = null; lanes = emptyList(); linePaths = emptyList(); edgePaths = emptyList(); leads = emptyList(); invalidate()
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.BLACK)
            val now = SystemClock.elapsedRealtime(); val wall = System.currentTimeMillis()
            val bitmap = image?.takeIf { now-received in 0..500 }
            val targets = ArRoadGeometry.recommendedRelative(nav, feedback, now, wall, budget)
            val intentLane = ArRoadGeometry.decisionRelative(nav, feedback, now, wall, budget)
            val arrows = if (bitmap != null) ArRoadGeometry.navigationArrows(nav, feedback, lanes, now, wall, budget) else emptyList()
            if (bitmap != null) {
                val scale = minOf(width.toFloat()/bitmap.width, height.toFloat()/bitmap.height)
                val w = bitmap.width*scale; val h = bitmap.height*scale
                val rect = RectF((width-w)/2, (height-h)/2, (width+w)/2, (height+h)/2)
                paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                canvas.drawBitmap(bitmap, null, rect, paint)
                canvas.save(); canvas.clipRect(rect)
                fun drawObserved(paths: List<ArRoadLane>, red: Boolean) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2*resources.displayMetrics.density
                    for (line in paths) {
                        val path = Path()
                        line.points.forEachIndexed { index, (x, y) ->
                            if (index == 0) path.moveTo(rect.left+x*w, rect.top+y*h)
                            else path.lineTo(rect.left+x*w, rect.top+y*h)
                        }
                        paint.color = if (red) Color.argb((line.alpha*255).toInt(),255,70,70)
                            else Color.argb((line.alpha.coerceAtMost(.7f)*255).toInt(),255,255,255)
                        canvas.drawPath(path,paint) // Open lines: never close edges into a road polygon.
                    }
                }
                for (lane in lanes.filter { it.relative in targets || it.relative == intentLane }) {
                    val path = Path()
                    lane.points.forEachIndexed { index, (x, y) ->
                        if (index == 0) path.moveTo(rect.left+x*w, rect.top+y*h)
                        else path.lineTo(rect.left+x*w, rect.top+y*h)
                    }
                    path.close()
                    if (lane.relative in targets) {
                        paint.style = Paint.Style.FILL; paint.color = Color.argb(65, 40, 230, 130)
                        canvas.drawPath(path, paint)
                    }
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 2*resources.displayMetrics.density
                    paint.color = if (lane.relative == intentLane) Color.rgb(60, 170, 255) else Color.rgb(40, 230, 130)
                    canvas.drawPath(path, paint)
                }
                drawObserved(linePaths, false)
                drawObserved(edgePaths, true)
                for (arrow in arrows) {
                    val path = Path()
                    arrow.points.forEachIndexed { index, (x,y) ->
                        if (index == 0) path.moveTo(rect.left+x*w,rect.top+y*h)
                        else path.lineTo(rect.left+x*w,rect.top+y*h)
                    }
                    path.close()
                    paint.style = Paint.Style.FILL
                    paint.color = when (arrow.kind) {
                        "intent" -> Color.rgb(60,170,255)
                        "recommended" -> Color.rgb(40,230,130)
                        "direction" -> Color.rgb(255,195,60)
                        else -> Color.WHITE
                    }
                    val arrowColor = paint.color
                    // Shallow screen-space depth; the ground geometry stays unchanged.
                    canvas.save(); canvas.translate(0f, 2*resources.displayMetrics.density)
                    paint.color = Color.argb(140, 0, 20, 28); canvas.drawPath(path, paint)
                    canvas.restore()
                    val bounds = RectF(); path.computeBounds(bounds, true)
                    paint.shader = LinearGradient(0f, bounds.top, 0f, bounds.bottom,
                        arrowColor, Color.rgb(Color.red(arrowColor)*4/5, Color.green(arrowColor)*4/5,
                            Color.blue(arrowColor)*4/5), Shader.TileMode.CLAMP)
                    paint.color = arrowColor; canvas.drawPath(path,paint); paint.shader = null
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density
                    paint.color = Color.argb(210,240,255,255); canvas.drawPath(path,paint)
                }
                for (lead in leads) {
                    val x = rect.left+lead.x*w; val y = rect.top+lead.y*h
                    val density = resources.displayMetrics.density
                    val size = (750f/(lead.distance/3+30)).coerceIn(15f,30f)*w/960f
                    val marker = Path().apply {
                        moveTo(x-size,y-size); lineTo(x,y); lineTo(x+size,y-size)
                    }
                    val color = if (lead.radar) Color.rgb(255,215,0) else Color.WHITE
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 3*density; paint.color = color
                    canvas.drawPath(marker,paint)
                    val labels = ArLeadPresenter.text(lead).split('\n')
                    paint.textSize = 16*resources.displayMetrics.scaledDensity
                    val step = paint.fontSpacing+2*density
                    val blockHeight = labels.size*step
                    val baseline = if (y+10*density+blockHeight <= rect.bottom)
                        y+10*density-paint.ascent() else (y-size-blockHeight).coerceAtLeast(rect.top-paint.ascent())
                    paint.style = Paint.Style.FILL
                    paint.setShadowLayer(2*density,1*density,1*density,Color.BLACK)
                    labels.forEachIndexed { index, text ->
                        val textWidth = paint.measureText(text)
                        val left = (x-textWidth/2).coerceIn(rect.left, maxOf(rect.left,rect.right-textWidth))
                        canvas.drawText(text,left,baseline+index*step,paint)
                    }
                    paint.clearShadowLayer()
                }
                canvas.restore()
            }
            val guidance = ArLaneGuidancePresenter.text(nav, feedback, now, wall, budget) ?: "未开始高德车机导航"
            val mapped = bitmap != null && lanes.any { it.relative in targets || it.relative == intentLane }
            val status = when {
                bitmap == null -> "等待实时画面 · ${com.garan.tesnav.ar.C3ArCameraService.displayStatus()}"
                mapped -> "绿箭头：优先选道 · 蓝箭头：C3 导航意向 · 非变道许可"
                arrows.any { it.kind == "direction" } -> "黄箭头：导航方向提示 · 目标车道尚未对应"
                arrows.isNotEmpty() -> "白箭头：沿路提示 · 优先车道待确认"
                else -> "车道与实景对应未确认 · 保留方向提示"
            }
            val density = resources.displayMetrics.density
            val sd = resources.displayMetrics.scaledDensity
            fun label(text: String, x: Float, y: Float, available: Float, size: Float, color: Int = Color.WHITE) {
                paint.style = Paint.Style.FILL; paint.color = color; paint.textSize = size*sd
                val count = paint.breakText(text, true, available.coerceAtLeast(0f), null)
                canvas.drawText(text.take(count), x, y, paint)
            }
            val panel = RectF(12*density, height-76*density, width-12*density, height-12*density)
            paint.style = Paint.Style.FILL; paint.color = Color.argb(225, 13, 25, 33)
            canvas.drawRoundRect(panel, 16*density, 16*density, paint)
            val summary = when {
                bitmap == null -> "等待实时画面"
                mapped -> "已对应车道 · 绿：推荐 / 蓝：导航意向"
                arrows.any { it.kind == "direction" } -> "方向提示 · 目标车道待确认"
                arrows.isNotEmpty() -> "沿路提示 · 优先车道待确认"
                else -> "等待有效路线与车道"
            }
            label(summary, panel.left+16*density, panel.top+25*density,
                panel.width()-96*density, 15f)
            val laneText = guidance.split('\n').take(2).joinToString(" · ")
            label(laneText, panel.left+16*density, panel.top+48*density,
                panel.width()-96*density, 12f, Color.rgb(181,200,210))
            val speed = feedback.forDisplay(now, budget).let {
                it.egoSpeedKph?.takeIf { speed -> it.vehicleStateValid && speed.isFinite() && speed >= 0 }
            }
            val speedPanel = RectF(width-104*density, 12*density, width-12*density, 96*density)
            paint.color = Color.argb(225, 13, 25, 33)
            canvas.drawRoundRect(speedPanel, 16*density, 16*density, paint)
            paint.isFakeBoldText = true
            label(speed?.toInt()?.toString() ?: "—", speedPanel.left+16*density,
                speedPanel.top+44*density, speedPanel.width()-24*density, 30f)
            paint.isFakeBoldText = false
            label("km/h · 增强", speedPanel.left+16*density, speedPanel.top+67*density,
                speedPanel.width()-20*density, 11f, Color.rgb(181,200,210))
            if (details) {
                val lines = ("$status\n$guidance").split('\n')
                val top = 108*density
                paint.color = Color.argb(230, 13, 25, 33)
                canvas.drawRoundRect(12*density, top, width-12*density,
                    top+(lines.size*22+20)*density, 12*density, 12*density, paint)
                lines.forEachIndexed { index, text ->
                    label(text, 24*density, top+(25+index*22)*density, width-48*density, 13f)
                }
            }
        }
    }
}
