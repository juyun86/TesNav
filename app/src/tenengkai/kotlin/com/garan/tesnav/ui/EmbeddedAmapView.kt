package com.garan.tesnav.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** Real AMap Auto pixels and touch, confined to the existing landscape map pane. */
class EmbeddedAmapView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val texture = TextureView(context)
    private val message = Button(context).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(18, 23, 29))
        setOnClickListener { requestAccess() }
    }
    private var active = false
    private var attached = false
    private var disposed = false
    private var bridge: EmbeddedAmapBridge? = null
    private var display: VirtualDisplay? = null
    private var surface: Surface? = null
    private var generation = 0
    private var launchPending = false
    private var permissionAsked = false
    private var reconnects = 0
    private val retry = Runnable { ensureConnected() }
    private val embeddedOpener: () -> Boolean = {
        val target = display?.display?.displayId
        if (active && attached && target != null && !launchPending && bridge != null) {
            scheduleDisplayChecks()
            true
        } else {
            ensureConnected()
            false
        }
    }
    private val checkDisplay = Runnable {
        val target = display?.display?.displayId ?: return@Runnable
        val remote = bridge ?: return@Runnable
        val token = generation
        worker.execute {
            if (token != generation || !active || disposed) return@execute
            val success = runCatching { remote.ensureOnDisplay(target) }.getOrDefault(false)
            main.post {
                if (token == generation && active && !success)
                    Log.w(TAG, "display_check_failed display=$target")
            }
        }
    }
    private fun scheduleDisplayChecks() {
        main.removeCallbacks(checkDisplay)
        // Dash rechecks after startup; also cover the 320ms destination broadcast.
        main.postDelayed(checkDisplay, 180)
        main.postDelayed(checkDisplay, 650)
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener {
        main.post { permissionAsked = false; reconnects = 0; ensureConnected() }
    }
    private val binderDead = Shizuku.OnBinderDeadListener { main.post { disconnected() } }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        main.post {
            if (result == PackageManager.PERMISSION_GRANTED) ensureConnected()
            else showMessage("未授权显示高德车机，点击重新授权")
        }
    }

    init {
        setBackgroundColor(Color.rgb(18, 23, 29))
        addView(texture, LayoutParams(-1, -1))
        addView(message, LayoutParams(-1, -2, Gravity.CENTER))
        texture.surfaceTextureListener = this
        texture.setOnTouchListener { _, event -> forwardTouch(event) }
        showMessage("等待显示高德车机")
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        visibility = if (value) View.VISIBLE else View.GONE
        if (value) {
            AmapAutoActions.embeddedOpener = embeddedOpener
            permissionAsked = false; reconnects = 0; ensureConnected()
        } else {
            if (AmapAutoActions.embeddedOpener === embeddedOpener) AmapAutoActions.embeddedOpener = null
            release()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        ensureConnected()
    }

    override fun onDetachedFromWindow() {
        attached = false
        if (AmapAutoActions.embeddedOpener === embeddedOpener) AmapAutoActions.embeddedOpener = null
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        release()
        super.onDetachedFromWindow()
    }

    private fun requestAccess() {
        if (!active || disposed) return
        if (!Shizuku.pingBinder()) {
            val manager = context.packageManager.getLaunchIntentForPackage("roro.stellar.manager")
                ?: context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (manager != null) context.startActivity(manager.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else showMessage("请先安装并启动 Stellar 或 Shizuku")
        } else {
            release()
            permissionAsked = false
            reconnects = 0
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                val manager = context.packageManager.getLaunchIntentForPackage("roro.stellar.manager")
                    ?: context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (manager != null) context.startActivity(manager.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else ensureConnected()
        }
    }

    private fun ensureConnected() {
        if (!active || !attached || disposed) return
        main.removeCallbacks(retry)
        if (!Shizuku.pingBinder()) {
            showMessage("Stellar 服务未运行，点击打开组件")
            return // Binder listener reconnects when the component returns; no background poll.
        }
        if (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(-1) != PackageManager.PERMISSION_GRANTED) {
            showMessage("需要授权显示高德车机")
            if (!permissionAsked && !Shizuku.shouldShowRequestPermissionRationale()) {
                permissionAsked = true
                runCatching { Shizuku.requestPermission(6103) }.onFailure { showMessage("点击授权显示高德车机") }
            }
            return
        }
        if (bridge == null) {
            try { bridge = EmbeddedAmapBridge() }
            catch (error: Exception) {
                Log.w(TAG, "bridge_failed type=${error.javaClass.simpleName}")
                scheduleRetry()
                return
            }
        }
        if (!texture.isAvailable || texture.width <= 0 || texture.height <= 0 || launchPending) return
        if (display != null) return
        try {
            surface = Surface(texture.surfaceTexture)
            val dpi = resources.displayMetrics.densityDpi.coerceIn(160, 320)
            display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .createVirtualDisplay("TenengkaiCarMap", texture.width, texture.height, dpi, surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            val id = display?.display?.displayId ?: error("NoDisplay")
            // This version's launcher is UsbFillActivity, which forwards to display 0.
            val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("androidauto://launch"))
                .setPackage("com.autonavi.amapauto")
            val info = context.packageManager.resolveActivity(mapIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            val component = if (info != null) "${info.packageName}/${info.name}"
                else context.packageManager.getLaunchIntentForPackage("com.autonavi.amapauto")
                    ?.component?.flattenToString() ?: error("MissingAmapAuto")
            val token = ++generation
            val remote = bridge ?: error("NoBridge")
            launchPending = true
            showMessage("正在显示高德车机")
            worker.execute {
                val success = runCatching {
                    remote.launch(id, component)
                }.onFailure { Log.w(TAG, "launch_failed type=${it.javaClass.simpleName} cause=${it.cause?.javaClass?.simpleName}") }.getOrDefault(false)
                main.post {
                    if (token != generation || disposed || !active) return@post
                    launchPending = false
                    if (success) {
                        message.visibility = View.GONE
                        Log.i(TAG, "launch_complete display=$id")
                        scheduleDisplayChecks()
                    } else {
                        releaseDisplay()
                        scheduleRetry()
                    }
                }
            }
        } catch (error: Exception) {
            releaseDisplay()
            showMessage(if (error.message == "MissingAmapAuto") "未安装高德车机版" else "无法显示高德车机，点击重试")
            Log.w(TAG, "display_failed type=${error.javaClass.simpleName}")
        }
    }

    private fun forwardTouch(event: MotionEvent): Boolean {
        if (!active || display == null || message.visibility == View.VISIBLE) return false
        val remote = bridge ?: return false
        val target = display?.display?.displayId ?: return false
        val copy = MotionEvent.obtain(event)
        worker.execute {
            try {
                if (!remote.touch(target, copy))
                    main.post { showMessage("触控连接异常，点击重试") }
            } catch (_: Exception) { main.post { disconnected() } }
            finally { copy.recycle() }
        }
        return true
    }

    private fun disconnected() {
        if (disposed) return
        release()
        if (active) {
            showMessage("高德车机连接已断开，等待恢复")
            if (Shizuku.pingBinder()) scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        showMessage("高德车机连接失败，点击重试")
        main.removeCallbacks(retry)
        if (active && attached && reconnects < 3) {
            main.postDelayed(retry, (1L shl reconnects++) * 1000)
        }
    }

    private fun showMessage(text: String) { message.text = text; message.visibility = View.VISIBLE }
    private fun releaseDisplay() {
        main.removeCallbacks(checkDisplay)
        generation++
        launchPending = false
        display?.release(); display = null
        surface?.release(); surface = null
    }
    private fun releaseBridge() { bridge = null }
    private fun release() { main.removeCallbacks(retry); releaseDisplay(); releaseBridge() }
    fun dispose() {
        if (AmapAutoActions.embeddedOpener === embeddedOpener) AmapAutoActions.embeddedOpener = null
        disposed = true; release(); worker.shutdown()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { ensureConnected() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (width > 0 && height > 0) display?.resize(width, height, resources.displayMetrics.densityDpi.coerceIn(160, 320))
    }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { releaseDisplay(); return true }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    companion object { private const val TAG = "Tenengkai-CarMap" }
}
