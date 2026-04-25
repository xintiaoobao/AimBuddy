package com.aimbuddy

import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Non-root touch injector using hidden InputManager APIs from a Shizuku-authorized process.
 */
class ShizukuInputInjector {

    companion object {
        private const val TAG = "ShizukuInputInjector"
        private const val INJECT_MODE_ASYNC = 0
        private const val AIM_POINTER_ID = 5
    }

    private val inputManagerProxy: Any? by lazy {
        buildPrivilegedInputManagerProxy()
    }

    private val injectMethod by lazy {
        resolveInjectMethod(inputManagerProxy)
    }

    private val pointerProperties = arrayOf(
        MotionEvent.PointerProperties().apply {
            id = AIM_POINTER_ID
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
    )

    private val pointerCoords = arrayOf(
        MotionEvent.PointerCoords().apply {
            pressure = 1f
            size = 1f
        }
    )

    @Volatile
    private var pointerDown = false
    private var lastX = 0f
    private var lastY = 0f

    private fun buildPrivilegedInputManagerProxy(): Any? {
        return try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Shizuku not ready for privileged input manager")
                return null
            }

            val baseBinder = SystemServiceHelper.getSystemService("input")
            if (baseBinder == null) {
                Log.e(TAG, "Failed to get input service binder")
                return null
            }

            val wrappedBinder: IBinder = ShizukuBinderWrapper(baseBinder)
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            asInterface.isAccessible = true
            asInterface.invoke(null, wrappedBinder)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to build privileged input manager proxy: ${t.message}", t)
            null
        }
    }

    private fun resolveInjectMethod(proxy: Any?): java.lang.reflect.Method? {
        if (proxy == null) {
            return null
        }
        return try {
            val direct = proxy.javaClass.methods.firstOrNull {
                it.name == "injectInputEvent" &&
                    it.parameterTypes.size == 2 &&
                    InputEvent::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            direct?.also { it.isAccessible = true }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to resolve inject method: ${t.message}", t)
            null
        }
    }

    private fun invokeInject(event: MotionEvent): Boolean {
        val proxy = inputManagerProxy ?: return false
        val method = injectMethod ?: return false
        return try {
            val result = method.invoke(proxy, event, INJECT_MODE_ASYNC)
            when (result) {
                is Boolean -> result
                else -> true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "invokeInject failed: ${t.message}", t)
            false
        }
    }

    @Synchronized
    fun injectAimMove(screenX: Float, screenY: Float, isFirst: Boolean): Boolean {
        return try {
            val now = SystemClock.uptimeMillis()
            pointerCoords[0].x = screenX
            pointerCoords[0].y = screenY
            pointerCoords[0].pressure = 1f
            pointerCoords[0].size = 1f

            val action = if (isFirst || !pointerDown) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
            val source = InputDevice.SOURCE_TOUCHSCREEN
            val event = MotionEvent.obtain(
                now,
                now,
                action,
                1,
                pointerProperties,
                pointerCoords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                source,
                0
            )
            event.source = source

            val injected = invokeInject(event)
            event.recycle()

            if (!injected) {
                pointerDown = false
                return false
            }

            pointerDown = true
            lastX = screenX
            lastY = screenY
            true
        } catch (t: Throwable) {
            Log.e(TAG, "injectAimMove failed: ${t.message}", t)
            pointerDown = false
            false
        }
    }

    @Synchronized
    fun releaseAim(): Boolean {
        if (!pointerDown) {
            return true
        }

        return try {
            val now = SystemClock.uptimeMillis()
            pointerCoords[0].x = lastX
            pointerCoords[0].y = lastY
            pointerCoords[0].pressure = 0f
            pointerCoords[0].size = 0f

            val event = MotionEvent.obtain(
                now,
                now,
                MotionEvent.ACTION_UP,
                1,
                pointerProperties,
                pointerCoords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
            )

            val injected = invokeInject(event)
            event.recycle()

            if (!injected) {
                pointerDown = false
                return false
            }

            pointerDown = false
            true
        } catch (t: Throwable) {
            Log.e(TAG, "releaseAim failed: ${t.message}", t)
            pointerDown = false
            false
        }
    }
}
