package com.garan.tesnav.ui

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
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
                "Landroid/app/ActivityTaskManager\$RootTaskInfo;")
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
            val names = type.getField("taskNames").get(stack) as? Array<*> ?: continue
            if (names.isEmpty() || names.any { !it.toString().startsWith("com.autonavi.amapauto/") }) continue
            val id = type.getField(if (getter.name == "getAllRootTaskInfos") "taskId" else "stackId").getInt(stack)
            val current = type.getField("displayId").getInt(stack)
            if (current != display) {
                api.getMethod(if (getter.name == "getAllRootTaskInfos") "moveRootTaskToDisplay" else "moveStackToDisplay",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(tasks, id, display)
            }
            return true
        }
        return false
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
