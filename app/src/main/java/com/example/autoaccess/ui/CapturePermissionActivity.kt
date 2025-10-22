package com.example.autoaccess.ui

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.content.ContextCompat
import com.example.autoaccess.cap.ScreenCapture
import com.example.autoaccess.cap.ScreenCaptureService

class CapturePermissionActivity : ComponentActivity() {

    companion object { private const val TAG = "AutoAccess" }

    private lateinit var launcher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        launcher = registerForActivityResult(StartActivityForResult()) { res ->
            val ok = res.resultCode == RESULT_OK && res.data != null
            Log.i(TAG, "CapturePermissionActivity result ok=$ok")
            if (ok) {
                // Start FGS (phase 1). Service sẽ tự nâng cấp type khi chạy.
                val svc = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, res.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, res.data)
                }
                ContextCompat.startForegroundService(this, svc)

                // Fallback: kích hoạt trực tiếp để /capture trả về ready ngay
                try {
                    ScreenCapture.start(application, res.resultCode, res.data!!)
                    Log.i(TAG, "ScreenCapture started directly (fallback)")
                } catch (t: Throwable) {
                    Log.w(TAG, "Fallback start failed: ${t.message}")
                }
            } else {
                Log.w(TAG, "User denied MediaProjection")
            }
            finish()
        }

        launcher.launch(mpm.createScreenCaptureIntent())
    }
}