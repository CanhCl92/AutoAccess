package com.example.autoaccess.svc

import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import com.example.autoaccess.cap.ScreenCapture
import com.example.autoaccess.svc.Step.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * GestureEngine:
 * - Tìm toạ độ theo CAPTURE space (bitmap từ MediaProjection).
 * - Map sang GESTURE space theo PHYSICAL display + system insets (đã quy đổi về physical).
 * - Tôn trọng contentRect() của ScreenCapture (nếu có letterbox).
 */
class GestureEngine {

    companion object {
        private const val TAG = "AutoAccess"
    }

    @Volatile private var worker: Thread? = null
    @Volatile private var stopFlag = false

    @Volatile var currentMacroId: String? = null
        private set
    @Volatile var currentStep: Int = -1
        private set

    fun isRunning(): Boolean = worker?.isAlive == true

    fun run(macro: Macro) {
        stop()
        stopFlag = false
        currentMacroId = macro.id
        currentStep = -1

        worker = Thread {
            try {
                Log.i(TAG, "engine start id=${macro.id} steps=${macro.steps.size}")
                execSteps(macro.steps)
            } catch (t: Throwable) {
                Log.e(TAG, "engine crashed", t)
            } finally {
                currentStep = -1
                currentMacroId = null
                Log.i(TAG, "engine finished")
            }
        }.also { it.start() }
    }

    fun stop() {
        stopFlag = true
        worker?.interrupt()
        worker = null
    }

