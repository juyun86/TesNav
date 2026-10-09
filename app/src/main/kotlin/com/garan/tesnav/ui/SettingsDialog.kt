package com.garan.tesnav.ui

import android.app.Dialog
import android.app.AlertDialog
import com.garan.tesnav.config.SpeechMode
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.amap.api.navi.model.AMapNaviPath
import com.garan.tesnav.export.ExportConnectionState
import com.garan.tesnav.export.NavAssistV2ConnectionStatus
import com.garan.tesnav.model.GeoPoint
import com.garan.tesnav.AmapKeyActivity
import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.service.NavigationForegroundService
import com.garan.tesnav.service.NavigationDataSource
import com.garan.tesnav.util.RouteGeometrySimplifier
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Centered settings dialog with live Comma and Home Assistant state. */
class SettingsDialog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val service: NavigationForegroundService,
) {
    private val observationJobs = mutableListOf<Job>()
    private var applyingSyncToggle = false
    private var renderedRouteSignature: String? = null

    fun show() {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val title = TextView(context).apply {
            text = "设置"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(38, 50, 56))
            setPadding(dp(16), dp(14), dp(8), dp(10))
        }
        val close = Button(context).apply { text = "关闭" }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        @Suppress("DEPRECATION")
        val syncToggle = Switch(context).apply {
            text = if (com.garan.tesnav.BuildConfig.EXTENDED_NAV_SOURCES) "通过 Home Assistant 同步特斯拉导航" else "同步特斯拉导航"
            textSize = 17f
            isChecked = service.teslaSyncEnabled.value && !service.teslaBleSyncEnabled.value
        }
        val webSocketText = bodyText("Comma WebSocket：STOPPED")
        val teslaNavigationText = bodyText("is_tesla_nav_active：断开")
        val homeAssistantConnectionText = bodyText("Home Assistant：DISABLED")
        val homeAssistantNavigationText = bodyText("HA 导航：未知")
        val destinationText = bodyText("HA 目的地：—")
        val webSocketErrorText = errorText()
        val homeAssistantErrorText = errorText()
        val navigationErrorText = errorText()
        val navAssistTitle = bodyText("NavAssist / C3XL").apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        val navAssistPairingState = bodyText("")
        val navAssistStatusText = bodyText("状态：未配置")
        val navAssistEndpointText = bodyText("C3XL：—")
        val navAssistErrorText = errorText()
        val clearNavAssistPairing = Button(context).apply { text = "忘记已配对设备" }
        val navAssistBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(250, 252, 253))
                setStroke(dp(1), Color.rgb(224, 230, 234))
                cornerRadius = dp(12).toFloat()
            }
            addView(navAssistTitle, matchWidthParams())
            addView(navAssistPairingState, matchWidthParams(dp(8)))
            addView(clearNavAssistPairing, matchWidthParams(dp(6)))
            addView(navAssistStatusText, matchWidthParams(dp(10)))
            addView(navAssistEndpointText, matchWidthParams(dp(6)))
            addView(navAssistErrorText, matchWidthParams(dp(6)))
        }
        val routeOverviewPreferences = context.getSharedPreferences(ROUTE_OVERVIEW_PREFS, Context.MODE_PRIVATE)
        @Suppress("DEPRECATION")
        val routeOverviewToggle = Switch(context).apply {
            text = "显示路线全览统计"
            textSize = 17f
            isChecked = routeOverviewPreferences.getBoolean(SHOW_ROUTE_OVERVIEW, true)
        }
        val routeOverviewTitle = bodyText("路线全览统计").apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        val routeOverviewStats = bodyText("当前没有规划路线").apply {
            textSize = 15f
            setLineSpacing(0f, 1.18f)
        }
        val routeOverviewView = RouteOverviewView(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(245, 248, 250))
                setStroke(dp(1), Color.rgb(207, 216, 220))
                cornerRadius = dp(10).toFloat()
            }
        }
        val fullRouteTitle = bodyText("完整坐标点全览").apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        val simplifiedRouteTitle = bodyText("精简坐标点全览").apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        val simplifiedRouteStats = bodyText("当前没有规划路线").apply {
            textSize = 15f
            setLineSpacing(0f, 1.18f)
        }
        val simplifiedRouteOverviewView = RouteOverviewView(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(245, 248, 250))
                setStroke(dp(1), Color.rgb(207, 216, 220))
                cornerRadius = dp(10).toFloat()
            }
        }
        val routeOverviewBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(250, 252, 253))
                setStroke(dp(1), Color.rgb(224, 230, 234))
                cornerRadius = dp(12).toFloat()
            }
            addView(routeOverviewTitle, matchWidthParams())
            addView(routeOverviewStats, matchWidthParams(dp(10)))
            addView(fullRouteTitle, matchWidthParams(dp(14)))
            addView(routeOverviewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)).apply {
                topMargin = dp(8)
            })
            addView(simplifiedRouteTitle, matchWidthParams(dp(16)))
            addView(simplifiedRouteStats, matchWidthParams(dp(8)))
            addView(simplifiedRouteOverviewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)).apply {
                topMargin = dp(8)
            })
            visibility = if (routeOverviewToggle.isChecked) View.VISIBLE else View.GONE
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
            addView(Button(context).apply {
                text = "播报频次：${service.speechMode.title}"
                setOnClickListener {
                    val choices = SpeechMode.entries
                    AlertDialog.Builder(context).setTitle("语音播报频次")
                        .setSingleChoiceItems(choices.map { it.title }.toTypedArray(), choices.indexOf(service.speechMode)) { picker, index ->
                            if (service.setSpeechMode(choices[index])) {
                                text = "播报频次：${service.speechMode.title}"
                                picker.dismiss()
                                android.widget.Toast.makeText(context, "已保存。简洁／详细下次算路生效，静音立即生效", android.widget.Toast.LENGTH_LONG).show()
                            } else android.widget.Toast.makeText(context, "播报设置失败，请稍后重试", android.widget.Toast.LENGTH_SHORT).show()
                        }.setNegativeButton("取消", null).show()
                }
            }, matchWidthParams())
            addView(bodyText("简洁减少提醒；详细增加提醒；静音停止全部语音。自动保存，不影响发送给 C3XL 的导航信息频率。"), matchWidthParams())
            addView(Button(context).apply {
                text = "高德 Key 与配置指南"
                setOnClickListener {
                    if (service.canEditAmapKey()) {
                        context.startActivity(Intent(context, AmapKeyActivity::class.java))
                    } else android.widget.Toast.makeText(context, "请先结束当前导航再修改 Key", android.widget.Toast.LENGTH_SHORT).show()
                }
            }, matchWidthParams())
            addView(navAssistBlock, matchWidthParams())
            if (com.garan.tesnav.BuildConfig.EXTENDED_NAV_SOURCES) addView(Button(context).apply {
                text = "原车导航目的地 · 蓝牙自动同步"
                setOnClickListener {
                    context.startActivity(Intent(context, com.garan.tesnav.teslable.TeslaBleNavigationActivity::class.java))
                }
            }, matchWidthParams(dp(12)))
            if (com.garan.tesnav.BuildConfig.EXTENDED_NAV_SOURCES) {
                @Suppress("DEPRECATION")
                addView(Switch(context).apply {
                    text = "动态车速颜色"
                    textSize = 17f
                    isChecked = SpeedColorSettings.read(context).enabled
                    setOnCheckedChangeListener { _, enabled ->
                        context.getSharedPreferences(SpeedColorSettings.PREFS, Context.MODE_PRIVATE)
                            .edit().putBoolean(SpeedColorSettings.ENABLED, enabled).apply()
                    }
                }, matchWidthParams(dp(12)))
                addView(Button(context).apply {
                    text = "车速阈值与颜色"
                    setOnClickListener { SpeedColorSettings.showEditor(context) }
                }, matchWidthParams())
                addView(bodyText("达到设置的车速后使用对应颜色；未达到任何阈值时为白色。"), matchWidthParams())
            }
            addView(syncToggle, matchWidthParams())
            addView(webSocketText, matchWidthParams(dp(12)))
            addView(teslaNavigationText, matchWidthParams(dp(6)))
            addView(webSocketErrorText, matchWidthParams(dp(6)))
            addView(homeAssistantConnectionText, matchWidthParams(dp(16)))
            addView(homeAssistantNavigationText, matchWidthParams(dp(6)))
            addView(destinationText, matchWidthParams(dp(6)))
            addView(homeAssistantErrorText, matchWidthParams(dp(6)))
            addView(navigationErrorText, matchWidthParams(dp(12)))
            addView(routeOverviewToggle, matchWidthParams(dp(18)))
            addView(routeOverviewBlock, matchWidthParams(dp(24)))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val dialog = Dialog(context).apply {
            setContentView(content)
            setCanceledOnTouchOutside(true)
        }

        syncToggle.setOnCheckedChangeListener { _, enabled ->
            if (!applyingSyncToggle) service.setTeslaSyncEnabled(enabled)
        }
        routeOverviewToggle.setOnCheckedChangeListener { _, enabled ->
            routeOverviewPreferences.edit().putBoolean(SHOW_ROUTE_OVERVIEW, enabled).apply()
            routeOverviewBlock.visibility = if (enabled) View.VISIBLE else View.GONE
        }
        fun renderPairing() {
            navAssistPairingState.text = "UDP 4213 自动广播：无需 Token、配对或预填 IP"
            clearNavAssistPairing.visibility = View.GONE
        }
        renderPairing()
        clearNavAssistPairing.setOnClickListener {
            val error = service.clearNavAssistPairing()
            if (error == null) renderPairing()
            renderError(navAssistErrorText, error)
        }
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            observationJobs.forEach(Job::cancel)
            observationJobs.clear()
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            val metrics = context.resources.displayMetrics
            setLayout((metrics.widthPixels * 0.90f).toInt(), (metrics.heightPixels * 0.86f).toInt())
        }

        observationJobs += scope.launch {
            service.teslaSyncEnabled.collect { enabled ->
                val haEnabled = enabled && !service.teslaBleSyncEnabled.value
                if (syncToggle.isChecked != haEnabled) {
                    applyingSyncToggle = true
                    syncToggle.isChecked = haEnabled
                    applyingSyncToggle = false
                }
            }
        }
        observationJobs += scope.launch {
            service.commaConnectionState.collect { state ->
                webSocketText.text = "Comma WebSocket：${state.name}"
                renderTeslaNavigationState(teslaNavigationText, state)
            }
        }
        observationJobs += scope.launch {
            service.commaStateStore.state.collect {
                renderTeslaNavigationState(teslaNavigationText, service.commaConnectionState.value)
            }
        }
        observationJobs += scope.launch {
            service.commaLastError.collect { renderError(webSocketErrorText, it) }
        }
        observationJobs += scope.launch {
            service.navAssistV2Status.collect { status ->
                navAssistStatusText.text = "状态：${navAssistStatusLabel(status)}"
                renderPairing()
            }
        }
        observationJobs += scope.launch {
            service.navAssistV2ResolvedEndpoint.collect { endpoint ->
                navAssistEndpointText.text = "C3XL：${endpoint ?: "—"}"
            }
        }
        observationJobs += scope.launch {
            service.navAssistV2LastError.collect { renderError(navAssistErrorText, it) }
        }
        observationJobs += scope.launch {
            service.homeAssistantClient.connectionState.collect {
                homeAssistantConnectionText.text = "Home Assistant：${it.name}"
            }
        }
        observationJobs += scope.launch {
            service.homeAssistantClient.navigationState.collect { state ->
                homeAssistantNavigationText.text = when (state.navigationActive) {
                    true -> "HA 导航：开启"
                    false -> "HA 导航：关闭"
                    null -> "HA 导航：未知"
                }
                destinationText.text = if (state.navigationActive == true && state.destination != null) {
                    "HA 目的地：${state.destination.latitude}, ${state.destination.longitude}"
                } else {
                    "HA 目的地：—"
                }
            }
        }
        observationJobs += scope.launch {
            service.homeAssistantClient.lastError.collect { renderError(homeAssistantErrorText, it) }
        }
        observationJobs += scope.launch {
            service.selectedNavigationStateFlow.collect { state ->
                renderError(navigationErrorText, state.errorMessage)
                renderRouteOverview(
                    state.routePlanned,
                    routeOverviewStats,
                    routeOverviewView,
                    simplifiedRouteTitle,
                    simplifiedRouteStats,
                    simplifiedRouteOverviewView,
                )
            }
        }
    }

    private fun renderTeslaNavigationState(view: TextView, connectionState: ExportConnectionState) {
        view.text = if (connectionState == ExportConnectionState.CONNECTED) {
            "is_tesla_nav_active：${service.commaStateStore.state.value.isTeslaNavActive}"
        } else {
            "is_tesla_nav_active：断开"
        }
    }

    private fun navAssistStatusLabel(status: NavAssistV2ConnectionStatus): String = when (status) {
        NavAssistV2ConnectionStatus.UNCONFIGURED -> "未配置"
        NavAssistV2ConnectionStatus.SCANNING -> "正在扫描"
        NavAssistV2ConnectionStatus.MULTIPLE_DEVICES -> "多设备冲突（已拒绝连接）"
        NavAssistV2ConnectionStatus.DISCOVERED -> "已发现，等待导航数据"
        NavAssistV2ConnectionStatus.ONLINE -> "在线"
        NavAssistV2ConnectionStatus.ERROR -> "错误"
    }

    private fun renderError(view: TextView, error: String?) {
        view.text = error.orEmpty()
        view.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun renderRouteOverview(
        routePlanned: Boolean,
        statsView: TextView,
        overviewView: RouteOverviewView,
        simplifiedTitle: TextView,
        simplifiedStatsView: TextView,
        simplifiedOverviewView: RouteOverviewView,
    ) {
        val path = service.currentPath().takeIf { routePlanned }
        val coordinates = path?.coordList.orEmpty()
        val signature = if (path == null || coordinates.size < 2) {
            "empty:${service.navigationDataSource.value}"
        } else {
            val first = coordinates.first()
            val last = coordinates.last()
            "${path.pathid}:${path.allLength}:${coordinates.size}:${first.latitude}:${first.longitude}:${last.latitude}:${last.longitude}"
        }
        if (signature == renderedRouteSignature) return
        renderedRouteSignature = signature

        if (path == null || coordinates.size < 2) {
            val unavailable = if (service.navigationDataSource.value == NavigationDataSource.AMAP_AUTO)
                "车机广播不提供完整路线坐标，请在高德车机查看路线" else "当前没有规划路线"
            statsView.text = unavailable
            overviewView.setRoute(emptyList())
            simplifiedTitle.text = "精简坐标点全览"
            simplifiedStatsView.text = unavailable
            simplifiedOverviewView.setRoute(emptyList())
            return
        }

        val points = coordinates.map { GeoPoint(it.latitude, it.longitude) }
        val distances = points.zipWithNext(::distanceMeters)
        val sortedDistances = distances.sorted()
        val minimum = sortedDistances.firstOrNull() ?: 0.0
        val median = sortedDistances.getOrNull(sortedDistances.size / 2) ?: 0.0
        val average = distances.average().takeUnless(Double::isNaN) ?: 0.0
        val maximum = sortedDistances.lastOrNull() ?: 0.0
        val compactJsonBytes = compactJsonSize(points)
        val linkCount = path.steps.orEmpty().sumOf { it.links.orEmpty().size }
        statsView.text = buildString {
            appendLine("路线长度：${formatDistance(path.allLength)}")
            appendLine("坐标点：${points.size} · Steps：${path.steps.orEmpty().size} · Links：$linkCount")
            appendLine(
                "相邻点间距：最小 ${formatMeters(minimum)} · 中位 ${formatMeters(median)} · " +
                    "平均 ${formatMeters(average)} · 最大 ${formatMeters(maximum)}",
            )
            append("紧凑坐标 JSON 估算：${formatBytes(compactJsonBytes)}")
        }
        overviewView.setRoute(points)
        simplifiedTitle.text = "精简坐标点全览（计算中）"
        simplifiedStatsView.text = "正在根据全览图尺寸计算精简数据"
        simplifiedOverviewView.setRoute(emptyList())
        simplifiedOverviewView.post {
            if (renderedRouteSignature != signature) return@post
            val result = simplifyRouteForOverview(points, simplifiedOverviewView)
            renderSimplifiedRoute(points, result, simplifiedTitle, simplifiedStatsView, simplifiedOverviewView)
        }
    }

    private fun renderSimplifiedRoute(
        originalPoints: List<GeoPoint>,
        result: RouteGeometrySimplifier.Result,
        titleView: TextView,
        statsView: TextView,
        overviewView: RouteOverviewView,
    ) {
        val simplifiedPoints = result.points
        titleView.text = "精简坐标点全览（${simplifiedPoints.size} / ${originalPoints.size} 点）"
        val simplifiedDistances = simplifiedPoints.zipWithNext(::distanceMeters)
        val sortedSimplifiedDistances = simplifiedDistances.sorted()
        val simplifiedMinimum = sortedSimplifiedDistances.firstOrNull() ?: 0.0
        val simplifiedMedian = sortedSimplifiedDistances.getOrNull(sortedSimplifiedDistances.size / 2) ?: 0.0
        val simplifiedAverage = simplifiedDistances.average().takeUnless(Double::isNaN) ?: 0.0
        val simplifiedMaximum = sortedSimplifiedDistances.lastOrNull() ?: 0.0
        val reductionPercent = (1.0 - simplifiedPoints.size.toDouble() / originalPoints.size) * 100.0
        statsView.text = buildString {
            appendLine(
                "坐标点：${simplifiedPoints.size} · 减少：${String.format(Locale.US, "%.1f%%", reductionPercent)}",
            )
            appendLine("动态容差：${formatMeters(result.toleranceMeters)}（屏幕偏差 ${SIMPLIFICATION_TOLERANCE_PIXELS} px）")
            appendLine(
                "相邻点间距：最小 ${formatMeters(simplifiedMinimum)} · 中位 ${formatMeters(simplifiedMedian)} · " +
                    "平均 ${formatMeters(simplifiedAverage)} · 最大 ${formatMeters(simplifiedMaximum)}",
            )
            append("紧凑坐标 JSON 估算：${formatBytes(compactJsonSize(simplifiedPoints))}")
        }
        overviewView.setRoute(simplifiedPoints)
    }

    private fun simplifyRouteForOverview(
        points: List<GeoPoint>,
        overviewView: RouteOverviewView,
    ): RouteGeometrySimplifier.Result {
        val drawingPadding = 28f * context.resources.displayMetrics.density
        return RouteGeometrySimplifier.simplifyForViewport(
            points = points,
            viewportWidthPixels = overviewView.width.toDouble(),
            viewportHeightPixels = overviewView.height.toDouble(),
            paddingPixels = drawingPadding.toDouble(),
            tolerancePixels = SIMPLIFICATION_TOLERANCE_PIXELS,
        )
    }

    private fun distanceMeters(first: GeoPoint, second: GeoPoint): Double {
        val latitude1 = Math.toRadians(first.latitude)
        val latitude2 = Math.toRadians(second.latitude)
        val latitudeDelta = latitude2 - latitude1
        val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
        val a = sin(latitudeDelta / 2).let { it * it } +
            cos(latitude1) * cos(latitude2) * sin(longitudeDelta / 2).let { it * it }
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt((1 - a).coerceAtLeast(0.0)))
    }

    private fun compactJsonSize(points: List<GeoPoint>): Int = 2 + points.sumOf {
        it.latitude.toString().length + it.longitude.toString().length + 4
    }

    private fun formatDistance(meters: Int): String = if (meters >= 1000) {
        String.format(Locale.US, "%.1f km", meters / 1000.0)
    } else {
        "$meters m"
    }

    private fun formatMeters(meters: Double): String = String.format(Locale.US, "%.1f m", meters)

    private fun formatBytes(bytes: Int): String = if (bytes >= 1024) {
        String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    } else {
        "$bytes B"
    }

    private fun bodyText(value: String): TextView = TextView(context).apply {
        text = value
        textSize = 16f
        setTextColor(Color.rgb(38, 50, 56))
    }

    private fun errorText(): TextView = bodyText("").apply {
        setTextColor(Color.rgb(198, 40, 40))
        visibility = View.GONE
    }

    private fun matchWidthParams(topMargin: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { this.topMargin = topMargin }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val SIMPLIFICATION_TOLERANCE_PIXELS = 1.5
        const val ROUTE_OVERVIEW_PREFS = "route_overview_settings"
        const val SHOW_ROUTE_OVERVIEW = "show_route_overview"
    }
}
