package com.garan.tesnav.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.service.NavigationForegroundService
import com.garan.tesnav.service.NavigationDataSource
import com.garan.tesnav.BuildConfig
import com.garan.tesnav.model.withDisplayExpiry
import com.garan.tesnav.export.NAVASSIST_UDP_RECEIVE_WINDOW_MS
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** Compact live status for the fields that are useful during a road test. */
class NavigationStateDialog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val service: NavigationForegroundService,
) {
    private var updateJob: Job? = null

    fun show() {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val statusText = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.rgb(38, 50, 56))
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(
                statusText,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        val title = TextView(context).apply {
            text = "导航观察"
            textSize = 20f
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
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            updateJob?.cancel()
            updateJob = null
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            val metrics = context.resources.displayMetrics
            setLayout((metrics.widthPixels * 0.90f).toInt(), (metrics.heightPixels * 0.86f).toInt())
        }
        updateJob = scope.launch {
            combine(service.selectedNavigationStateFlow,
                service.navAssistDeliveryState, service.oemVehicleLaneState.withDisplayExpiry(
                    BuildConfig.NAV_ASSIST_V2_INTERVAL_MS + NAVASSIST_UDP_RECEIVE_WINDOW_MS,
                    SystemClock::elapsedRealtime),
                service.amapAutoBroadcasts.map(AmapAutoDisplayPresenter::details).distinctUntilChanged()) { state, delivery, oem, broadcasts ->
                val provider = runCatching { NavigationDataSource.valueOf(state.navigationProvider).displayName }.getOrDefault("未知")
                "来源：$provider\n\n" + displayText(state) + "\n\n" +
                    NavigationDeliveryPresenter.text(delivery, SystemClock.elapsedRealtime()) +
                    "\n\n" + LaneGuidancePresenter.observationText(state, oem) +
                    if (BuildConfig.EXTENDED_NAV_SOURCES && state.navigationProvider == NavigationDataSource.AMAP_AUTO.name)
                        "\n\n$broadcasts" else ""
            }.collect { text ->
                statusText.text = text + "\n\n" + service.diagnosticRecordingStatus
            }
        }
    }

    private fun displayText(state: NavigationState): String {
        fun distance(meters: Int?): String = when {
            meters == null -> "—"
            meters >= 1000 -> String.format(Locale.CHINA, "%.1f 公里", meters / 1000.0)
            else -> "$meters 米"
        }
        val duration = state.routeRemainTimeSeconds?.let { "${ceil(it / 60.0).toInt().coerceAtLeast(1)} 分钟" } ?: "—"
        val arrival = state.routeRemainTimeSeconds?.let {
            SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(System.currentTimeMillis() + it * 1000L))
        } ?: "—"
        val source = when (state.locationSourceStatus) {
            "vehicle", "vehicle-can" -> "车辆 GPS（C3 回传）"
            "amap_auto" -> "高德车机定位"
            "phone", "phone-gps" -> "手机 GPS"
            else -> "暂无可用定位"
        }
        val light = state.trafficLight?.takeIf { System.currentTimeMillis() - it.observedAtMs in 0..3_000 }?.let {
            val color = when (it.status) { 2 -> "红灯"; 3 -> "绿灯"; 4 -> "黄灯"; else -> "信号灯" }
            val direction = when (it.direction) { 1 -> "左转"; 2 -> "右转"; 3 -> "掉头"; 4 -> "直行"; else -> "方向未知" }
            val elapsedSeconds = ((System.currentTimeMillis() - it.observedAtMs) / 1000L).toInt()
            "$direction · $color${it.countdownSeconds?.let { seconds -> " ${maxOf(0, seconds - elapsedSeconds)} 秒" }.orEmpty()}"
        } ?: "暂无"
        val next = listOfNotNull(state.nextRoad, state.maneuver.name.takeUnless { it == "NONE" || it == "UNKNOWN" }).joinToString(" · ").ifBlank { "—" }
        return "行程\n" +
            "剩余 $duration · ${distance(state.routeRemainDistanceMeters)}\n预计 $arrival 到达\n\n" +
            "下一步\n$next · ${distance(state.nextTurnDistanceMeters)}\n\n" +
            "红绿灯\n$light\n\n" +
            "定位\n$source · ${if (state.gpsSignalWeak) "弱" else "正常"} · 精度 ${state.accuracy?.let { String.format(Locale.CHINA, "%.1f 米", it) } ?: "—"}\n\n" +
            "导航联动\n路线${if (state.routeMatched == true) "已匹配" else "未匹配"} · 本地来源${if (state.navAssistControlAllowed) "有效" else "待确认（${state.navAssistSourceStatus}）"}"
    }
}
