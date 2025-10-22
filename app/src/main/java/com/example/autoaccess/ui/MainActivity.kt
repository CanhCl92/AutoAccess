package com.example.autoaccess.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.autoaccess.databinding.ActivityMainBinding
import com.example.autoaccess.svc.HttpServer   // đổi import cho đúng chỗ lớp của bạn
// import com.example.autoaccess.svc.GestureService ... nếu cần

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start HTTP server
        binding.btnStart.setOnClickListener {
            Log.d("AutoAccess", "Start HTTP server")
            // Nếu HttpServer là object utils:
            // HttpServer.start(applicationContext)
            // Nếu là Service:
            // startService(Intent(this, HttpServer::class.java).setAction("start"))
        }

        // Stop HTTP server
        binding.btnStop.setOnClickListener {
            Log.d("AutoAccess", "Stop HTTP server")
            // HttpServer.stop()
            // hoặc stopService(Intent(this, HttpServer::class.java))
        }

        // Mở Accessibility Settings
        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Xin quyền capture màn hình
        binding.btnCapture.setOnClickListener {
            startActivity(Intent(this, CapturePermissionActivity::class.java))
        }
    }
}