    private fun execSteps(steps: List<Step>) {
        val svc = AccessibilitySvc.instance
        val ctx = svc?.applicationContext
        if (svc == null || ctx == null) {
            Log.w(TAG, "No service instance; stop.")
            return
        }

        // Ghi log kích thước để đối chiếu mapping
        run {
            val (cw, ch) = ScreenCapture.frameSize()
            val info = try { AccessibilitySvc.instance?.displayInfo() } catch (_: Throwable) { null }
            val (winW, winH) = AccessibilitySvc.instance?.displaySize() ?: (0 to 0)
            if (info != null) {
                val gw = (info.w - info.insetLeft - info.insetRight).coerceAtLeast(1)
                val gh = (info.h - info.insetTop - info.insetBottom).coerceAtLeast(1)
                Log.i(
                    TAG, "sizes: capture=${cw}x${ch}, physical=${info.w}x${info.h}, " +
                            "window=${winW}x${winH}, insets L:${info.insetLeft} T:${info.insetTop} " +
                            "R:${info.insetRight} B:${info.insetBottom}, gestureArea=${gw}x${gh}"
                )
            } else {
                Log.i(TAG, "sizes: capture=${cw}x${ch}, physical=<null>, window=${winW}x${winH}")
            }
        }

        var lastPoint: PointF? = null

        steps.forEachIndexed { idx, step ->
            if (stopFlag) return
            currentStep = idx

            when (step) {
                is WaitImage -> {
                    val p = getPointByTemplate(ctx, step.id, step.minScore, step.timeoutMs)
                    if (p == null) {
                        Log.w(TAG, "WaitImage timeout ${step.id}")
                    } else {
                        lastPoint = p
                        Log.i(TAG, "WaitImage ${step.id} -> (${p.x.toInt()},${p.y.toInt()})")
                        DebugStore.record(step.id, p.x.toInt(), p.y.toInt(), step.minScore)
                    }
                }

                is FindImage -> {
                    val p = getPointByTemplate(ctx, step.id, step.minScore, step.timeoutMs)
                    if (p == null) {
                        Log.w(TAG, "FindImage timeout ${step.id}")
                    } else {
                        lastPoint = p
                        Log.i(TAG, "FindImage ${step.id} -> (${p.x.toInt()},${p.y.toInt()})")
                        DebugStore.record(step.id, p.x.toInt(), p.y.toInt(), step.minScore)
                    }
                }

                is TapImage -> {
                    val p = getPointByTemplate(ctx, step.id, step.minScore, /*timeout*/1200)
                    if (p == null) {
                        Log.w(TAG, "TapImage not found ${step.id}")
                    } else {
                        val cx = p.x + step.dx
                        val cy = p.y + step.dy
                        DebugStore.record(step.id, cx.toInt(), cy.toInt(), step.minScore)

                        val mapped = mapCapToDisp(PointF(cx, cy))
                        val scheduled = sendTap(mapped.x, mapped.y, 100)
                        lastPoint = mapped
                        Log.i(
                            TAG,
                            "TapImage ${step.id} cap=(${cx.toInt()},${cy.toInt()}) -> disp=(${mapped.x.toInt()},${mapped.y.toInt()}), scheduled=$scheduled"
                        )
                    }
                }

                is SwipeImage -> {
                    val p = getPointByTemplate(ctx, step.id, step.minScore, /*timeout*/1200)
                    if (p == null) {
                        Log.w(TAG, "SwipeImage not found ${step.id}")
                    } else {
                        val sxCap = p.x
                        val syCap = p.y
                        val exCap = p.x + step.dx
                        val eyCap = p.y + step.dy

                        DebugStore.record(step.id, sxCap.toInt(), syCap.toInt(), step.minScore)

                        val start = mapCapToDisp(PointF(sxCap, syCap))
                        val end   = mapCapToDisp(PointF(exCap, eyCap))
                        val scheduled = sendSwipe(start.x, start.y, end.x, end.y, step.durMs)
                        lastPoint = end
                        Log.i(
                            TAG,
                            "SwipeImage ${step.id} cap=(${sxCap.toInt()},${syCap.toInt()}) -> (${exCap.toInt()},${eyCap.toInt()}); " +
                                    "disp=(${start.x.toInt()},${start.y.toInt()}) -> (${end.x.toInt()},${end.y.toInt()}), scheduled=$scheduled"
                        )
                    }
                }

                is Tap -> {
                    val scheduled = sendTap(step.x, step.y, step.durMs)
                    lastPoint = PointF(step.x, step.y)
                    Log.i(TAG, "Tap (${step.x.toInt()},${step.y.toInt()}) scheduled=$scheduled")
                }

                is Swipe -> {
                    val scheduled = sendSwipe(step.x, step.y, step.x2, step.y2, step.durMs)
                    lastPoint = PointF(step.x2, step.y2)
                    Log.i(TAG, "Swipe ${step.x.toInt()},${step.y.toInt()} -> ${step.x2.toInt()},${step.y2.toInt()} scheduled=$scheduled")
                }

                Back   -> AccessibilitySvc.instance?.back()
                Home   -> AccessibilitySvc.instance?.home()
                Recent -> AccessibilitySvc.instance?.recent()
            }
        }
    }

    /* -------- helpers -------- */

    /** Tìm toạ độ (CAPTURE space) theo template. */
    private fun getPointByTemplate(
        ctx: Context,
        templateId: String,
        minScore: Int,
        timeoutMs: Long
    ): PointF? {
        if (!ScreenCapture.isReady()) {
            Log.w(TAG, "Screen bitmap unavailable; capture not ready?")
            return null
        }
        val bmp = ImageStore.loadBitmap(ctx, templateId) ?: run {
            Log.w(TAG, "template $templateId not found")
            return null
        }

        return try {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            var p: PointF? = null
            while (!stopFlag && SystemClock.uptimeMillis() < deadline) {
                p = ImageMatcher.findOnScreen(template = bmp, minScore = minScore) // CAPTURE space
                if (p != null) break
                try { Thread.sleep(80) } catch (_: InterruptedException) { break }
            }
            p
        } finally { try { bmp.recycle() } catch (_: Throwable) {} }
    }

    /** So sánh xấp xỉ vài px để tránh lệch do làm tròn/driver. */
    private fun approxEq(a: Int, b: Int, tol: Int = 4): Boolean = abs(a - b) <= tol

