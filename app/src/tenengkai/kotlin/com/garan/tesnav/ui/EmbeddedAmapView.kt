package com.garan.tesnav.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
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
import java.util.concurrent.atomic.AtomicInteger

/** Real AMap Auto pixels and touch, confined to the existing landscape map pane. */
class EmbeddedAmapView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private val worker = displayWorker
    private val texture = TextureView(context)
    private val c3Overlay = C3NativeOverlayView(context)
    private val message = Button(context).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(18, 23, 29))
        setOnClickListener { requestAccess() }
    }
    @Volatile private var active = false
    @Volatile private var attached = false
    @Volatile private var disposed = false
    private var bridge: EmbeddedAmapBridge? = null
    private var display: VirtualDisplay? = null
    private var surface: Surface? = null
    private var displayTexture: SurfaceTexture? = null
    private var displayWidth = 0
    private var displayHeight = 0
    @Volatile private var generation = 0
    private var launchPending = false
    private var permissionAsked = false
    private var reconnects = 0
    private var retryPending = false
    private var retryExhausted = false
    private val pendingTouches = AtomicInteger()
    private val retry = Runnable { retryPending = false; ensureConnected() }
    private val embeddedOpener: () -> Boolean = {
        val target = display?.display?.displayId
        if (active && attached && target != null && !launchPending && bridge != null) {
            scheduleDisplayChecks()
            true
        } else {
            if (!retryPending && !retryExhausted) ensureConnected()
            false
        }
    }
    private val checkDisplay = Runnable {
        val target = display?.display?.displayId ?: return@Runnable
        val remote = bridge ?: return@Runnable
        val token = generation
        worker.execute {
            if (!embeddedTaskCurrent(token, generation, active, attached, disposed)) return@execute
            val success = runCatching { remote.ensureOnDisplay(target) }.getOrDefault(false)
            main.post {
                if (embeddedTaskCurrent(token, generation, active, attached, disposed) && !success)
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
        main.post {
            if (disposed) return@post
            permissionAsked = false; resetRetries(); ensureConnected()
        }
    }
    private val binderDead = Shizuku.OnBinderDeadListener { main.post { disconnected() } }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        main.post {
            if (disposed || !attached || !active) return@post
            if (result == PackageManager.PERMISSION_GRANTED) ensureConnected()
            else showMessage("未授权显示高德车机，点击重新授权")
        }
    }

    init {
        setBackgroundColor(Color.rgb(18, 23, 29))
        addView(texture, LayoutParams(-1, -1))
        addView(c3Overlay, LayoutParams(-1, -1))
        addView(message, LayoutParams(-1, -2, Gravity.CENTER))
        texture.surfaceTextureListener = this
        texture.setOnTouchListener { _, event -> forwardTouch(event) }
        showMessage("等待显示高德车机")
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        c3Overlay.setActive(value)
        visibility = if (value) View.VISIBLE else View.GONE
        if (value) {
            AmapAutoActions.embeddedOpener = embeddedOpener
            permissionAsked = false; resetRetries(); ensureConnected()
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
            resetRetries()
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                val manager = context.packageManager.getLaunchIntentForPackage("roro.stellar.manager")
                    ?: context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (manager != null) context.startActivity(manager.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else ensureConnected()
        }
    }

    private fun ensureConnected() {
        if (!active || !attached || disposed || retryPending || retryExhausted) return
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
        if (display != null) { updateTextureTransform(); return }
        try {
            val retained = retainedDisplay
            if (retained != null) {
                retainedDisplay = null
                display = retained.display; surface = retained.surface; displayTexture = retained.texture
                displayWidth = retained.width; displayHeight = retained.height
                if (texture.surfaceTexture !== retained.texture) texture.setSurfaceTexture(retained.texture)
                Log.i(TAG, "display_reattached display=${retained.display.display.displayId}")
            } else {
                displayTexture = texture.surfaceTexture
                ownedTexture = displayTexture
                surface = Surface(displayTexture)
                // Preserve the host-pane coordinate system used by the verified
                // native AR baseline. AMap's camera overlays are calibrated in this
                // display space; letterboxing a different aspect ratio offsets them.
                displayWidth = AMAP_DISPLAY_WIDTH
                displayHeight = AMAP_DISPLAY_HEIGHT
                displayTexture?.setDefaultBufferSize(displayWidth, displayHeight)
                display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                    .createVirtualDisplay("TenengkaiCarMap", displayWidth, displayHeight, AMAP_DISPLAY_DPI, surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            }
            updateTextureTransform()
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
                if (!embeddedTaskCurrent(token, generation, active, attached, disposed)) return@execute
                val success = runCatching {
                    remote.launch(id, component)
                }.onFailure { Log.w(TAG, "launch_failed type=${it.javaClass.simpleName} cause=${it.cause?.javaClass?.simpleName}") }.getOrDefault(false)
                main.post {
                    if (!embeddedTaskCurrent(token, generation, active, attached, disposed)) return@post
                    launchPending = false
                    if (success) {
                        resetRetries()
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
        if (!active || !attached || disposed || display == null || message.visibility == View.VISIBLE) return false
        val remote = bridge ?: return false
        val target = display?.display?.displayId ?: return false
        val token = generation
        val viewport = embeddedDisplayViewport(texture.width, texture.height, displayWidth, displayHeight) ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            (event.x < viewport.left || event.x >= viewport.left+viewport.width ||
                event.y < viewport.top || event.y >= viewport.top+viewport.height)) return false
        // A blocked Binder must not accumulate an unbounded queue of MotionEvents.
        if (pendingTouches.get() >= 8 && event.actionMasked == MotionEvent.ACTION_MOVE) return true
        if (pendingTouches.get() >= 32) {
            disconnected()
            return true
        }
        val copy = MotionEvent.obtain(event)
        // Invert the exact letterbox transform; bars cannot activate map controls.
        copy.transform(Matrix().apply {
            setScale(1/viewport.scale, 1/viewport.scale)
            postTranslate(-viewport.left/viewport.scale, -viewport.top/viewport.scale)
        })
        pendingTouches.incrementAndGet()
        worker.execute {
            try {
                if (!embeddedTaskCurrent(token, generation, active, attached, disposed)) return@execute
                if (!remote.touch(target, copy))
                    main.post {
                        if (embeddedTaskCurrent(token, generation, active, attached, disposed)) disconnected()
                    }
            } catch (_: Exception) {
                main.post { if (embeddedTaskCurrent(token, generation, active, attached, disposed)) disconnected() }
            } finally { copy.recycle(); pendingTouches.decrementAndGet() }
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
            retryPending = true
            main.postDelayed(retry, (1L shl reconnects++) * 1000)
        } else {
            retryExhausted = true
        }
    }

    private fun resetRetries() {
        main.removeCallbacks(retry)
        retryPending = false; retryExhausted = false; reconnects = 0
    }

    private fun showMessage(text: String) { message.text = text; message.visibility = View.VISIBLE }
    private fun releaseDisplay() {
        main.removeCallbacks(checkDisplay)
        generation++
        launchPending = false
        val previous = display
        if (previous != null) {
            retainedDisplay = RetainedDisplay(previous, surface, displayTexture!!, displayWidth, displayHeight)
            Log.i(TAG, "display_retained display=${previous.display.displayId}")
        } else {
            surface?.release()
            if (ownedTexture === displayTexture) ownedTexture = null
        }
        display = null; surface = null; displayTexture = null
    }
    private fun releaseBridge() { bridge = null }
    private fun release() { main.removeCallbacks(retry); retryPending = false; releaseDisplay(); releaseBridge() }
    fun dispose() {
        if (AmapAutoActions.embeddedOpener === embeddedOpener) AmapAutoActions.embeddedOpener = null
        disposed = true; release()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { ensureConnected() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // Retain task dimensions and navigation across rotation; only fit the host view.
        if (displayWidth > 0 && displayHeight > 0) surface.setDefaultBufferSize(displayWidth, displayHeight)
        updateTextureTransform()
    }
    private fun updateTextureTransform() {
        val viewport = embeddedDisplayViewport(texture.width, texture.height, displayWidth, displayHeight) ?: return
        c3Overlay.setViewport(viewport.left, viewport.top, viewport.width, viewport.height)
        texture.setTransform(Matrix().apply {
            setScale(viewport.width/texture.width, viewport.height/texture.height,
                texture.width/2f, texture.height/2f)
        })
    }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releaseDisplay()
        return ownedTexture !== surface
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private data class RetainedDisplay(val display: VirtualDisplay, val surface: Surface?,
        val texture: SurfaceTexture, val width: Int, val height: Int)
    companion object {
        private const val TAG = "Tenengkai-CarMap"
        private const val AMAP_DISPLAY_WIDTH = 1795
        private const val AMAP_DISPLAY_HEIGHT = 1528
        private const val AMAP_DISPLAY_DPI = 320
        private val displayWorker = Executors.newSingleThreadExecutor()
        // One process-owned session, without an Activity/View reference. Stop input
        // on background/detach, but keep AMap's display and navigation task intact.
        private var retainedDisplay: RetainedDisplay? = null
        private var ownedTexture: SurfaceTexture? = null
    }
}
