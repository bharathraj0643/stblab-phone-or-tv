package com.stblab.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.xstream.remote.RemoteV2Client

class MainActivity : Activity() {
    private lateinit var remote: RemoteV2Client
    private lateinit var serverStatus: TextView
    private lateinit var url: TextView
    private lateinit var pin: TextView
    private lateinit var remoteStatus: TextView
    private lateinit var hostInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var serverButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 1000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remote = RemoteV2Client.get(this)
        render()
    }
    override fun onResume() { super.onResume(); handler.post(refreshTask) }
    override fun onPause() { handler.removeCallbacks(refreshTask); super.onPause() }

    private fun render() {
        window.decorView.setBackgroundColor(Color.rgb(11, 16, 32))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(60, 28, 60, 28) }
        fun text(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply { text = value; textSize = size; setTextColor(color); setPadding(0, 5, 0, 5) }
        fun field(hintText: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
            hint = hintText; inputType = type; textSize = 18f; setSingleLine(true); setPadding(14, 9, 14, 9)
            setTextColor(Color.WHITE); setHintTextColor(0xffaab4cf.toInt()); setBackgroundColor(0xff1a2540.toInt())
        }
        root.addView(text("StbLab", 32f))
        root.addView(text("BPL TV host · Airtel Remote v2 client", 16f, 0xffaab4cf.toInt()))
        root.addView(text("WEB SERVER", 14f, 0xff27d7a1.toInt()))
        serverStatus = text("Server stopped", 18f, 0xffffcc66.toInt()); root.addView(serverStatus)
        url = text("", 22f, 0xff27d7a1.toInt()); root.addView(url)
        pin = text("", 18f); root.addView(pin)
        serverButton = Button(this).apply { text = "START SERVER"; setOnClickListener { toggleServer() } }; root.addView(serverButton)

        root.addView(text("AIRTEL BOX PAIRING", 14f, 0xff27d7a1.toInt()))
        hostInput = field("Airtel box IP address or host name")
        hostInput.setText(remote.savedHost()); root.addView(hostInput)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = "PAIR WITH AIRTEL"; setOnClickListener { startPairing() } })
        row.addView(Button(this).apply { text = "RECONNECT"; setOnClickListener { remote.connect() } })
        root.addView(row)
        remoteStatus = text("Remote status: unpaired", 17f, 0xffffcc66.toInt()); root.addView(remoteStatus)
        codeInput = field("6-character code shown on Airtel TV", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        root.addView(codeInput)
        root.addView(Button(this).apply { text = "CONFIRM PAIRING CODE"; setOnClickListener { confirmPairing() } })
        root.addView(text("Start pairing here, then enter the code displayed by the Airtel box. Credentials are stored privately on this TV.", 14f, 0xffaab4cf.toInt()))
        setContentView(root)
    }

    private fun toggleServer() {
        val action = if (HostService.running) HostService.ACTION_STOP else HostService.ACTION_START
        val intent = Intent(this, HostService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }
    private fun startPairing() {
        try { remote.startPair(hostInput.text.toString()) }
        catch (t: Throwable) { remoteStatus.text = t.message ?: "Could not start pairing" }
    }
    private fun confirmPairing() {
        try { remote.finishPair(codeInput.text.toString()); codeInput.text.clear() }
        catch (t: Throwable) { remoteStatus.text = t.message ?: "Pairing failed" }
    }
    private fun refresh() {
        val ip = lanAddress()
        val running = HostService.running
        serverStatus.text = HostService.error?.let { "Server error: $it" } ?: if (running) "Server running" else "Server stopped"
        url.text = if (running && ip != "No network") "http://$ip:${HostService.PORT}" else ""
        pin.text = if (running) "Web access PIN: ${getSharedPreferences("stblab_host", MODE_PRIVATE).getString("access_pin", "------")}" else ""
        serverButton.text = if (running) "STOP SERVER" else "START SERVER"

        val state = remote.snapshot()
        remoteStatus.text = when {
            state.status == "waiting_code" -> state.error?.let { "Pairing code: $it" } ?: "Airtel box is showing a pairing code. Enter it below."
            state.connected -> "Connected to ${state.model ?: state.host}${state.currentApp?.let { " · $it" } ?: ""}"
            state.error != null -> "${state.status}: ${state.error}"
            else -> "Remote: ${state.status}${state.host.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}"
        }
        remoteStatus.setTextColor(if (state.connected) 0xff27d7a1.toInt() else 0xffffcc66.toInt())
    }
}
