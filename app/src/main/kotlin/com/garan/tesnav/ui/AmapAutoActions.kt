package com.garan.tesnav.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Standard AMap Auto commands, matching the CP companion's POI interface. */
internal object AmapAutoActions {
    const val PACKAGE = "com.autonavi.amapauto"
    // Installed only while the foreground landscape car-map pane owns the display.
    var embeddedOpener: (() -> Boolean)? = null

    fun open(context: Context): Boolean = runCatching {
        embeddedOpener?.let { return it() }
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** Cancel the running route without launching or moving the car-map activity. */
    fun stopNavigation(context: Context): Boolean = runCatching {
        context.applicationContext.sendBroadcast(Intent("AUTONAVI_STANDARD_BROADCAST_RECV").apply {
            setPackage(PACKAGE)
            putExtra("KEY_TYPE", 10010)
            putExtra("SOURCE_APP", context.packageName)
        })
        true
    }.getOrDefault(false)

    fun navigate(context: Context, name: String, latitude: Double, longitude: Double,
                 stillValid: () -> Boolean = { true }, onDispatched: (Boolean) -> Unit = {}): Boolean {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0) return false
        val appContext = context.applicationContext
        var opener = embeddedOpener
        val configuration = context.resources.configuration
        val embeddedRequired = com.garan.tesnav.BuildConfig.EXTENDED_NAV_SOURCES &&
            configuration.screenWidthDp >= 600 && configuration.screenWidthDp > configuration.screenHeightDp
        if (opener == null) {
            if (embeddedRequired) {
                val opened = runCatching {
                    appContext.startActivity(Intent(appContext, com.garan.tesnav.NavigationActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }.isSuccess
                if (!opened) return false
            } else if (!open(appContext)) return false
        }
        val command = Intent("AUTONAVI_STANDARD_BROADCAST_RECV").apply {
            setPackage(PACKAGE)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            putExtra("KEY_TYPE", 10038)
            putExtra("SOURCE_APP", context.packageName)
            putExtra("POINAME", name.ifBlank { "地图选点" })
            putExtra("LAT", latitude)
            putExtra("LON", longitude)
            putExtra("DEV", 0)
            putExtra("STYLE", 0)
        }
        val handler = Handler(Looper.getMainLooper())
        val deadline = android.os.SystemClock.elapsedRealtime() + 8_000L
        var dispatchPending = false
        // A cold embedded display opens asynchronously. Wait before dispatching the
        // destination instead of permanently failing this target during startup.
        val attempt = object : Runnable {
            override fun run() {
                if (!stillValid() || (opener != null && embeddedOpener !== opener)) {
                    onDispatched(false)
                    return
                }
                if (embeddedRequired && opener == null) {
                    opener = embeddedOpener
                    if (opener == null) {
                        if (android.os.SystemClock.elapsedRealtime() >= deadline) onDispatched(false)
                        else handler.postDelayed(this, 100L)
                        return
                    }
                }
                val ready = if (opener == null) !embeddedRequired
                    else runCatching { opener?.invoke() == true }.getOrDefault(false)
                if (!ready) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) onDispatched(false)
                    else handler.postDelayed(this, 100L)
                    return
                }
                if (!dispatchPending) {
                    dispatchPending = true
                    // Allow the launched receiver its existing registration window.
                    handler.postDelayed(this, 320L)
                    return
                }
                onDispatched(runCatching { appContext.sendBroadcast(command) }.isSuccess)
            }
        }
        handler.post(attempt)
        return true
    }
}
