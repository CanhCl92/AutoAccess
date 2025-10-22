package com.example.autoaccess.cap

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * MediaProjection capture với xử lý đổi xoay/kích thước an toàn.
 * Android 14+: KHÔNG recreate VirtualDisplay – chỉ resize + đổi surface.
 */
object ScreenCapture {

    private const val TAG = "AutoAccess"

    @Volatile private var ready = false

    private var projection: MediaProjection? = null
    private var callback: MediaProjection.Callback? = null
    private var vDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // display listener
    private var dispMgr: DisplayManager? = null
    private var dispListener: DisplayManager.DisplayListener? = null
    private lateinit var appRef: Application
    private val mainHandler = Handler(Looper.getMainLooper())

    // Kích thước frame capture hiện tại (cập nhật theo từng frame)
    @Volatile private var capW = 0
    @Volatile private var capH = 0
    fun frameSize(): Pair<Int, Int> = capW to capH

    // Content rect (vùng nội dung thật bên trong khung capture – bỏ viền/letterbox)
    @Volatile private var capContentRect: Rect = Rect(0, 0, 0, 0)
    @Volatile private var lastDetectTs: Long = 0L
    fun contentRect(): Rect {
        val (w, h) = frameSize()
        val r = capContentRect
        return if (r.width() <= 0 || r.height() <= 0) Rect(0, 0, w, h) else Rect(r)
    }

    fun isReady(): Boolean = ready

