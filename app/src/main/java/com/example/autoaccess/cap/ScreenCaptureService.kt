package com.example.autoaccess.cap

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground Service cho MediaProjection (2-phase):
 *  Phase 1: vào FGS KHÔNG kèm type (tránh SecurityException trên Android 14).
 *  Phase 2: khi ScreenCapture.start(...) OK thì "nâng cấp" type = MEDIA_PROJECTION.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "AutoAccess"

        const val ACTION_START = "sc_start"
        const val ACTION_STOP  = "sc_stop"

        const val EXTRA_RESULT_CODE = "resCode"
        const val EXTRA_RESULT_DATA = "resData"

        private const val CH_ID   = "scap"
        private const val NOTI_ID = 42
    }

    override fun onCreate() {
        super.onCreate()
        // Channel cho notification (O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CH_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    private fun buildNotification(): android.app.Notification =
        NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning) // thay icon app nếu muốn
            .setContentTitle("AutoAccess")
            .setContentText("Screen capture running")
            .setOngoing(true)
            // Android 14 gợi ý hiện ngay khi lên FGS
            .setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
            )
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val notif = buildNotification()

                // Phase 1: vào FGS *không kèm type*
                startForeground(NOTI_ID, notif)

                // Nếu đã sẵn sàng thì chỉ cần nâng cấp type (nếu được)
                if (ScreenCapture.isReady()) {
                    upgradeTypeIfPossible(notif)
                    return START_STICKY
                }

                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                try {
                    if (code == Activity.RESULT_OK && data != null) {
                        // Bắt đầu MediaProjection + VirtualDisplay.
                        // YÊU CẦU ANDROID 14+: callback phải được đăng ký trước khi tạo virtual display
                        // (logic đăng ký callback đã thực hiện trong ScreenCapture.start)
                        ScreenCapture.start(application as Application, code, data)
                        Log.i(TAG, "ScreenCapture started")

                        // Phase 2: nâng cấp type = MEDIA_PROJECTION (API 29+)
                        upgradeTypeIfPossible(notif)
                    } else {
                        Log.w(TAG, "MediaProjection result invalid (code=$code, data=${data != null})")
                        stopForeground(true)
                        stopSelf()
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Start capture failed", t)
                    stopForeground(true)
                    stopSelf()
                }
            }

            ACTION_STOP -> {
                try {
                    ScreenCapture.stop()
                    Log.i(TAG, "ScreenCapture stopped")
                } catch (_: Throwable) { /* no-op */ }
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun upgradeTypeIfPossible(notif: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(
                    NOTI_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
                Log.i(TAG, "FGS upgraded to type=mediaProjection")
            } catch (se: SecurityException) {
                Log.w(TAG, "Upgrade FGS type failed: ${se.message}")
            }
        }
    }

    override fun onDestroy() {
        // Dọn tài nguyên nếu service bị kill bất ngờ
        try { ScreenCapture.stop() } catch (_: Throwable) { }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}