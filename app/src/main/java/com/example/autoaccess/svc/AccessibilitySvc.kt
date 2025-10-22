package com.example.autoaccess.svc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.hardware.display.DisplayManager
import kotlin.math.roundToInt

class AccessibilitySvc : AccessibilityService() {

    companion object {
        private const val TAG = "AutoAccess"
        @Volatile var instance: AccessibilitySvc? = null
            private set
    }

    data class DispInfo(
        val w: Int, val h: Int,
        val insetLeft: Int, val insetTop: Int, val insetRight: Int, val insetBottom: Int
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilitySvc connected")

        try {
            val sp = getSharedPreferences("aliases", MODE_PRIVATE)
            for ((k, v) in sp.all) if (v is String) AliasRegistry.put(k, v)
        } catch (t: Throwable) {
            Log.w(TAG, "load aliases failed", t)
        }
        if (!HttpServer.isRunning()) HttpServer.start(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "AccessibilitySvc destroyed")
        HttpServer.stop()
        instance = null
    }

    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    fun tap(x: Float, y: Float, durMs: Long = 80L): Boolean {
        val g = buildTap(x.toInt(), y.toInt(), durMs)
        val scheduled = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "tap completed at (${x.toInt()},${y.toInt()})")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "tap CANCELLED at (${x.toInt()},${y.toInt()})")
            }
        }, null)
        Log.i(TAG, "tap schedule=$scheduled at (${x.toInt()},${y.toInt()})")
        return scheduled
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long = 300L): Boolean {
        val g = buildSwipe(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), durMs)
        val scheduled = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "swipe completed ${x1.toInt()},${y1.toInt()} -> ${x2.toInt()},${y2.toInt()}")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "swipe CANCELLED ${x1.toInt()},${y1.toInt()} -> ${x2.toInt()},${y2.toInt()}")
            }
        }, null)
        Log.i(TAG, "swipe schedule=$scheduled ${x1.toInt()},${y1.toInt()} -> ${x2.toInt()},${y2.toInt()}")
        return scheduled
    }

    fun back()   { performGlobalAction(GLOBAL_ACTION_BACK) }
    fun home()   { performGlobalAction(GLOBAL_ACTION_HOME) }
    fun recent() { performGlobalAction(GLOBAL_ACTION_RECENTS) }

    fun buildTap(x: Int, y: Int, ms: Long = 80L): GestureDescription {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(p, 0, ms.coerceAtLeast(1L))
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    fun buildSwipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long = 300L): GestureDescription {
        val p = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val dur = ms.coerceAtLeast(1L)
        val stroke = if (Build.VERSION.SDK_INT >= 26)
            GestureDescription.StrokeDescription(p, 0, dur, false)
        else
            GestureDescription.StrokeDescription(p, 0, dur)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    /** Giữ lại cho tương thích cũ (trả logical size). */
    fun displaySize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    /**
     * MỚI: trả về kích thước **vật lý** (physical) của display + insets **đã quy đổi về physical**.
     * Dùng số này cho mọi phép map tọa độ gesture.
     */
    fun displayInfo(): DispInfo {
        return if (Build.VERSION.SDK_INT >= 30) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            val winW = bounds.width()
            val winH = bounds.height()

            // Lấy kích thước PHYSICAL theo rotation hiện tại
            val dmgr = getSystemService(DISPLAY_SERVICE) as DisplayManager
            val disp: Display = dmgr.getDisplay(Display.DEFAULT_DISPLAY)
            val mode = disp.mode
            val rot = disp.rotation
            val physW = if (rot == android.view.Surface.ROTATION_90 || rot == android.view.Surface.ROTATION_270)
                mode.physicalHeight else mode.physicalWidth
            val physH = if (rot == android.view.Surface.ROTATION_90 || rot == android.view.Surface.ROTATION_270)
                mode.physicalWidth else mode.physicalHeight

            // Insets ở WINDOW space → scale sang PHYSICAL
            val insWin = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val kx = if (winW > 0) physW.toFloat() / winW.toFloat() else 1f
            val ky = if (winH > 0) physH.toFloat() / winH.toFloat() else 1f
            val l = (insWin.left   * kx).roundToInt()
            val t = (insWin.top    * ky).roundToInt()
            val r = (insWin.right  * kx).roundToInt()
            val b = (insWin.bottom * ky).roundToInt()

            DispInfo(physW, physH, l, t, r, b)
        } else {
            val dm = resources.displayMetrics
            DispInfo(dm.widthPixels, dm.heightPixels, 0, 0, 0, 0)
        }
    }
}
