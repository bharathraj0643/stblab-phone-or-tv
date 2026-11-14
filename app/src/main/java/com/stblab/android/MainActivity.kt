package com.stblab.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var url: TextView
    private lateinit var pin: TextView
    private lateinit var startButton: Button
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onResume() { super.onResume(); refresh() }
    private fun render() {
        window.decorView.setBackgroundColor(Color.rgb(11, 16, 32))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(64, 40, 64, 40) }
        fun text(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply { text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER; setPadding(0, 8, 0, 8) }
        root.addView(text("StbLab", 34f))
        root.addView(text("Host the channel picker on this TV", 18f, 0xffaab4cf.toInt()))
        status = text("Server stopped", 20f, 0xffffcc66.toInt()); root.addView(status)
        url = text("", 24f, 0xff27d7a1.toInt()); root.addView(url)
        pin = text("", 18f); root.addView(pin)
        startButton = Button(this).apply { text = "START SERVER"; isFocusable = true; setOnClickListener { toggle() } }; root.addView(startButton)
        root.addView(text("Open the URL on the Airtel box browser. Enter this PIN there before changing channels.", 15f, 0xffaab4cf.toInt()))
        root.addView(text("Remote pairing is not configured yet.", 16f, 0xffffcc66.toInt()))
        setContentView(root)
    }
    private fun toggle() {
        val action = if (HostService.running) HostService.ACTION_STOP else HostService.ACTION_START
        startService(Intent(this, HostService::class.java).setAction(action))
        window.decorView.postDelayed({ refresh() }, 1000)
    }
    private fun refresh() {
        val ip = lanAddress()
        val isRunning = HostService.running
        status.text = HostService.error?.let { "Server error: $it" } ?: if (isRunning) "Server running" else "Server stopped"
        url.text = if (isRunning && ip != "No network") "http://$ip:${HostService.PORT}" else ""
        pin.text = if (isRunning) "Access PIN: ${getSharedPreferences("stblab_host", MODE_PRIVATE).getString("access_pin", "------")}" else ""
        startButton.text = if (isRunning) "STOP SERVER" else "START SERVER"
    }
}
