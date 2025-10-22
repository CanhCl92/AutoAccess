// AutoAccess — Coord Mapping & Calibration (v1)
// Single-file drop-in to help fix coordinate drift when logical display < physical
// Modules in this file:
// 1) CoordSpaces + CoordMapper: deterministic mapping via physical size + insets + contentRect
// 2) DebugOverlay: lightweight crosshair overlay for visual verification
// 3) Calibrator: 2- or 3-point auto-calibration to compute affine from Gesture→Capture
// 4) DebugHttp: minimal handlers to drive overlay, tap tests, and calibration via your existing debug server
//
// Notes:
// - Capture space (CAP): pixel coords of VirtualDisplay/ScreenCapture bitmap
// - Physical space (PHYS): Display.mode.physicalWidth/Height, rotation applied
// - Gesture space (GEST): coordinates expected by AccessibilityService.dispatchGesture (after system insets)
// - ContentRect (CR): the active content rectangle inside CAP (letterbox/pillarbox); usually Rect(0,0,w,h) but not always
// - Insets: system bars etc. Use values from AccessibilityService / WindowInsets
//
// Mapping model (deterministic):
//   // remove insets from gesture
//   gx' = gx - insets.left; gy' = gy - insets.top
//   // scale into content rect size
//   sx = CR.width  / (PHYS.width  - insets.left - insets.right)
//   sy = CR.height / (PHYS.height - insets.top  - insets.bottom)
//   cx = CR.left + gx' * sx
//   cy = CR.top  + gy' * sy
// This assumes CAP is aligned with PHYS orientation. If rotation changes, update PHYS, CR accordingly.
// If a device applies logical scaling (e.g., 1280x720 window on 1920x1080 physical), this formula still holds
// so long as your insets + PHYS are taken from Display and not from the app window metrics.
//
// Auto-calibration (optional/fallback):
// - Draw 2–3 crosshairs in GEST coords (post-inset visual positions), capture the screen, locate crosshairs in CAP by color, 
//   then fit an affine transform CAP = A * [GEST;1]. This removes the need to reason about OEM scaling quirks.
//
// ---- Imports ----
package com.example.autoaccess.mapp

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.annotation.ColorInt
import com.example.autoaccess.cap.ScreenCapture
import com.example.autoaccess.svc.GestureSender // your existing helper to dispatch gestures
import com.example.autoaccess.util.HttpServer // your existing debug HTTP, replace with actual import
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---- 1) Coord spaces & deterministic mapper ----

data class InsetsPx(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val h get() = left + right
    val v get() = top + bottom
}

data class PhysSize(val width: Int, val height: Int)

data class ContentRect(val rect: Rect) {
    val left get() = rect.left
    val top get() = rect.top
    val right get() = rect.right
    val bottom get() = rect.bottom
    val width get() = rect.width()
    val height get() = rect.height()
}

/**
 * Deterministic mapping Gesture→Capture using physical, insets, and content rect.
 * Logs intermediate values for troubleshooting. Safe to call hot.
 */
object CoordMapper {
    private const val TAG = "CoordMapper"

    data class Spaces(
        val phys: PhysSize,
        val insets: InsetsPx,
        val content: ContentRect,
    ) {
        val gestureAreaW: Int get() = phys.width - insets.h
        val gestureAreaH: Int get() = phys.height - insets.v
    }

    data class MapParams(
        val scaleX: Float,
        val scaleY: Float,
        val offsetX: Float,
        val offsetY: Float,
    )

    fun computeParams(spaces: Spaces): MapParams {
        val sx = spaces.content.width.toFloat() / spaces.gestureAreaW.toFloat()
        val sy = spaces.content.height.toFloat() / spaces.gestureAreaH.toFloat()
        val ox = spaces.content.left.toFloat() - spaces.insets.left * sx
        val oy = spaces.content.top.toFloat() - spaces.insets.top  * sy
        return MapParams(sx, sy, ox, oy)
    }

