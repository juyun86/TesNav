package com.garan.tesnav.ui

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper

/** The same remote system interfaces as Dash; avoids Stellar 1.1's UserService bug. */
internal class EmbeddedAmapBridge {
    init {
        if (Build.VERSION.SDK_INT >= 28) {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/os/ServiceManager;",
                "Landroid/app/IActivityManager", "Landroid/hardware/input/IInputManager",
                "Landroid/view/InputEvent;", "Landroid/app/ActivityOptions;",
                "Landroid/app/IActivityTaskManager", "Landroid/app/ActivityManager\$StackInfo;",
                "Landroid/app/ActivityTaskManager\$RootTaskInfo;", "Landroid/app/TaskInfo;")
        }
    }

    private fun remote(service: String, descriptor: String): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, service) as IBinder
        return Class.forName("$descriptor\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(binder))
    }
    private val activity by lazy { remote("activity", "android.app.IActivityManager") }
    private val input by lazy { remote("input", "android.hardware.input.IInputManager") }
    private val tasks by lazy { remote("activity_task", "android.app.IActivityTaskManager") }
    fun ensureOnDisplay(display: Int): Boolean {
        require(display > 0)
        return moveExisting(display)
    }

    private fun moveExisting(display: Int): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val api = Class.forName("android.app.IActivityTaskManager")
        val getter = api.methods.firstOrNull { it.name == "getAllRootTaskInfos" }
            ?: api.getMethod("getAllStackInfos")
        val stacks = getter.invoke(tasks) as List<*>
        for (stack in stacks.filterNotNull()) {
            val type = stack.javaClass
            val top = type.getField("topActivity").get(stack) as? ComponentName ?: continue
            if (top.packageName != "com.autonavi.amapauto") continue
            val names = embeddedTaskNames(stack) ?: continue
            if (names.isEmpty() || names.any { !it.toString().startsWith("com.autonavi.amapauto/") }) continue
            val rootTask = getter.name == "getAllRootTaskInfos"
            val id = type.getField(if (rootTask) "taskId" else "stackId").getInt(stack)
            val taskId = if (rootTask) id else
                (type.getField("taskIds").get(stack) as? IntArray)?.lastOrNull() ?: continue
            val current = type.getField("displayId").getInt(stack)
            if (current != display) {
                api.getMethod(if (rootTask) "moveRootTaskToDisplay" else "moveStackToDisplay",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(tasks, id, display)
            }
            // Match Dash's complete handoff: moving the task is not enough. AMap
            // recalculates its AR viewport only after its virtual display becomes
            // focused. Android 11+ exposes focusTopTask(displayId); this Android 10
            // tablet exposes the equivalent setFocusedTask(taskId).
            val focusTopTask = api.methods.firstOrNull {
                it.name == "focusTopTask" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (focusTopTask != null) focusTopTask.invoke(tasks, display)
            else api.getMethod("setFocusedTask", Int::class.javaPrimitiveType).invoke(tasks, taskId)
            return true
        }
        return false
    }

    /**
     * AMap builds its AR overlay against the camera frame size (1280x720) instead of the
     * 1795x1528 window, so the whole overlay lands in the window's top-left corner.
     * A window-bounds change is what makes it recompute, and that is exactly what a manual
     * split screen does. A fullscreen task refuses `resizeTask`, so drive the same
     * `resizeStack` the shell uses: give the stack bounds, then hand it back to fullscreen.
     */
    fun nudgeWindow(display: Int, width: Int, height: Int): Boolean {
        val stack = amapStackId(display) ?: return false
        val inset = 4
        val resized = resizeStack(stack, inset, inset, width - inset, height - inset)
        Thread.sleep(300)
        // Leaving explicit bounds behind breaks the manual split-screen recovery, which is
        // the only fallback we have, so always return the stack to fullscreen afterwards.
        val fullscreen = setFullscreen(stack)
        val restored = fullscreen || resizeStack(stack, 0, 0, width, height)
        Log.i("Tenengkai-CarMap",
            "nudge stack=$stack resized=$resized fullscreen=$fullscreen restored=$restored")
        return resized && restored
    }

    private fun amapStackId(display: Int): Int? {
        if (Build.VERSION.SDK_INT < 29) return null
        val api = Class.forName("android.app.IActivityTaskManager")
        val getter = api.methods.firstOrNull { it.name == "getAllRootTaskInfos" }
            ?: api.getMethod("getAllStackInfos")
        val stacks = runCatching { getter.invoke(tasks) as List<*> }.getOrNull() ?: return null
        val rootTask = getter.name == "getAllRootTaskInfos"
        for (stack in stacks.filterNotNull()) {
            val type = stack.javaClass
            val top = runCatching { type.getField("topActivity").get(stack) as? ComponentName }
                .getOrNull() ?: continue
            if (top.packageName != "com.autonavi.amapauto") continue
            val names = runCatching { embeddedTaskNames(stack) }.getOrNull() ?: continue
            if (names.isEmpty() || names.any { !it.toString().startsWith("com.autonavi.amapauto/") }) continue
            val current = runCatching { type.getField("displayId").getInt(stack) }.getOrDefault(-1)
            if (current != display) continue
            return runCatching {
                type.getField(if (rootTask) "taskId" else "stackId").getInt(stack)
            }.getOrNull()
        }
        return null
    }

    /** Same call `am stack resize <id> <l> <t> <r> <b>` makes. */
    private fun resizeStack(stack: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        val api = Class.forName("android.app.IActivityTaskManager")
        val method = api.methods.firstOrNull {
            it.name == "resizeStack" && it.parameterCount >= 3 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Rect::class.java
        } ?: return false
        val args = arrayOfNulls<Any?>(method.parameterCount)
        args[0] = stack
        args[1] = Rect(left, top, right, bottom)
        for (index in 2 until method.parameterCount) {
            args[index] = when (method.parameterTypes[index]) {
                java.lang.Boolean.TYPE -> false
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
        return runCatching { method.invoke(tasks, *args); true }.getOrDefault(false)
    }

    private fun setFullscreen(stack: Int): Boolean {
        val api = Class.forName("android.app.IActivityTaskManager")
        val method = api.methods.firstOrNull {
            it.name == "setStackWindowingMode" && it.parameterCount >= 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return false
        val args = arrayOfNulls<Any?>(method.parameterCount)
        args[0] = stack
        args[1] = 1 // WINDOWING_MODE_FULLSCREEN
        for (index in 2 until method.parameterCount) {
            args[index] = if (method.parameterTypes[index] == java.lang.Boolean.TYPE) false else 0
        }
        return runCatching { method.invoke(tasks, *args); true }.getOrDefault(false)
    }

    fun launch(display: Int, component: String): Boolean {
        require(display > 0 && component.startsWith("com.autonavi.amapauto/"))
        if (moveExisting(display)) return true
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("androidauto://launch"))
            .setComponent(ComponentName.unflattenFromString(component))
            .setPackage("com.autonavi.amapauto").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(display)
        ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
            .invoke(options, 1)
        val name = if (Build.VERSION.SDK_INT >= 30) "startActivityAsUserWithFeature" else "startActivity"
        val method = Class.forName("android.app.IActivityManager").methods.single {
            it.name == name && it.parameterCount == if (Build.VERSION.SDK_INT >= 30) 12 else 10
        }
        val values = if (Build.VERSION.SDK_INT >= 30)
            arrayOf(null, "com.android.shell", null, intent, null, null, null, 0, 0, null, options.toBundle(), 0)
        else arrayOf(null, "com.android.shell", intent, null, null, null, 0, 0, null, options.toBundle())
        val accepted = (method.invoke(activity, *values) as Int) >= 0
        if (accepted) moveExisting(display)
        return accepted
    }

    fun touch(display: Int, event: MotionEvent): Boolean {
        require(display > 0)
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, display)
        return Class.forName("android.hardware.input.IInputManager")
            .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            .invoke(input, event, 0) as Boolean
    }
}

/** Android 12 RootTaskInfo renamed StackInfo.taskNames to childTaskNames. */
internal fun embeddedTaskNames(task: Any): Array<*>? {
    val type = task.javaClass
    val field = try { type.getField("childTaskNames") }
        catch (_: NoSuchFieldException) { type.getField("taskNames") }
    return field.get(task) as? Array<*>
}
