package com.example.autoaccess.svc

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.example.autoaccess.cap.ScreenCapture

/**
 * Tìm template trên màn hình hiện tại (poll đến khi có frame hoặc hết timeout).
 * Trả về tâm điểm khớp (PointF) hoặc null nếu không thấy.
 */
object ScreenFind {

    /**
     * @param template  ảnh mẫu cần tìm
     * @param minScore  ngưỡng điểm khớp (0..1000), mặc định 800
     * @param timeoutMs tối đa chờ frame màn hình (ms)
     */
    fun findOnScreen(
        template: Bitmap,
        minScore: Int = 800,
        timeoutMs: Long = 1000L
    ): PointF? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val screen = ScreenCapture.lastBitmap()
            if (screen != null) {
                try {
                    // Dùng bộ so khớp trung tâm của dự án
                    val p: PointF? = ImageMatcher.findOnScreen(screen, minScore)
                    if (p != null) return p
                } catch (t: Throwable) {
                    Log.w("AutoAccess", "findOnScreen failed", t)
                } finally {
                    screen.recycle()
                }
            } else {
                // Chưa có frame -> đợi một chút rồi thử lại
                Thread.sleep(50)
            }
        }
        return null
    }
}