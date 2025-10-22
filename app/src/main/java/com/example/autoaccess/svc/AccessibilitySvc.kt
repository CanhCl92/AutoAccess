package com.example.autoaccess.svc

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.autoaccess.cap.ScreenCapture
import com.example.autoaccess.mapp.ContentRect
import com.example.autoaccess.mapp.CoordMapper
import com.example.autoaccess.mapp.DebugOverlay
import com.example.autoaccess.mapp.InsetsPx
import com.example.autoaccess.mapp.MappingDebugServer
import com.example.autoaccess.mapp.PhysSize
import com.example.autoaccess.util.HttpServer   // giữ đúng util server bạn đang dùng

/**
 * Accessibility service chính của AutoAccess.
 * ĐÃ tích hợp:
 *  - DebugOverlay (TYPE_ACCESSIBILITY_OVERLAY)
 *  - MappingDebugServer với các endpoint /map/...
 *  - spacesProvider() dùng physical + insets + contentRect
 */
class AccessibilitySvc : AccessibilityService() {

    // --- Các thành phần bạn đã có trong dự án ---
    // Lưu ý: nếu tên khác, đổi lại cho khớp:
    private lateinit var screenCapture: ScreenCapture
    private lateinit var debugHttp: HttpServer
    private lateinit var gestureSender: GestureSender   // helper dispatchGesture của bạn

    // Nếu bạn có DisplayInfo riêng chứa insets (như log bạn in ra), dùng lại ở đây:
    // Ví dụ:
    // data class DisplayInfo(val insetsL:Int, val insetsT:Int, val insetsR:Int, val insetsB:Int, ...)
    private lateinit var displayInfo: DisplayInfo

    // --- Phần thêm mới cho mapping/calib ---
    private lateinit var overlay: DebugOverlay
    private var mapServer: MappingDebugServer? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("AccessibilitySvc", "onServiceConnected()")

        // TODO: nếu bạn đã có luồng init screenCapture/debugHttp/gestureSender/displayInfo,
        // hãy gán vào ở đây hoặc đảm bảo các field trên được set trước khi gọi initMappingDebug().

        // Gắn overlay (TYPE_ACCESSIBILITY_OVERLAY)
        overlay = DebugOverlay.attach(this)

        // Khởi tạo server debug cho mapping nếu có debugHttp
        initMappingDebug()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Giữ nguyên logic hiện tại của bạn
    }

    override fun onInterrupt() {
        // Giữ nguyên
    }

    // ---------------- Mapping Debug ----------------

    /** Cấp dữ liệu không gian cho mapper: physical + insets + contentRect. */
    private fun spacesProvider(): CoordMapper.Spaces {
        val physW = currentPhysicalWidth()
        val physH = currentPhysicalHeight()

        val insets = readSystemInsetsPx() // Lấy từ displayInfo (ưu tiên), fallback 0
        val contentRect = readCaptureContentRect() // từ ScreenCapture

        return CoordMapper.Spaces(
            phys = PhysSize(physW, physH),
            insets = insets,
            content = ContentRect(contentRect)
        )
    }

    /** Khởi tạo các endpoint /map/... vào debug server sẵn có. */
    private fun initMappingDebug() {
        if (!::debugHttp.isInitialized || !::screenCapture.isInitialized || !::gestureSender.isInitialized) {
            Log.w("AccessibilitySvc", "MappingDebugServer không khởi tạo vì thiếu debugHttp / screenCapture / gestureSender")
            return
        }
        mapServer = MappingDebugServer(
            service = this,
            capture = screenCapture,
            spacesProvider = ::spacesProvider,
            gesture = gestureSender,
            http = debugHttp
        )
        Log.i("AccessibilitySvc", "MappingDebugServer ready: /map/params, /map/showCrosshair, /map/tap, /map/calib3, /map/captureAt")
    }

    // ---------------- Helpers ----------------

    private fun currentPhysicalWidth(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= 23) {
                display?.mode?.physicalWidth ?: resources.displayMetrics.widthPixels
            } else {
                resources.displayMetrics.widthPixels
            }
        } catch (_: Throwable) {
            resources.displayMetrics.widthPixels
        }
    }

    private fun currentPhysicalHeight(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= 23) {
                display?.mode?.physicalHeight ?: resources.displayMetrics.heightPixels
            } else {
                resources.displayMetrics.heightPixels
            }
        } catch (_: Throwable) {
            resources.displayMetrics.heightPixels
        }
    }

    private fun readSystemInsetsPx(): InsetsPx {
        return try {
            if (::displayInfo.isInitialized) {
                // Ưu tiên dùng đúng nguồn insets bạn đã log: “insets L:.. T:.. R:.. B:..”
                InsetsPx(
                    left = displayInfo.insetsL,
                    top = displayInfo.insetsT,
                    right = displayInfo.insetsR,
                    bottom = displayInfo.insetsB
                )
            } else {
                // Fallback nếu chưa có displayInfo
                InsetsPx(0, 0, 0, 0)
            }
        } catch (_: Throwable) {
            InsetsPx(0, 0, 0, 0)
        }
    }

    private fun readCaptureContentRect(): Rect {
        return try {
            // Nếu ScreenCapture của bạn có contentRect sẵn, dùng nó;
            // nếu không, mặc định full buffer capture.
            screenCapture.contentRect ?: Rect(0, 0, screenCapture.width, screenCapture.height)
        } catch (_: Throwable) {
            Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
    }
}

/** Placeholder cho GestureSender nếu file này không nhìn thấy import; bạn có thể xóa nếu đã có class thật trong dự án. */
interface GestureSender {
    fun tap(x: Float, y: Float)
}

/** Placeholder cho DisplayInfo nếu bạn đã có class tương tự; đổi cho khớp dự án hoặc xóa phần này. */
data class DisplayInfo(
    val insetsL: Int,
    val insetsT: Int,
    val insetsR: Int,
    val insetsB: Int
)