    fun gestureToCapture(xGest: Float, yGest: Float, spaces: Spaces): PointF {
        val p = computeParams(spaces)
        val cx = p.offsetX + xGest * p.scaleX
        val cy = p.offsetY + yGest * p.scaleY
        if (BuildConfig.DEBUG) Log.d(TAG, "g→c x=$xGest y=$yGest | sx=${p.scaleX} sy=${p.scaleY} ox=${p.offsetX} oy=${p.offsetY} => cx=$cx cy=$cy")
        return PointF(cx, cy)
    }

    fun captureToGesture(xCap: Float, yCap: Float, spaces: Spaces): PointF {
        val p = computeParams(spaces)
        val gx = (xCap - p.offsetX) / p.scaleX
        val gy = (yCap - p.offsetY) / p.scaleY
        return PointF(gx, gy)
    }
}

// ---- 2) Debug overlay (crosshair) ----
@SuppressLint("ViewConstructor")
class DebugOverlay(
    ctx: Context,
) : android.view.View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    var crosshairs: List<Crosshair> = emptyList()
        set(value) { field = value; postInvalidateOnAnimation() }

    data class Crosshair(
        val center: PointF,
        val radius: Float = 16f,
        @ColorInt val color: Int = Color.MAGENTA,
        val label: String? = null,
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (ch in crosshairs) {
            paint.color = ch.color
            fill.color = ch.color
            canvas.drawCircle(ch.center.x, ch.center.y, ch.radius, paint)
            canvas.drawLine(ch.center.x - ch.radius, ch.center.y, ch.center.x + ch.radius, ch.center.y, paint)
            canvas.drawLine(ch.center.x, ch.center.y - ch.radius, ch.center.x, ch.center.y + ch.radius, paint)
            ch.label?.let {
                fill.textSize = 24f
                canvas.drawText(it, ch.center.x + ch.radius + 6f, ch.center.y - ch.radius - 6f, fill)
            }
        }
    }

    companion object {
        fun attach(service: AccessibilityService): DebugOverlay {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val overlay = DebugOverlay(service)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= 26)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(overlay, lp)
            return overlay
        }
    }
}

