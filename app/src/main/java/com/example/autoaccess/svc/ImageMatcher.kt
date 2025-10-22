package com.example.autoaccess.svc

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.example.autoaccess.cap.ScreenCapture
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ImageMatcher tối ưu:
 * - Đọc pixel 1 lần bằng getPixels() -> IntArray
 * - Tiền xử lý template thành kênh độ sáng (luminance) + mask alpha
 * - Hỗ trợ ROI, sampling step theo kích thước template, early-exit
 * - Bỏ qua các pixel template có alpha thấp (mặc định < 128)
 * Điểm 0..1000 (1000 = khớp hoàn hảo theo SAD trên kênh luminance Y).
 */
object ImageMatcher {

    private const val TAG = "AutoAccess"
    private const val ALPHA_THRESHOLD = 128

    // Cache template đã tiền xử lý (giải phóng tự động khi GC)
    private val tplCache = WeakHashMap<Bitmap, TplData>()

    fun findOnScreen(template: Bitmap, minScore: Int, roi: Rect? = null): PointF? {
        val screen = ScreenCapture.lastBitmap() ?: return null
        return try {
            val res = match(screen, template, minScore.coerceIn(0, 1000), roi)
            res?.let { PointF(it.x.toFloat(), it.y.toFloat()) }
        } finally {
            try { screen.recycle() } catch (_: Throwable) {}
        }
    }

    // ----------------- nội bộ -----------------

    private data class TplData(
        val w: Int,
        val h: Int,
        val luma: IntArray,   // Y 0..255
        val mask: ByteArray   // 1 = dùng, 0 = bỏ (alpha thấp)
    )

    private data class Res(val x: Int, val y: Int, val score: Int)

    private fun match(screen: Bitmap, template: Bitmap, minScore: Int, roi: Rect?): Res? {
        val sW = screen.width
        val sH = screen.height
        val tW = template.width
        val tH = template.height
        if (tW <= 0 || tH <= 0 || tW > sW || tH > sH) return null

        // Vùng tìm kiếm (tọa độ gốc trái-trên, đảm bảo đủ chỗ cho template)
        val left   = max(0, roi?.left ?: 0)
        val top    = max(0, roi?.top  ?: 0)
        val right  = min(sW - tW, (roi?.right  ?: sW) - tW)
        val bottom = min(sH - tH, (roi?.bottom ?: sH) - tH)
        if (left > right || top > bottom) return null

        // Lấy buffer luminance cho màn hình
        val scrLuma = toLumaBuffer(screen)

        // Lấy/cấp cache cho template
        val tpl = tplCache[template] ?: preprocessTemplate(template).also { tplCache[template] = it }

        // Chọn bước sample theo diện tích template (mẫu nhỏ -> bước nhỏ để chính xác)
        val area = tW * tH
        var step = when {
            area <= 60 * 60   -> 1
            area <= 120 * 120 -> 2
            area <= 200 * 200 -> 3
            area <= 300 * 300 -> 4
            else              -> 6
        }

        // Nếu người dùng đặt minScore rất cao cho icon nhỏ, ép step=1 để tránh false positive
        if (minScore >= 850 && area <= 100 * 100) step = 1

        // Số mẫu thực sự dùng (bỏ pixel alpha thấp)
        val sampleDen = countEffectiveSamples(tpl, step)
        if (sampleDen <= 0) return null
        val maxTotal = 255 * sampleDen

        var bestScore = -1
        var bestX = 0
        var bestY = 0

        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                var accum = 0
                var aborted = false

                var ty = 0
                while (ty < tH) {
                    val sy = y + ty
                    var tx = 0
                    while (tx < tW) {
                        val m = tpl.mask[ty * tW + tx].toInt()
                        if (m == 1) {
                            val sx = x + tx
                            val sIdx = sy * sW + sx
                            val tIdx = ty * tW + tx
                            val diff = abs(scrLuma[sIdx] - tpl.luma[tIdx])
                            accum += diff

                            // Early-exit: điểm tối đa còn có thể đạt nếu phần còn lại khớp hoàn hảo
                            val theoreticalMax = 1000 - (accum * 1000 / maxTotal)
                            if (theoreticalMax < minScore) {
                                aborted = true
                                break
                            }
                        }
                        tx += step
                    }
                    if (aborted) break
                    ty += step
                }

                if (!aborted) {
                    val score = 1000 - (accum * 1000 / maxTotal)
                    if (score > bestScore) {
                        bestScore = score
                        bestX = x + tW / 2
                        bestY = y + tH / 2
                    }
                }
                x += step
            }
            y += step
        }

        return if (bestScore >= minScore) {
            // Log nhẹ để debug khi cần
            try {
                Log.d(TAG, "match: bestScore=$bestScore at ($bestX,$bestY) t=${tW}x${tH} step=$step roi=[$left,$top,$right,$bottom]")
            } catch (_: Throwable) {}
            Res(bestX, bestY, bestScore)
        } else null
    }

    /** Đếm số sample sẽ thực sự dùng (mask==1) theo step để tính maxTotal nhất quán. */
    private fun countEffectiveSamples(tpl: TplData, step: Int): Int {
        var den = 0
        var ty = 0
        while (ty < tpl.h) {
            var tx = 0
            while (tx < tpl.w) {
                if (tpl.mask[ty * tpl.w + tx].toInt() == 1) den++
                tx += step
            }
            ty += step
        }
        return den
    }

    /** Tiền xử lý template thành (luma, mask). */
    private fun preprocessTemplate(bmp: Bitmap): TplData {
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)

        val luma = IntArray(w * h)
        val mask = ByteArray(w * h)
        var i = 0
        while (i < argb.size) {
            val c = argb[i]
            val a = (c ushr 24) and 0xff
            if (a >= ALPHA_THRESHOLD) {
                // Y ≈ (0.299 R + 0.587 G + 0.114 B)
                val r = (c ushr 16) and 0xff
                val g = (c ushr 8)  and 0xff
                val b = (c)         and 0xff
                luma[i] = (299 * r + 587 * g + 114 * b + 500) / 1000
                mask[i] = 1
            } else {
                luma[i] = 0
                mask[i] = 0
            }
            i++
        }
        return TplData(w, h, luma, mask)
    }

    /** Lấy toàn bộ luminance của màn hình 1 lần (nhanh hơn getPixel lặp). */
    private fun toLumaBuffer(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val luma = IntArray(w * h)

        var i = 0
        while (i < argb.size) {
            val c = argb[i]
            // không cần alpha ở màn hình (thường opaque)
            val r = (c ushr 16) and 0xff
            val g = (c ushr 8)  and 0xff
            val b = (c)         and 0xff
            luma[i] = (299 * r + 587 * g + 114 * b + 500) / 1000
            i++
        }
        return luma
    }
}