    private fun clamp(v: Float, lo: Float, hi: Float): Float = min(hi, max(lo, v))

    /**
     * Map CAPTURE-space -> DISPLAY/gesture space theo:
     * - contentRect() trong capture (nếu có);
     * - physical display size + insets (đã là physical).
     * KHÔNG dùng window size.
     */
    private fun mapCapToDisp(p: PointF): PointF {
        val (capW, capH) = ScreenCapture.frameSize()
        val info = try { AccessibilitySvc.instance?.displayInfo() } catch (_: Throwable) { null }
        if (info == null || capW <= 0 || capH <= 0) return p

        val l = info.insetLeft
        val t = info.insetTop
        val r = info.insetRight
        val b = info.insetBottom
        val effW = max(1, info.w - l - r) // gesture area width (PHYSICAL)
        val effH = max(1, info.h - t - b) // gesture area height (PHYSICAL)

        val content = ScreenCapture.contentRect() // trong CAPTURE space
        val cW = max(1, content.width())
        val cH = max(1, content.height())

        // Nếu capture == physical (1:1, không insets) -> đường tắt
        if (approxEq(capW, info.w) && approxEq(capH, info.h) && l == 0 && t == 0 && r == 0 && b == 0) {
            Log.d(TAG, "map: DIRECT 1:1 cap=(${capW}x${capH}) == physical=(${info.w}x${info.h}) -> (${p.x},${p.y})")
            return PointF(p.x, p.y)
        }

        // Chuẩn hoá toạ độ theo contentRect (CAPTURE → content)
        val nx = (p.x - content.left).coerceIn(0f, (cW - 1).toFloat())
        val ny = (p.y - content.top ).coerceIn(0f, (cH - 1).toFloat())

        // Scale content → gesture-area (PHYSICAL)
        val sx = effW.toFloat() / cW.toFloat()
        val sy = effH.toFloat() / cH.toFloat()

        val gx = l + nx * sx
        val gy = t + ny * sy

        val cgx = clamp(gx, 0f, (info.w - 1).toFloat())
        val cgy = clamp(gy, 0f, (info.h - 1).toFloat())

        Log.d(
            TAG,
            "map: CONTENT_SCALE cap=(${capW}x${capH}) content=$content -> phys=(${info.w}x${info.h}), " +
                    "insets L:$l T:$t R:$r B:$b, p=(${p.x},${p.y}) => ($cgx,$cgy)"
        )
        return PointF(cgx, cgy)
    }

    /** Gọi tap service, tương thích cả kiểu trả về Boolean (mới) lẫn Unit (cũ). */
    private fun sendTap(x: Float, y: Float, durMs: Long): Boolean {
        val svc = AccessibilitySvc.instance ?: return false
        return try {
            val m = AccessibilitySvc::class.java.getMethod(
                "tap",
                Float::class.java,
                Float::class.java,
                Long::class.java
            )
            val r = m.invoke(svc, x, y, durMs)
            (r as? Boolean) ?: true
        } catch (_: NoSuchMethodException) {
            return try {
                svc.tap(x, y, durMs)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "tap invoke failed", t); false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "tap invoke failed", t); false
        }
    }

    /** Gọi swipe service, tương thích cả kiểu trả về Boolean (mới) lẫn Unit (cũ). */
    private fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long): Boolean {
        val svc = AccessibilitySvc.instance ?: return false
        return try {
            val m = AccessibilitySvc::class.java.getMethod(
                "swipe",
                Float::class.java,
                Float::class.java,
                Float::class.java,
                Float::class.java,
                Long::class.java
            )
            val r = m.invoke(svc, x1, y1, x2, y2, durMs)
            (r as? Boolean) ?: true
        } catch (_: NoSuchMethodException) {
            return try {
                svc.swipe(x1, y1, x2, y2, durMs)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "swipe invoke failed", t); false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "swipe invoke failed", t); false
        }
    }
}