// ---- 3) Auto-calibrator ----
class Calibrator(
    private val service: AccessibilityService,
    private val capture: ScreenCapture,
    private val spacesProvider: () -> CoordMapper.Spaces,
    private val overlay: DebugOverlay,
) {
    data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float) {
        fun map(x: Float, y: Float): PointF = PointF(a * x + b * y + tx, c * x + d * y + ty)
        override fun toString(): String = "[[%.5f %.5f %.2f],[%.5f %.5f %.2f]]".format(a, b, tx, d, c, ty)
    }

    sealed interface Result {
        data class Ok(val affine: Affine, val rmse: Float): Result
        data class Err(val reason: String): Result
    }

    /** Show 3 crosshairs in GEST coords, capture, detect in CAP, fit affine. */
    fun run3pt(): Result {
        val sp = spacesProvider()
        val w = sp.phys.width - sp.insets.h
        val h = sp.phys.height - sp.insets.v

        val ptsGest = listOf(
            PointF(sp.insets.left + w * 0.2f, sp.insets.top + h * 0.2f),
            PointF(sp.insets.left + w * 0.8f, sp.insets.top + h * 0.25f),
            PointF(sp.insets.left + w * 0.3f, sp.insets.top + h * 0.75f),
        )
        overlay.crosshairs = ptsGest.mapIndexed { i, p -> DebugOverlay.Crosshair(p, label = "G${i+1}") }
        SystemClock.sleep(200)

        val bmp = capture.screenshot() ?: return Result.Err("no capture")
        val ptsCap = ptsGest.mapIndexed { i, g ->
            findCrosshair(bmp, expected = CoordMapper.gestureToCapture(g.x, g.y, sp))
                ?: return Result.Err("crosshair #${i+1} not found")
        }

        val aff = fitAffine3(ptsGest, ptsCap)
        val rmse = rms(ptsGest.zip(ptsCap)) { g, c -> aff.map(g.x, g.y) to c }
        return Result.Ok(aff, rmse)
    }

    private fun rms(pairs: List<Pair<PointF, PointF>>, map: (PointF, PointF) -> Pair<PointF, PointF>): Float {
        var acc = 0.0
        for (p in pairs) {
            val (m, c) = map(p.first, p.second)
            val dx = (m.x - c.x).toDouble()
            val dy = (m.y - c.y).toDouble()
            acc += dx*dx + dy*dy
        }
        return kotlin.math.sqrt(acc / pairs.size).toFloat()
    }

    /** Find the magenta crosshair around expected position with small search radius. */
    private fun findCrosshair(bmp: Bitmap, expected: PointF, radius: Int = 60): PointF? {
        val x0 = max(0, expected.x.toInt() - radius)
        val y0 = max(0, expected.y.toInt() - radius)
        val x1 = min(bmp.width - 1, expected.x.toInt() + radius)
        val y1 = min(bmp.height - 1, expected.y.toInt() + radius)
        var best: PointF? = null
        var bestScore = 0
        for (y in y0..y1) for (x in x0..x1) {
            val c = bmp.getPixel(x, y)
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            // loose magenta filter
            if (r > 200 && b > 200 && g < 80) {
                val score = r + b - g
                if (score > bestScore) { bestScore = score; best = PointF(x.toFloat(), y.toFloat()) }
            }
        }
        return best
    }

    /** Fit affine transform CAP = A*[GEST;1] using 3 non-collinear point pairs. */
    private fun fitAffine3(src: List<PointF>, dst: List<PointF>): Affine {
        require(src.size == 3 && dst.size == 3)
        val (x1,y1) = src[0]; val (u1,v1) = dst[0]
        val (x2,y2) = src[1]; val (u2,v2) = dst[1]
        val (x3,y3) = src[2]; val (u3,v3) = dst[2]
        // Solve linear system for a,b,tx and c,d,ty independently
        val (a,b,tx) = solve3(x1,y1,1f, u1, x2,y2,1f, u2, x3,y3,1f, u3)
        val (c,d,ty) = solve3(x1,y1,1f, v1, x2,y2,1f, v2, x3,y3,1f, v3)
        return Affine(a,b,c,d,tx,ty)
    }

    // Solve 3x3 system M * [p q r]^T = [t1 t2 t3]^T in closed form (Cramer's rule)
    private fun solve3(
        m11: Float, m12: Float, m13: Float, t1: Float,
        m21: Float, m22: Float, m23: Float, t2: Float,
        m31: Float, m32: Float, m33: Float, t3: Float,
    ): Triple<Float,Float,Float> {
        fun det(a: Float,b: Float,c: Float,d: Float,e: Float,f: Float,g: Float,h: Float,i: Float): Float =
            a*(e*i - f*h) - b*(d*i - f*g) + c*(d*h - e*g)
        val D = det(m11,m12,m13, m21,m22,m23, m31,m32,m33)
        val Dp = det(t1,m12,m13, t2,m22,m23, t3,m32,m33)
        val Dq = det(m11,t1,m13, m21,t2,m23, m31,t3,m33)
        val Dr = det(m11,m12,t1, m21,m22,t2, m31,m32,t3)
        return Triple(Dp/D, Dq/D, Dr/D)
    }
}

