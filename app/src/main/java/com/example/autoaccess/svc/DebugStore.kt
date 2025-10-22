package com.example.autoaccess.svc

import android.graphics.*
import com.example.autoaccess.cap.ScreenCapture
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

object DebugStore {

    data class Snapshot(
        val id: String,
        val x: Int,
        val y: Int,
        val score: Int,
        val ts: Long
    )

    private val lastSnap = AtomicReference<Snapshot?>(null)
    private val lastOverlay = AtomicReference<ByteArray?>(null)
    private val lastCrop = AtomicReference<ByteArray?>(null)

    /** Ghi nhận match + dựng overlay/crop PNG. Tọa độ dùng KHÔNG GIAN CAPTURE. */
    fun record(templateId: String, x: Int, y: Int, score: Int) {
        val ts = System.currentTimeMillis()
        lastSnap.set(Snapshot(templateId, x, y, score, ts))

        // Lấy frame để vẽ overlay
        ScreenCapture.lastBitmap()?.let { frame ->
            try {
                // --- overlay ---
                val bmp = frame.copy(Bitmap.Config.ARGB_8888, true)
                val c = Canvas(bmp)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)

                // Khung đỏ
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f
                p.color = Color.RED
                c.drawRect((x - 30).toFloat(), (y - 30).toFloat(),
                    (x + 30).toFloat(), (y + 30).toFloat(), p)

                // Tâm vàng
                p.color = Color.YELLOW
                c.drawLine((x - 36).toFloat(), y.toFloat(), (x + 36).toFloat(), y.toFloat(), p)
                c.drawLine(x.toFloat(), (y - 36).toFloat(), x.toFloat(), (y + 36).toFloat(), p)

                // Nhãn
                val label = "$templateId  s=$score"
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 36f
                }
                val bg = Paint().apply { color = 0x7F000000 }
                val w = text.measureText(label)
                val r = RectF((x - 30).toFloat(), (y - 70).toFloat(),
                    (x - 30 + w + 24).toFloat(), (y - 30).toFloat())
                c.drawRoundRect(r, 12f, 12f, bg)
                c.drawText(label, r.left + 12f, r.bottom - 8f, text)

                lastOverlay.set(toPng(bmp))

                // --- crop ---
                val crop = safeCrop(frame, x - 50, y - 50, 100, 100)
                lastCrop.set(toPng(crop))
                crop.recycle()
                bmp.recycle()
            } finally {
                try { frame.recycle() } catch (_: Throwable) {}
            }
        }
    }

    private fun toPng(b: Bitmap): ByteArray =
        ByteArrayOutputStream().use { bos ->
            b.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bos.toByteArray()
        }

    private fun safeCrop(b: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap {
        val sx = x.coerceIn(0, b.width - 1)
        val sy = y.coerceIn(0, b.height - 1)
        val ex = (x + w).coerceIn(1, b.width)
        val ey = (y + h).coerceIn(1, b.height)
        return Bitmap.createBitmap(b, sx, sy, ex - sx, ey - sy)
    }

    /** Trả snapshot gần nhất. */
    fun snapshot(): Snapshot? = lastSnap.get()

    /** Trả PNG overlay (bytes) gần nhất. */
    fun overlayPng(): ByteArray? = lastOverlay.get()

    /** Trả PNG crop (bytes) gần nhất. */
    fun cropPng(): ByteArray? = lastCrop.get()
}