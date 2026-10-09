package com.garan.tesnav

import android.app.Activity
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewOptions
import com.amap.api.navi.enums.AMapNaviViewShowMode
import com.amap.api.navi.view.OverviewButtonView
import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.model.OemVehicleLaneState
import com.garan.tesnav.model.withDisplayExpiry
import com.garan.tesnav.export.NAVASSIST_UDP_RECEIVE_WINDOW_MS
import com.garan.tesnav.export.NavAssistV2Protocol
import com.garan.tesnav.model.RouteChoice
import com.garan.tesnav.service.NavigationForegroundService
import com.garan.tesnav.service.NavigationDataSource
import com.garan.tesnav.service.NavigationSourcePolicy
import com.garan.tesnav.ui.NavigationStateDialog
import com.garan.tesnav.ui.NavigationSourceMenu
import com.garan.tesnav.ui.AmapAutoActions
import com.garan.tesnav.ui.LaneGuidanceView
import com.garan.tesnav.ui.EmbeddedAmapView
import com.garan.tesnav.ui.SettingsDialog
import com.garan.tesnav.config.AmapConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** Dedicated AMap navigation page, matching AMap's official Activity separation. */
class NavigationActivity : Activity() {
    private lateinit var naviView: AMapNaviView
    private lateinit var routeActions: LinearLayout
    private lateinit var routeChoiceScroll: HorizontalScrollView
    private lateinit var routeChoiceRow: LinearLayout
    private lateinit var navigationButtons: LinearLayout
    private lateinit var endNavigationButton: Button
    private lateinit var realtimeButton: Button
    private lateinit var simulationButton: Button
    private lateinit var speechButton: Button
    private lateinit var routeSwitchButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var debugButton: ImageButton
    private lateinit var sourceButton: Button
    private lateinit var overviewButton: OverviewButtonView
    private lateinit var laneGuidanceView: LaneGuidanceView
    private lateinit var navigationSummaryView: TextView
    private lateinit var navigationTopPanel: LinearLayout
    private var splitSpeedView: TextView? = null
    private var titaClient: com.garan.tesnav.teslable.TitaDashboardClient? = null
    private var titaSample: com.garan.tesnav.teslable.TitaDashboardSample? = null
    private var splitGearView: TextView? = null
    private var officialDashboard = com.garan.tesnav.teslable.VehicleDashboardData()
    private val dashboardSelector = com.garan.tesnav.teslable.DashboardSourceSelector()
    private var dashboardJob: Job? = null
    private var speedColorConfiguration = com.garan.tesnav.ui.SpeedColorConfiguration(false, emptyList())
    private val speedColorPreferences by lazy {
        getSharedPreferences(com.garan.tesnav.ui.SpeedColorSettings.PREFS, Context.MODE_PRIVATE)
    }
    private val speedColorListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        speedColorConfiguration = com.garan.tesnav.ui.SpeedColorSettings.read(this)
        renderSplitSpeed()
    }
    private var splitBatteryView: TextView? = null
    private var splitRangeView: TextView? = null
    private var splitArrivalView: TextView? = null
    private var splitLeftBlindspotView: TextView? = null
    private var splitRightBlindspotView: TextView? = null
    private var splitMapPane: FrameLayout? = null
    private var embeddedAmapView: EmbeddedAmapView? = null
    private var foreground = false
    private val splitLayout: Boolean
        get() = BuildConfig.EXTENDED_NAV_SOURCES &&
            resources.configuration.screenWidthDp >= 600 &&
            resources.configuration.screenWidthDp > resources.configuration.screenHeightDp

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runtimeService: NavigationForegroundService? = null
    private var bindRequested = false
    private var stateJob: Job? = null
    private var oemLaneJob: Job? = null
    private var currentState = NavigationState()
    private var currentSource = NavigationDataSource.AMAP_API
    private var currentOemLaneState = OemVehicleLaneState()
    // One configured send interval plus the next ACK receive window; display only.
    private val feedbackDisplayBudgetMs = BuildConfig.NAV_ASSIST_V2_INTERVAL_MS
        .coerceIn(NavAssistV2Protocol.MIN_INTERVAL_MS, Long.MAX_VALUE - NAVASSIST_UDP_RECEIVE_WINDOW_MS) +
        NAVASSIST_UDP_RECEIVE_WINDOW_MS
    private var previousMode: NavigationMode? = null
    private var routeRequestSent = false
    private var renderedRouteChoices: List<RouteChoice> = emptyList()
    private var routeChoicesExpanded = false
    private var autoMapCentered = false
    private lateinit var autoGuidanceView: com.garan.tesnav.ui.AutoNavigationGuidanceView
    private var autoVehicleMarker: com.amap.api.maps.model.Marker? = null
    private var autoLocationAt: Long? = null
    private var autoExpiryJob: Job? = null
    private var autoMapPausedUntil = 0L

    private val runtimeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as NavigationForegroundService.LocalBinder).getService()
            runtimeService = service
            service.teslaBleClient.dashboardActive = splitLayout && foreground
            debugButton.isEnabled = true
            observeRuntime(service)
            requestRouteIfNeeded(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cancelRuntimeObservation()
            runtimeService = null
            bindRequested = false
            debugButton.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AmapConfiguration.prepare(applicationContext)) {
            startActivity(Intent(this, AmapKeyActivity::class.java))
            finish()
            return
        }
        naviView = AMapNaviView(this, createViewOptions())
        createControls()
        setContentView(createRootView())
        naviView.onCreate(savedInstanceState)
        naviView.setShowTrafficLightView(true)
        naviView.setLazyOverviewButtonView(overviewButton)
        configureMap()
        configureActions()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                stopNavigationAndFinish()
            }
        }
    }

    private fun createViewOptions() = AMapNaviViewOptions().apply {
        setLayoutVisible(false)
        setCompassEnabled(false)
        setTrafficBarEnabled(false)
        setTrafficLayerEnabled(false)
        setRouteListButtonShow(false)
        setSettingMenuEnabled(false)
        setBroadcastModeEnabled(false)
        // Keep SDK lane guidance enabled even though the stock navigation layout is hidden.
        // We consume showLaneInfo ourselves in LaneGuidanceView.
        setLaneInfoShow(true)
        setRefreshButtonEnabled(false)
        setNaviStatusBarEnabled(false)
        setTilt(0)
        setZoom(15)
        setPointToCenter(0.4, 0.5)
        setAutoLockCar(false)
        setAutoDrawRoute(true)
        setDrawBackUpOverlay(true)
    }

    private fun createControls() {
        endNavigationButton = actionButton("结束导航")
        realtimeButton = actionButton("开始导航")
        simulationButton = actionButton("模拟导航")
        speechButton = actionButton("静音")
        routeSwitchButton = actionButton("切换路线")
        sourceButton = actionButton(
            NavigationSourcePolicy.buttonText(NavigationDataSource.AMAP_API, BuildConfig.EXTENDED_NAV_SOURCES),
        ).apply { textSize = 14f }
        routeChoiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        routeChoiceScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(routeChoiceRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        navigationButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(endNavigationButton, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(realtimeButton, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(simulationButton, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            })
            addView(speechButton, LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            })
            addView(routeSwitchButton, LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            })
        }
        routeActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(routeChoiceScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(navigationButtons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        settingsButton = floatingIconButton(R.drawable.ic_settings, "打开设置")
        debugButton = floatingIconButton(R.drawable.ic_bug_report, "查看 NavigationState").apply {
            isEnabled = false
        }
        overviewButton = OverviewButtonView(this).apply {
            contentDescription = "路线全览"
        }
        laneGuidanceView = LaneGuidanceView(this, feedbackDisplayBudgetMs)
        navigationSummaryView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(220, 24, 32, 38))
            }
            visibility = View.GONE
        }
        navigationTopPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            autoGuidanceView = com.garan.tesnav.ui.AutoNavigationGuidanceView(this@NavigationActivity).apply {
                visibility = View.GONE
                setOnClickListener { autoMapPausedUntil = 0; autoLocationAt = null; renderNavigationState(currentState) }
            }
            addView(autoGuidanceView, LinearLayout.LayoutParams(dp(340), dp(102)))
            addView(navigationSummaryView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(laneGuidanceView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
        }
    }

    private fun createRootView(): FrameLayout = FrameLayout(this).apply {
        if (splitLayout) {
            addView(createSplitLayout(), FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            return@apply
        }
        addView(naviView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        addView(navigationTopPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(22)
            marginStart = dp(72)
            marginEnd = dp(72)
        })
        addView(routeActions, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            marginStart = dp(12)
            marginEnd = dp(12)
            bottomMargin = dp(32)
        })
        addView(overviewButton, leftButtonParams(stackLevel = 1))
        addView(settingsButton, leftButtonParams(stackLevel = 2))
        addView(debugButton, leftButtonParams(stackLevel = 3))
        addView(sourceButton, FrameLayout.LayoutParams(dp(132), dp(52)).apply {
            gravity = Gravity.TOP or Gravity.END
            marginEnd = dp(16)
            topMargin = dp(22)
        })
    }

    /** Reuse the live map and guidance; only the landscape presentation changes. */
    private fun createSplitLayout(): LinearLayout {
        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 23, 29))
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val compact = resources.configuration.screenHeightDp <= 420
        val statusRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            splitLeftBlindspotView = TextView(this@NavigationActivity).apply {
                text = "左盲区 —"
                textSize = 12f
                gravity = Gravity.START
                setTextColor(Color.LTGRAY)
            }
            splitRightBlindspotView = TextView(this@NavigationActivity).apply {
                text = "右盲区 —"
                textSize = 12f
                gravity = Gravity.END
                setTextColor(Color.LTGRAY)
            }
            splitLeftBlindspotView?.visibility = View.GONE
            splitRightBlindspotView?.visibility = View.GONE
            addView(splitLeftBlindspotView, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@NavigationActivity).apply {
                text = "挡位 —"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.LTGRAY)
                contentDescription = "挡位暂无数据"
                visibility = View.GONE
                splitGearView = this
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(splitRightBlindspotView, LinearLayout.LayoutParams(0, -2, 1f))
        }
        sidebar.addView(statusRow, LinearLayout.LayoutParams(-1, -2))
        splitSpeedView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = if (compact) 60f else 80f
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            includeFontPadding = false
            setTextColor(Color.WHITE)
            text = "—"
            contentDescription = "车辆速度暂无数据"
        }
        sidebar.addView(splitSpeedView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        sidebar.addView(TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            text = "km/h"
            setTextColor(Color.LTGRAY)
        })
        sidebar.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(27, 34, 42))
                cornerRadius = dp(12).toFloat()
            }
            for ((label, value) in listOf("电量" to "—%", "续航" to "— km")) {
                addView(LinearLayout(this@NavigationActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    addView(TextView(this@NavigationActivity).apply {
                        text = label
                        textSize = 11f
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setTextColor(Color.rgb(145, 155, 165))
                    }, LinearLayout.LayoutParams(-1, -2))
                    addView(TextView(this@NavigationActivity).apply {
                        text = value
                        textSize = if (compact) 20f else 24f
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                        setTextColor(Color.WHITE)
                        contentDescription = "$label 暂无数据"
                        if (label == "电量") splitBatteryView = this else splitRangeView = this
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(12) })
        splitArrivalView = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            visibility = View.GONE
        }
        sidebar.addView(splitArrivalView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        titaClient = com.garan.tesnav.teslable.TitaDashboardClient(this) { sample, _ ->
            titaSample = sample
            renderSplitSpeed()
        }
        sidebar.addView(View(this).apply {
            setBackgroundColor(Color.rgb(45, 53, 61))
        }, LinearLayout.LayoutParams(-1, dp(1)))
        // All instrument content scrolls together; bottom controls keep their own space.
        navigationTopPanel.removeView(autoGuidanceView)
        navigationTopPanel.removeView(navigationSummaryView)
        navigationTopPanel.removeView(laneGuidanceView)
        laneGuidanceView.fitToPanel()
        navigationTopPanel.addView(laneGuidanceView,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        navigationSummaryView.apply {
            textSize = 16f
            background = null
            setPadding(0, dp(6), 0, dp(6))
        }
        sidebar.addView(navigationSummaryView, LinearLayout.LayoutParams(-1, -2))
        navigationTopPanel.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        sidebar.addView(navigationTopPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val sidebarPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 23, 29))
            addView(ScrollView(this@NavigationActivity).apply {
                isFillViewport = true
                addView(sidebar, FrameLayout.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        sidebarPane.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), dp(10))
            addView(settingsButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(debugButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(sourceButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        }, LinearLayout.LayoutParams(-1, -2))
        val mapPane = FrameLayout(this).apply {
            addView(naviView, FrameLayout.LayoutParams(-1, -1))
            addView(overviewButton, FrameLayout.LayoutParams(dp(52), dp(52)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(12); marginEnd = dp(12)
            })
            // Existing actions can be wider than the map half, especially in route preview.
            routeActions.removeView(navigationButtons)
            routeActions.addView(HorizontalScrollView(this@NavigationActivity).apply {
                addView(navigationButtons)
            }, LinearLayout.LayoutParams(-1, -2))
            addView(routeActions, FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.BOTTOM
                marginStart = dp(8); marginEnd = dp(8); bottomMargin = dp(12)
            })
        }
        splitMapPane = mapPane
        val rightPane = FrameLayout(this).apply {
            addView(mapPane, FrameLayout.LayoutParams(-1, -1))
            embeddedAmapView = EmbeddedAmapView(this@NavigationActivity).apply {
                visibility = View.GONE
            }
            addView(embeddedAmapView, FrameLayout.LayoutParams(-1, -1))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(18, 23, 29))
            val sidebarWidth = dp((resources.configuration.screenWidthDp * 0.38f).toInt().coerceAtLeast(300))
            addView(sidebarPane, LinearLayout.LayoutParams(sidebarWidth, -1))
            addView(rightPane, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun renderSplitSpeed() {
        val displayed = currentOemLaneState.forDisplay(SystemClock.elapsedRealtime(), feedbackDisplayBudgetMs)
        val now = SystemClock.elapsedRealtime()
        val tita = titaSample
        fun <T> titaObservation(value: T) = tita?.let {
            com.garan.tesnav.teslable.DashboardObservation(value, it.receivedAtMs, it.refreshCostMs, 2000)
        }
        // TITA notifications can repeat a retained speed; keep the existing C3/official speed path.
        val selectedSpeed = dashboardSelector.select<Float>("speed", null, officialDashboard.speed, now)
        val selectedBattery = dashboardSelector.select("battery", tita?.batteryPercent?.let { titaObservation(it) }, officialDashboard.battery, now)
        val selectedRange = dashboardSelector.select("range", tita?.rangeKm?.let { titaObservation(it) }, officialDashboard.range, now)
        // C3 feedback updates continuously; a still-valid BLE query must not mask it.
        val c3Speed = displayed.egoSpeedKph?.takeIf {
            displayed.vehicleStateValid && it.isFinite() && it >= 0f
        }
        val speed = c3Speed ?: selectedSpeed?.observation?.value
        val speedSource = if (c3Speed != null) "C3" else selectedSpeed?.source ?: "暂无"
        fun valueWithUnit(value: String, unit: String): CharSequence = android.text.SpannableString("$value$unit").apply {
            setSpan(android.text.style.RelativeSizeSpan(0.6f), value.length, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.ForegroundColorSpan(Color.rgb(145, 155, 165)), value.length, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        splitBatteryView?.text = selectedBattery?.let { valueWithUnit(it.observation.value.toString(), "%") } ?: "—"
        splitRangeView?.text = selectedRange?.let { valueWithUnit(String.format(Locale.CHINA, "%.0f", it.observation.value), " km") } ?: "—"
        // Gear/TPMS stay on the existing C3 display; do not duplicate a BLE-only gear here.
        splitGearView?.visibility = View.GONE
        val arrivalBattery = officialDashboard.arrivalBattery?.takeIf { it.fresh(now) }?.value
        val minutesToArrival = officialDashboard.minutesToArrival?.takeIf { it.fresh(now) }?.value
        splitArrivalView?.apply {
            val parts = listOfNotNull(
                arrivalBattery?.let { String.format(Locale.CHINA, "到达电量 %.0f%%", it) },
                minutesToArrival?.let { "剩余 ${kotlin.math.ceil(it.toDouble()).toLong()} 分钟" },
            )
            visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
            text = if (parts.isEmpty()) "" else "原车预计：${parts.joinToString(" · ")}"
            contentDescription = text
        }
        (splitBatteryView?.parent as? View)?.visibility = if (selectedBattery != null) View.VISIBLE else View.GONE
        (splitRangeView?.parent as? View)?.visibility = if (selectedRange != null) View.VISIBLE else View.GONE
        ((splitBatteryView?.parent as? View)?.parent as? View)?.visibility =
            if (selectedBattery != null || selectedRange != null) View.VISIBLE else View.GONE
        splitBatteryView?.contentDescription = selectedBattery?.let { "${it.source} 电量 ${it.observation.value} 百分比" } ?: "电量暂无数据"
        splitRangeView?.contentDescription = selectedRange?.let { "${it.source} 续航 ${it.observation.value} 公里" } ?: "续航暂无数据"
        splitSpeedView?.apply {
            setTextColor(speedColorConfiguration.colorFor(speed))
            text = speed?.let { String.format(Locale.CHINA, "%.0f", it) } ?: "—"
            contentDescription = speed?.let { "$speedSource 车辆速度 $text 公里每小时" } ?: "车辆速度暂无数据"
        }
        for ((view, label, occupied) in listOf(
            Triple(splitLeftBlindspotView, "左盲区", displayed.leftBlindspot),
            Triple(splitRightBlindspotView, "右盲区", displayed.rightBlindspot),
        )) {
            view?.apply {
                visibility = if (displayed.blindspotValid) View.VISIBLE else View.GONE
                text = when {
                    !displayed.blindspotValid -> "$label —"
                    occupied -> "$label 有车"
                    else -> "$label 无提示"
                }
                setTextColor(if (displayed.blindspotValid && occupied) Color.rgb(255, 178, 64) else Color.LTGRAY)
                contentDescription = if (displayed.blindspotValid) text else "$label 暂无数据"
            }
        }
    }

    private fun leftButtonParams(stackLevel: Int) = FrameLayout.LayoutParams(dp(52), dp(52)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            marginStart = dp(16)
            bottomMargin = dp(16 + stackLevel * 64)
    }

    private fun configureMap() {
        naviView.map.setOnMapTouchListener { event ->
            if (currentSource == NavigationDataSource.AMAP_AUTO && event.action == android.view.MotionEvent.ACTION_MOVE) {
                autoMapPausedUntil = SystemClock.elapsedRealtime() + 8_000
                autoLocationAt = null
            }
        }
        naviView.map.uiSettings.apply {
            isZoomControlsEnabled = true
            isMyLocationButtonEnabled = true
            isCompassEnabled = false
            isScaleControlsEnabled = false
            isIndoorSwitchEnabled = false
            isTiltGesturesEnabled = false
        }
        naviView.map.moveCamera(CameraUpdateFactory.changeTilt(0f))
    }

    private fun configureActions() {
        sourceButton.setOnClickListener {
            val service = runtimeService ?: return@setOnClickListener
            if (BuildConfig.EXTENDED_NAV_SOURCES) {
                NavigationSourceMenu.show(
                    this,
                    sourceButton,
                    service.navigationDataSource.value,
                    BuildConfig.EXTENDED_NAV_SOURCES,
                ) { source ->
                    service.selectNavigationDataSource(source)?.let(::toast)
                }
            } else {
                service.toggleAmapNavigationDataSource()
            }
        }
        endNavigationButton.setOnClickListener {
            if (BuildConfig.EXTENDED_NAV_SOURCES && currentSource == NavigationDataSource.AMAP_AUTO) finish()
            else runtimeService?.stopNavigation()
        }
        realtimeButton.setOnClickListener {
            if (BuildConfig.EXTENDED_NAV_SOURCES && currentSource == NavigationDataSource.AMAP_AUTO) {
                if (!AmapAutoActions.open(this)) toast("未能打开高德车机，请确认已安装车机版")
            } else if (runtimeService?.startRealtime() != true) toast("启动导航失败")
        }
        simulationButton.setOnClickListener {
            val service = runtimeService ?: return@setOnClickListener
            val succeeded = when (currentState.navigationMode) {
                NavigationMode.ROUTE_PLANNED -> service.startSimulation()
                NavigationMode.SIMULATION -> {
                    if (currentState.simulationPaused) service.resumeSimulation() else service.pauseSimulation()
                }
                else -> false
            }
            if (!succeeded) toast("模拟导航操作失败")
        }
        speechButton.setOnClickListener {
            val service = runtimeService ?: return@setOnClickListener
            if (!service.setSpeechEnabled(!currentState.speechEnabled)) toast("语音设置失败")
        }
        routeSwitchButton.setOnClickListener {
            routeChoicesExpanded = !routeChoicesExpanded
            routeChoiceScroll.visibility = if (routeChoicesExpanded && currentState.routeChoices.size > 1) View.VISIBLE else View.GONE
            routeSwitchButton.text = if (routeChoicesExpanded) "收起路线" else "切换路线"
        }
        settingsButton.setOnClickListener {
            runtimeService?.let { service ->
                SettingsDialog(
                    context = this,
                    scope = activityScope,
                    service = service,
                ).show()
            } ?: toast("后台导航服务尚未连接")
        }
        debugButton.setOnClickListener {
            runtimeService?.let { service ->
                NavigationStateDialog(this, activityScope, service).show()
            }
        }
    }

    private fun requestRouteIfNeeded(service: NavigationForegroundService) {
        if (service.navigationDataSource.value == NavigationDataSource.AMAP_AUTO) return
        if (routeRequestSent || service.stateStore.state.value.navigationMode != NavigationMode.IDLE) return
        if (!intent.hasExtra(EXTRA_DESTINATION_LATITUDE) || !intent.hasExtra(EXTRA_DESTINATION_LONGITUDE)) {
            finish()
            return
        }
        routeRequestSent = true
        val accepted = service.planRoute(
            intent.getDoubleExtra(EXTRA_DESTINATION_LATITUDE, Double.NaN),
            intent.getDoubleExtra(EXTRA_DESTINATION_LONGITUDE, Double.NaN),
        )
        if (!accepted) {
            toast(service.stateStore.state.value.errorMessage ?: "路线规划请求失败")
            finish()
        }
    }

    private fun observeRuntime(service: NavigationForegroundService) {
        cancelRuntimeObservation()
        stateJob = activityScope.launch {
            combine(
                service.stateStore.state,
                service.amapAutoNavigationState,
                service.navigationDataSource,
            ) { apiState, autoState, source -> Triple(apiState, autoState, source) }
                .collect { (apiState, autoState, source) ->
                    if (BuildConfig.EXTENDED_NAV_SOURCES && currentSource == NavigationDataSource.AMAP_AUTO &&
                        source == NavigationDataSource.AMAP_API && !intent.hasExtra(EXTRA_DESTINATION_LATITUDE)
                    ) {
                        finish()
                        return@collect
                    }
                    if (source != currentSource) {
                        val options = naviView.viewOptions
                        options.setAutoDrawRoute(source == NavigationDataSource.AMAP_API)
                        naviView.setViewOptions(options)
                        if (source == NavigationDataSource.AMAP_AUTO) naviView.map.clear()
                    }
                    currentSource = source
                    sourceButton.text = NavigationSourcePolicy.buttonText(source, BuildConfig.EXTENDED_NAV_SOURCES)
                    renderNavigationState(if (source == NavigationDataSource.AMAP_AUTO) autoState ?: NavigationState() else apiState)
                }
        }
        dashboardJob = activityScope.launch {
            service.teslaBleClient.dashboard.collectLatest { data ->
                officialDashboard = data
                while (true) {
                    renderSplitSpeed()
                    val now = SystemClock.elapsedRealtime()
                    val remaining = listOfNotNull(data.speed, data.gear, data.battery, data.range, data.arrivalBattery, data.minutesToArrival)
                        .map { it.receivedAtMs + it.budgetMs - now }.filter { it > 0 }
                    if (remaining.isEmpty()) break
                    delay(remaining.min() + 1)
                }
            }
        }
        oemLaneJob = activityScope.launch {
            service.oemVehicleLaneState.withDisplayExpiry(feedbackDisplayBudgetMs, SystemClock::elapsedRealtime).collect { state ->
                currentOemLaneState = state
                renderSplitSpeed()
                laneGuidanceView.render(currentState, currentOemLaneState)
                if (currentSource == NavigationDataSource.AMAP_AUTO &&
                    currentState.guidanceReceivedElapsedMs?.let { SystemClock.elapsedRealtime() - it in 0..3_000 } != true) {
                    laneGuidanceView.visibility = View.GONE
                }
            }
        }
    }

    private fun renderNavigationState(state: NavigationState) {
        val oldMode = previousMode
        currentState = state
        laneGuidanceView.render(state, currentOemLaneState)
        renderNavigationSummary(state)
        autoExpiryJob?.cancel()
        val autoSelected = BuildConfig.EXTENDED_NAV_SOURCES && currentSource == NavigationDataSource.AMAP_AUTO
        // The car application's real UI replaces only the SDK map pane.
        splitMapPane?.visibility = if (autoSelected) View.INVISIBLE else View.VISIBLE
        embeddedAmapView?.setActive(autoSelected && foreground)
        autoGuidanceView.visibility = if (!splitLayout && autoSelected && state.routePlanned) View.VISIBLE else View.GONE
        if (currentSource == NavigationDataSource.AMAP_AUTO) {
            routeActions.visibility = if (BuildConfig.EXTENDED_NAV_SOURCES) View.VISIBLE else View.GONE
            routeChoiceScroll.visibility = View.GONE
            if (BuildConfig.EXTENDED_NAV_SOURCES) {
                endNavigationButton.apply { text = "返回搜索"; visibility = View.VISIBLE }
                realtimeButton.apply { text = "打开高德车机"; visibility = View.VISIBLE }
                simulationButton.visibility = View.GONE
                speechButton.visibility = View.GONE
                routeSwitchButton.visibility = View.GONE
                overviewButton.visibility = View.GONE
                val latitude = state.latitude
                val longitude = state.longitude
                val observedAt = state.locationObservedAtMs
                val guidanceFresh = com.garan.tesnav.ui.AutoNavigationDisplayPolicy.guidanceFresh(state, SystemClock.elapsedRealtime())
                autoGuidanceView.render(state, guidanceFresh)
                if (!guidanceFresh) laneGuidanceView.visibility = View.GONE
                if (latitude != null && longitude != null && observedAt != null &&
                    com.garan.tesnav.ui.AutoNavigationDisplayPolicy.locationFresh(state,
                        SystemClock.elapsedRealtime(), System.currentTimeMillis())
                ) {
                    val position = LatLng(latitude, longitude)
                    val heading = state.bearing?.takeIf { it.isFinite() } ?: 0f
                    if (autoVehicleMarker == null) {
                        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bitmap)
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        val path = android.graphics.Path().apply {
                            moveTo(32f, 5f); lineTo(57f, 56f); lineTo(32f, 45f); lineTo(7f, 56f); close()
                        }
                        paint.color = Color.WHITE; paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 7f
                        canvas.drawPath(path, paint)
                        paint.color = Color.rgb(30, 123, 245); paint.style = android.graphics.Paint.Style.FILL
                        canvas.drawPath(path, paint)
                        autoVehicleMarker = naviView.map.addMarker(com.amap.api.maps.model.MarkerOptions()
                            .position(position).anchor(0.5f, 0.5f)
                            .icon(com.amap.api.maps.model.BitmapDescriptorFactory.fromBitmap(bitmap)))
                    }
                    autoVehicleMarker?.apply { this.position = position; rotateAngle = -heading; isVisible = true }
                    if (autoLocationAt != observedAt && SystemClock.elapsedRealtime() >= autoMapPausedUntil) {
                        naviView.map.moveCamera(CameraUpdateFactory.newCameraPosition(
                            com.amap.api.maps.model.CameraPosition(position, 18f, 35f, heading)))
                        autoLocationAt = observedAt
                    }
                    autoMapCentered = true
                } else autoVehicleMarker?.isVisible = false
                // Expire both vehicle and guidance even if broadcasts stop completely.
                autoExpiryJob = activityScope.launch {
                    kotlinx.coroutines.delay(500)
                    renderNavigationState(currentState)
                }
            }
            previousMode = state.navigationMode
            return
        }
        endNavigationButton.text = "结束导航"
        autoMapCentered = false
        autoVehicleMarker?.remove(); autoVehicleMarker = null; autoLocationAt = null
        realtimeButton.text = "开始导航"
        overviewButton.visibility = View.VISIBLE
        when (state.navigationMode) {
            NavigationMode.IDLE -> {
                routeChoicesExpanded = false
                routeSwitchButton.text = "切换路线"
                routeActions.visibility = View.GONE
                routeChoiceScroll.visibility = View.GONE
                routeSwitchButton.visibility = View.GONE
                speechButton.visibility = View.GONE
                routeSwitchButton.visibility = View.GONE
                if (oldMode != null && oldMode != NavigationMode.IDLE) finish()
                if (routeRequestSent && !state.errorMessage.isNullOrBlank()) {
                    toast(state.errorMessage)
                    finish()
                }
            }
            NavigationMode.ROUTE_PLANNED -> {
                routeActions.visibility = View.VISIBLE
                renderRouteChoices(state.routeChoices)
                routeChoiceScroll.visibility = if (state.routeChoices.isEmpty()) View.GONE else View.VISIBLE
                endNavigationButton.visibility = View.GONE
                realtimeButton.visibility = View.VISIBLE
                simulationButton.apply {
                    text = "模拟导航"
                    visibility = View.VISIBLE
                }
                speechButton.visibility = View.GONE
                if (oldMode != NavigationMode.ROUTE_PLANNED) {
                    naviView.setShowMode(AMapNaviViewShowMode.SHOW_MODE_DISPLAY_OVERVIEW)
                }
            }
            NavigationMode.REALTIME -> {
                if (state.routeChoices.size <= 1) routeChoicesExpanded = false
                routeActions.visibility = View.VISIBLE
                renderRouteChoices(state.routeChoices)
                routeChoiceScroll.visibility = if (routeChoicesExpanded && state.routeChoices.size > 1) View.VISIBLE else View.GONE
                endNavigationButton.visibility = View.VISIBLE
                realtimeButton.visibility = View.GONE
                simulationButton.visibility = View.GONE
                speechButton.apply {
                    text = if (state.speechEnabled) "静音" else "恢复语音"
                    visibility = View.VISIBLE
                }
                routeSwitchButton.visibility = if (state.routeChoices.size > 1) View.VISIBLE else View.GONE
                if (oldMode != NavigationMode.REALTIME) enterNavigationView()
            }
            NavigationMode.SIMULATION -> {
                if (state.routeChoices.size <= 1) routeChoicesExpanded = false
                routeActions.visibility = View.VISIBLE
                renderRouteChoices(state.routeChoices)
                routeChoiceScroll.visibility = if (routeChoicesExpanded && state.routeChoices.size > 1) View.VISIBLE else View.GONE
                endNavigationButton.visibility = View.VISIBLE
                realtimeButton.visibility = View.GONE
                simulationButton.apply {
                    text = if (state.simulationPaused) "继续" else "暂停"
                    visibility = View.VISIBLE
                }
                speechButton.apply {
                    text = if (state.speechEnabled) "静音" else "恢复语音"
                    visibility = View.VISIBLE
                }
                routeSwitchButton.visibility = if (state.routeChoices.size > 1) View.VISIBLE else View.GONE
                if (oldMode != NavigationMode.SIMULATION) enterNavigationView()
            }
            NavigationMode.ARRIVED -> {
                routeActions.visibility = View.GONE
                routeSwitchButton.visibility = View.GONE
            }
        }
        previousMode = state.navigationMode
    }

    private fun renderRouteChoices(choices: List<RouteChoice>) {
        val visibleChoices = choices.take(MAX_ROUTE_CHOICES)
        if (visibleChoices == renderedRouteChoices) return
        renderedRouteChoices = visibleChoices
        routeChoiceRow.removeAllViews()
        visibleChoices.forEachIndexed { index, choice ->
            routeChoiceRow.addView(
                routeChoiceCard(choice, index),
                LinearLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) marginStart = dp(8)
                },
            )
        }
    }

    private fun routeChoiceCard(choice: RouteChoice, index: Int): TextView = TextView(this).apply {
        val minutes = ceil(choice.durationSeconds / 60.0).toInt().coerceAtLeast(1)
        val distance = String.format(java.util.Locale.CHINA, "%.1f", choice.distanceMeters / 1000.0)
        val title = choice.label.ifBlank { "路线 ${index + 1}" }
        val toll = if (choice.tollYuan > 0) " · 收费 ¥${choice.tollYuan}" else ""
        text = "$title${if (choice.selected) " · 已选" else ""}\n" +
            "${minutes}分钟 · ${distance}公里$toll · ${choice.trafficLightCount}个红绿灯"
        textSize = 13f
        setTextColor(if (choice.selected) Color.WHITE else Color.rgb(38, 50, 56))
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (choice.selected) BRAND_BLUE else Color.argb(238, 255, 255, 255))
            setStroke(dp(1), if (choice.selected) BRAND_BLUE else Color.rgb(176, 190, 197))
        }
        setOnClickListener {
            if (runtimeService?.selectRoute(choice.routeId) == true) {
                routeChoicesExpanded = false
                routeChoiceScroll.visibility = View.GONE
                routeSwitchButton.text = "切换路线"
                naviView.setShowMode(AMapNaviViewShowMode.SHOW_MODE_DISPLAY_OVERVIEW)
            } else {
                toast("路线切换失败")
            }
        }
    }

    private fun renderNavigationSummary(state: NavigationState) {
        if (splitLayout && currentSource == NavigationDataSource.AMAP_AUTO) {
            navigationSummaryView.visibility = View.GONE
            return
        }
        if (currentSource == NavigationDataSource.AMAP_AUTO && state.routePlanned &&
            !com.garan.tesnav.ui.AutoNavigationDisplayPolicy.guidanceFresh(state, SystemClock.elapsedRealtime())) {
            navigationSummaryView.text = "高德车机 · 引导已过期"
            navigationSummaryView.visibility = View.VISIBLE
            return
        }
        if (currentSource == NavigationDataSource.AMAP_AUTO && !state.routePlanned) {
            navigationSummaryView.text = listOfNotNull(
                "高德车机 · 等待导航",
                state.currentRoad?.takeIf(String::isNotBlank),
            ).joinToString("\n")
            navigationSummaryView.visibility = View.VISIBLE
            return
        }
        if (!state.routePlanned || state.navigationMode == NavigationMode.IDLE) {
            navigationSummaryView.visibility = View.GONE
            return
        }
        val seconds = state.routeRemainTimeSeconds
        val distance = state.routeRemainDistanceMeters
        val trip = buildList {
            seconds?.let { add("剩余 ${ceil(it / 60.0).toInt().coerceAtLeast(1)} 分钟") }
            distance?.let { add(if (it >= 1000) String.format(Locale.CHINA, "%.1f 公里", it / 1000.0) else "$it 米") }
            seconds?.let {
                val arrival = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(System.currentTimeMillis() + it * 1000L))
                add("预计 $arrival 到达")
            }
        }.joinToString(" · ")
        val light = state.trafficLight?.takeIf { System.currentTimeMillis() - it.observedAtMs in 0..3_000 }?.let {
            val status = when (it.status) { 2 -> "红灯"; 3 -> "绿灯"; 4 -> "黄灯"; else -> "信号灯" }
            val direction = when (it.direction) { 1 -> "左转"; 2 -> "右转"; 3 -> "掉头"; 4 -> "直行"; else -> null }
            val elapsedSeconds = ((System.currentTimeMillis() - it.observedAtMs) / 1000L).toInt()
            val countdown = it.countdownSeconds?.minus(elapsedSeconds)?.coerceAtLeast(0)
            listOfNotNull(direction, "$status${countdown?.let { value -> " $value 秒" }.orEmpty()}").joinToString(" · ")
        }
        val source = "高德车机".takeIf { currentSource == NavigationDataSource.AMAP_AUTO }
        val locationWarning = "暂无新鲜定位 · 车位已隐藏".takeIf {
            currentSource == NavigationDataSource.AMAP_AUTO &&
                !com.garan.tesnav.ui.AutoNavigationDisplayPolicy.locationFresh(state,
                    SystemClock.elapsedRealtime(), System.currentTimeMillis())
        }
        val maneuver = if (currentSource == NavigationDataSource.AMAP_AUTO) {
            listOfNotNull(state.nextRoad, state.nextTurnDistanceMeters?.let { "$it 米" })
                .joinToString(" · ").takeIf(String::isNotBlank)
        } else null
        navigationSummaryView.text = listOfNotNull(source, maneuver, trip.takeIf(String::isNotBlank), light, locationWarning).joinToString("\n")
        navigationSummaryView.visibility = if (navigationSummaryView.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun enterNavigationView() {
        val options = naviView.viewOptions
        options.setAutoLockCar(true)
        options.setTilt(0)
        naviView.setViewOptions(options)
        naviView.recoverLockMode()
    }

    private fun bindRuntimeService() {
        if (bindRequested) return
        bindRequested = bindService(
            Intent(this, NavigationForegroundService::class.java),
            runtimeConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun cancelRuntimeObservation() {
        autoExpiryJob?.cancel(); autoExpiryJob = null
        stateJob?.cancel()
        stateJob = null
        dashboardJob?.cancel()
        dashboardJob = null
        officialDashboard = com.garan.tesnav.teslable.VehicleDashboardData()
        dashboardSelector.clear()
        oemLaneJob?.cancel()
        oemLaneJob = null
        currentOemLaneState = OemVehicleLaneState()
        renderSplitSpeed()
        if (::laneGuidanceView.isInitialized) laneGuidanceView.render(currentState, currentOemLaneState)
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        speedColorConfiguration = com.garan.tesnav.ui.SpeedColorSettings.read(this)
        speedColorPreferences.registerOnSharedPreferenceChangeListener(speedColorListener)
        renderSplitSpeed()
        titaClient?.start()
        embeddedAmapView?.setActive(currentSource == NavigationDataSource.AMAP_AUTO)
        if (::naviView.isInitialized) bindRuntimeService()
    }

    override fun onResume() {
        super.onResume()
        if (!::naviView.isInitialized) return
        naviView.onResume()
        laneGuidanceView.render(currentState, currentOemLaneState)
        val options = naviView.viewOptions
        options.setTilt(0)
        naviView.setViewOptions(options)
        naviView.map.moveCamera(CameraUpdateFactory.changeTilt(0f))
    }

    override fun onPause() {
        if (::naviView.isInitialized) naviView.onPause()
        super.onPause()
    }

    override fun onStop() {
        foreground = false
        speedColorPreferences.unregisterOnSharedPreferenceChangeListener(speedColorListener)
        runtimeService?.teslaBleClient?.dashboardActive = false
        titaClient?.stop()
        embeddedAmapView?.setActive(false)
        cancelRuntimeObservation()
        if (bindRequested) unbindService(runtimeConnection)
        runtimeService = null
        bindRequested = false
        if (::debugButton.isInitialized) debugButton.isEnabled = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::naviView.isInitialized) naviView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun stopNavigationAndFinish() {
        if (!BuildConfig.EXTENDED_NAV_SOURCES || currentSource != NavigationDataSource.AMAP_AUTO) {
            runtimeService?.stopNavigation()
        }
        finish()
    }

    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        stopNavigationAndFinish()
    }

    override fun onDestroy() {
        embeddedAmapView?.dispose()
        activityScope.cancel()
        if (::naviView.isInitialized) naviView.onDestroy()
        super.onDestroy()
    }

    private fun actionButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 17f
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(BRAND_BLUE)
    }

    private fun floatingIconButton(icon: Int, description: String): ImageButton = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = description
        setColorFilter(Color.rgb(38, 50, 56))
        setPadding(dp(13), dp(13), dp(13), dp(13))
        elevation = dp(10).toFloat()
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(dp(1), Color.rgb(207, 216, 220))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_DESTINATION_LATITUDE = "destination_latitude"
        const val EXTRA_DESTINATION_LONGITUDE = "destination_longitude"
        private const val MAX_ROUTE_CHOICES = 3
        private val BRAND_BLUE = Color.rgb(0, 120, 255)
    }
}