// ---- 4) Debug HTTP endpoints ----
class MappingDebugServer(
    private val service: AccessibilityService,
    private val capture: ScreenCapture,
    private val spacesProvider: () -> CoordMapper.Spaces,
    private val gesture: GestureSender,
    http: HttpServer
) {
    private val overlay = DebugOverlay.attach(service)
    private val calib = Calibrator(service, capture, spacesProvider, overlay)

    init {
        http.get("/map/params") { _ ->
            val sp = spacesProvider()
            val p = CoordMapper.computeParams(sp)
            """
            phys=${sp.phys.width}x${sp.phys.height} insets=[${sp.insets.left},${sp.insets.top},${sp.insets.right},${sp.insets.bottom}]
            content=${sp.content.left},${sp.content.top},${sp.content.width}x${sp.content.height}
            gestureArea=${sp.gestureAreaW}x${sp.gestureAreaH}
            scale=(${p.scaleX.format3()}, ${p.scaleY.format3()}) offset=(${p.offsetX.format1()}, ${p.offsetY.format1()})
            """.trimIndent()
        }

        http.get("/map/showCrosshair") { q ->
            val x = q.float("x")
            val y = q.float("y")
            overlay.crosshairs = listOf(DebugOverlay.Crosshair(PointF(x,y), label = "G"))
            "ok: crosshair at $x,$y"
        }

        http.get("/map/tap") { q ->
            val gx = q.float("x")
            val gy = q.float("y")
            val sp = spacesProvider()
            val c = CoordMapper.gestureToCapture(gx, gy, sp)
            gesture.tap(gx, gy)
            "tap gest=($gx,$gy) → cap=(${c.x.format1()},${c.y.format1()})"
        }

        http.get("/map/calib3") { _ ->
            when (val r = calib.run3pt()) {
                is Calibrator.Result.Ok -> {
                    overlay.crosshairs = emptyList()
                    "ok: affine=${r.affine} rmse=${r.rmse.format1()}"
                }
                is Calibrator.Result.Err -> "err: ${r.reason}"
            }
        }

        http.get("/map/captureAt") { q ->
            val gx = q.float("x")
            val gy = q.float("y")
            val sp = spacesProvider()
            val c = CoordMapper.gestureToCapture(gx, gy, sp)
            val bmp = capture.screenshot() ?: return@get "no capture"
            val crop = safeCrop(bmp, Rect((c.x-40).toInt(), (c.y-40).toInt(), (c.x+40).toInt(), (c.y+40).toInt()))
            val path = "/sdcard/Android/data/autoaccess/crops/c_${System.currentTimeMillis()}.png" // adjust path
            savePng(crop, path)
            "saved $path around cap=(${c.x.format1()},${c.y.format1()})"
        }
    }

    private fun savePng(bmp: Bitmap, path: String) {
        // TODO: implement with your file helper; omitted for brevity
    }

    private fun safeCrop(src: Bitmap, r: Rect): Bitmap {
        val rr = Rect(
            max(0, r.left),
            max(0, r.top),
            min(src.width, r.right),
            min(src.height, r.bottom)
        )
        return Bitmap.createBitmap(src, rr.left, rr.top, max(1, rr.width()), max(1, rr.height()))
    }
}

// ---- Helpers ----
private fun StringBuilder.appendKv(k: String, v: Any?) { append(k).append('=').append(v).append('\n') }
private fun Map<String,String>.float(key: String): Float = this[key]?.toFloatOrNull() ?: error("missing $key")
private fun Float.format1() = String.format("%.1f", this)
private fun Float.format3() = String.format("%.3f", this)

// ---- Instrumentation suggestion for the engine ----
// In your image step executor, add verbose logs around template matching and tap dispatch:
/*
val t0 = SystemClock.elapsedRealtime()
val bmp = screenCapture.screenshot()
Log.d("Engine", "snap ${bmp?.width}x${bmp?.height} in ${SystemClock.elapsedRealtime()-t0}ms")
val res = template.match(bmp)
Log.i("Engine", "match tpl='${template.name}' conf=${res?.score} at=${res?.point}")
if (res != null) {
    val sp = spacesProvider()
    val g = CoordMapper.captureToGesture(res.point.x.toFloat(), res.point.y.toFloat(), sp)
    Log.i("Engine", "tap g=(${g.x.format1()},${g.y.format1()}) from cap=(${res.point.x},${res.point.y})")
    gesture.tap(g.x, g.y)
} else {
    Log.w("Engine", "no match for ${template.name}")
}
*/