    fun start(app: Application, resultCode: Int, data: Intent) {
        stop()

        appRef = app
        val (w, h, dpi) = currentMetrics(app)
        capW = w; capH = h
        capContentRect = Rect(0, 0, w, h)
        lastDetectTs = 0L

        // Lấy projection
        val mpm = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("getMediaProjection returned null")
        projection = proj

        // Callback dừng
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection onStop()")
                stop()
            }
        }
        proj.registerCallback(cb, mainHandler)
        callback = cb

        // Tạo đường ống ban đầu (ImageReader + VirtualDisplay)
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, /*maxImages*/ 2)
        vDisplay = proj.createVirtualDisplay(
            "aa_cap", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            imageReader!!.surface, null, null
        )

        // Lắng nghe thay đổi hiển thị để resize
        dispMgr = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dispListener = object : DisplayManager.DisplayListener {
            private var lastDispatch = 0L
            override fun onDisplayChanged(displayId: Int) {
                val now = System.currentTimeMillis()
                if (now - lastDispatch < 200) return // debounce
                lastDispatch = now
                ensureSize()
            }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }.also { dispMgr?.registerDisplayListener(it, mainHandler) }

        ready = true
        Log.i(TAG, "ScreenCapture ready w=$w h=$h dpi=$dpi")
    }

    fun stop() {
        ready = false
        try { dispMgr?.unregisterDisplayListener(dispListener) } catch (_: Throwable) {}
        dispListener = null; dispMgr = null

        try { vDisplay?.release() } catch (_: Throwable) {}
        vDisplay = null
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
        try { callback?.let { projection?.unregisterCallback(it) } } catch (_: Throwable) {}
        callback = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null

        capContentRect = Rect(0, 0, 0, 0)
        lastDetectTs = 0L
    }

    /** Resize VirtualDisplay + swap ImageReader surface (không tạo VD mới). */
    private fun ensureSize() {
        val vd = vDisplay ?: return
        val (w, h, dpi) = currentMetrics(appRef)
        if (w == capW && h == capH) return

        try {
            Log.i(TAG, "display changed -> resize capture ${capW}x${capH} -> ${w}x${h}")

            // Tạo reader mới trước
            val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

            // Đổi surface rồi resize VD
            vd.setSurface(newReader.surface)
            vd.resize(w, h, dpi)

            // Đóng reader cũ
            val old = imageReader
            imageReader = newReader
            try { old?.close() } catch (_: Throwable) {}

            capW = w; capH = h
            // reset contentRect cho size mới (sẽ được detect lại)
            capContentRect = Rect(0, 0, w, h)
            lastDetectTs = 0L
        } catch (t: Throwable) {
            // Android 14: tránh recreate – chỉ log nếu resize thất bại
            Log.w(TAG, "ensureSize resize failed", t)
        }
    }

    /**
     * Lấy kích thước **physical** của display theo rotation hiện tại (1:1 với không gian cử chỉ),
     * cùng với densityDpi để tạo VirtualDisplay.
     */
    private fun currentMetrics(app: Application): Triple<Int, Int, Int> {
        val dm = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d: Display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = d.mode
        val rot = d.rotation
        val w = if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270)
            mode.physicalHeight else mode.physicalWidth
        val h = if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270)
            mode.physicalWidth else mode.physicalHeight
        val dpi = app.resources.displayMetrics.densityDpi
        return Triple(w, h, dpi)
    }

    /** Lấy bitmap frame gần nhất. */
    fun acquireBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        var img: Image? = null
        return try {
            img = reader.acquireLatestImage() ?: return null

            // Cập nhật size thực tế nếu khác (tuỳ rowStride/padding)
            if (capW != img.width || capH != img.height) {
                capW = img.width
                capH = img.height
                Log.d(TAG, "Capture frame size updated to ${capW}x${capH}")
                // reset content rect cho size mới
                capContentRect = Rect(0, 0, capW, capH)
                lastDetectTs = 0L
            }

            val plane = img.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * img.width

            val tmp = Bitmap.createBitmap(
                img.width + rowPadding / pixelStride,
                img.height,
                Bitmap.Config.ARGB_8888
            )
            tmp.copyPixelsFromBuffer(buffer)

            val out = Bitmap.createBitmap(tmp, 0, 0, img.width, img.height)
            tmp.recycle()

            // Cập nhật contentRect (throttle để tiết kiệm)
            maybeUpdateContentRect(out)

            out
        } catch (t: Throwable) {
            Log.w(TAG, "acquireBitmap failed", t)
            null
        } finally {
            try { img?.close() } catch (_: Throwable) {}
        }
    }

    // ---------- ContentRect detection ----------

    private fun maybeUpdateContentRect(bmp: Bitmap) {
        val now = System.currentTimeMillis()
        val need =
            capContentRect.width() <= 0 ||
                    capContentRect.height() <= 0 ||
                    now - lastDetectTs > 1500 // 1.5s/lần là đủ

        if (!need) return

        val r = detectContentRect(bmp)
        capContentRect = r
        lastDetectTs = now
        Log.d(TAG, "contentRect in capture: $r / frame=${bmp.width}x${bmp.height}")
    }

    /**
     * Phát hiện viền đen/đồng màu (letterbox) rất nhanh bằng cách đo phương sai độ sáng
     * theo các cột/hàng được lấy mẫu thưa.
     */
    private fun detectContentRect(bmp: Bitmap): Rect {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return Rect(0, 0, 0, 0)

        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        fun luma(c: Int): Int {
            val r = (c ushr 16) and 0xff
            val g = (c ushr 8) and 0xff
            val b = c and 0xff
            return (299 * r + 587 * g + 114 * b + 500) / 1000
        }

        fun colIsBorder(x: Int): Boolean {
            var sum = 0; var sum2 = 0; var n = 0
            val step = max(1, h / 96)
            var y = 0
            while (y < h) {
                val Y = luma(px[y * w + x])
                sum += Y; sum2 += Y * Y; n++
                y += step
            }
            val mean = sum.toFloat() / n
            val varv = sum2.toFloat() / n - mean * mean
            return varv < 12f // ngưỡng thực nghiệm
        }

        fun rowIsBorder(y: Int): Boolean {
            var sum = 0; var sum2 = 0; var n = 0
            val step = max(1, w / 96)
            var x = 0
            while (x < w) {
                val Y = luma(px[y * w + x])
                sum += Y; sum2 += Y * Y; n++
                x += step
            }
            val mean = sum.toFloat() / n
            val varv = sum2.toFloat() / n - mean * mean
            return varv < 12f
        }

        var left = 0
        while (left < w - 1 && colIsBorder(left)) left++
        var right = w - 1
        while (right > left && colIsBorder(right)) right--

        var top = 0
        while (top < h - 1 && rowIsBorder(top)) top++
        var bottom = h - 1
        while (bottom > top && rowIsBorder(bottom)) bottom--

        // đảm bảo không bị bóp quá đà
        val minW = (w * 0.6f).toInt()
        val minH = (h * 0.6f).toInt()
        if (right - left + 1 < minW) { left = 0; right = w - 1 }
        if (bottom - top + 1 < minH) { top = 0; bottom = h - 1 }

        return Rect(left, top, right + 1, bottom + 1) // right/bottom exclusive
    }

    // ---------- Convenience outputs ----------

    fun jpegBytes(quality: Int = 80): ByteArray? {
        val bmp = acquireBitmap() ?: return null
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    fun peekLatest(): Bitmap? {
        val bmp = acquireBitmap() ?: return null
        return try { bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false) }
        finally { try { bmp.recycle() } catch (_: Throwable) {} }
    }

    fun waitForFrame(timeoutMs: Long = 300): Bitmap? {
        val end = android.os.SystemClock.uptimeMillis() + timeoutMs
        var out: Bitmap? = null
        while (android.os.SystemClock.uptimeMillis() < end) {
            val b = acquireBitmap()
            if (b != null) {
                out = try { b.copy(b.config ?: Bitmap.Config.ARGB_8888, false) }
                finally { try { b.recycle() } catch (_: Throwable) {} }
                break
            }
            try { Thread.sleep(30) } catch (_: InterruptedException) { break }
        }
        return out
    }

    fun lastAsJpeg(quality: Int = 85): ByteArray? {
        val bmp = waitForFrame(300) ?: return null
        return try {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), bos)
            bos.toByteArray()
        } finally { try { bmp.recycle() } catch (_: Throwable) {} }
    }

    fun lastBitmap(): Bitmap? = waitForFrame(300)

    fun lastAsPng(): ByteArray? {
        val bmp = waitForFrame(300) ?: return null
        return try {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bos.toByteArray()
        } finally { try { bmp.recycle() } catch (_: Throwable) {} }
    }
}
