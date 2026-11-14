package com.stblab.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xstream.remote.RemoteV2Client
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var remote: RemoteV2Client
    private lateinit var serverStatus: TextView
    private lateinit var url: TextView
    private lateinit var remoteStatus: TextView
    private lateinit var hostInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var discoveryStatus: TextView
    private lateinit var discoveryResults: LinearLayout
    private lateinit var findButton: Button
    private lateinit var pairButton: Button
    private lateinit var reconnectButton: Button
    private lateinit var confirmButton: Button
    private lateinit var imeToggleButton: Button
    @Volatile private var activeDiscovery: Discovery? = null
    private val discoveryExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "StbLabDiscovery").apply { isDaemon = true } }
    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 1000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remote = RemoteV2Client.get(this)
        startHostService()
        render()
    }
    override fun onResume() { super.onResume(); handler.post(refreshTask) }
    override fun onPause() { handler.removeCallbacks(refreshTask); super.onPause() }

    private fun render() {
        window.decorView.setBackgroundColor(Color.rgb(11, 16, 32))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 28, 60, 28) }
        fun text(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply { text = value; textSize = size; setTextColor(color); setPadding(0, 5, 0, 5) }
        fun field(hintText: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
            hint = hintText; inputType = type; textSize = 18f; setSingleLine(true); setPadding(14, 9, 14, 9)
            id = View.generateViewId()
            isFocusable = true; isFocusableInTouchMode = true
            setTextColor(Color.WHITE); setHintTextColor(0xffaab4cf.toInt())
            setFieldFocusStyle(this, false)
            onFocusChangeListener = View.OnFocusChangeListener { view, focused -> setFieldFocusStyle(view as EditText, focused) }
        }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            isFocusable = true; isFocusableInTouchMode = true
            setPadding(18, 10, 18, 10)
            setButtonFocusStyle(this, false)
            onFocusChangeListener = View.OnFocusChangeListener { view, focused -> setButtonFocusStyle(view as Button, focused) }
            setOnClickListener { onClick() }
        }
        root.addView(text("StbLab", 32f))
        root.addView(text("BPL TV host · Airtel Remote v2 client", 16f, 0xffaab4cf.toInt()))
        root.addView(text("WEB SERVER", 14f, 0xff27d7a1.toInt()))
        val serverRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        serverStatus = text("● SERVER STARTING", 20f, 0xffffcc66.toInt())
        serverRow.addView(serverStatus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        serverRow.addView(button("STOP SERVER & EXIT") { stopServerAndExit() })
        root.addView(serverRow)
        url = text("", 22f, 0xff27d7a1.toInt()); root.addView(url)

        root.addView(text("AIRTEL BOX PAIRING", 14f, 0xff27d7a1.toInt()))
        hostInput = field("Airtel box IP address or host name")
        hostInput.setText(remote.savedHost()); root.addView(hostInput)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        findButton = button("FIND AIRTEL BOX") { discoverBoxes() }
        pairButton = button("PAIR WITH AIRTEL") { startPairing() }
        reconnectButton = button("RECONNECT") { remote.connect() }
        findButton.nextFocusRightId = pairButton.id
        pairButton.nextFocusLeftId = findButton.id
        pairButton.nextFocusRightId = reconnectButton.id
        reconnectButton.nextFocusLeftId = pairButton.id
        row.addView(findButton)
        row.addView(pairButton)
        row.addView(reconnectButton)
        root.addView(row)
        discoveryStatus = text("Scan to find Android TV Remote devices on this network.", 14f, 0xffaab4cf.toInt())
        root.addView(discoveryStatus)
        discoveryResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(discoveryResults)
        remoteStatus = text("Remote status: unpaired", 17f, 0xffffcc66.toInt()); root.addView(remoteStatus)
        imeToggleButton = button(imeToggleLabel()) {
            remote.setRemoteTextInputEnabled(!remote.remoteTextInputEnabled)
            imeToggleButton.text = imeToggleLabel()
        }
        root.addView(imeToggleButton)
        root.addView(text("Remote text input can conflict with third-party keyboard services such as tvQuickActions Gamepad. Leave disabled unless you use this app's text injection.", 13f, 0xffaab4cf.toInt()))
        codeInput = field("6-character code shown on Airtel TV", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        root.addView(codeInput)
        confirmButton = button("CONFIRM PAIRING CODE") { confirmPairing() }
        root.addView(confirmButton)
        hostInput.nextFocusDownId = findButton.id
        pairButton.nextFocusDownId = codeInput.id
        reconnectButton.nextFocusDownId = codeInput.id
        codeInput.nextFocusUpId = pairButton.id
        codeInput.nextFocusDownId = confirmButton.id
        root.addView(text("Start pairing here, then enter the code displayed by the Airtel box. Credentials are stored privately on this TV.", 14f, 0xffaab4cf.toInt()))
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        findButton.post { findButton.requestFocus() }
    }

    private fun discoverBoxes() {
        if (activeDiscovery != null) return
        discoveryResults.removeAllViews()
        discoveryStatus.text = "Searching with mDNS and local network scan…"
        val scan = Discovery(this) { boxes, done ->
            runOnUiThread {
                if (isFinishing || isDestroyed || activeDiscovery == null) return@runOnUiThread
                discoveryResults.removeAllViews()
                boxes.take(12).forEach { box ->
                    val resultButton = Button(this).apply {
                        text = "${box.name}  ·  ${box.ip}"
                        isAllCaps = false
                        isFocusable = true; isFocusableInTouchMode = true
                        contentDescription = "Use ${box.ip} for Airtel box pairing"
                        setButtonFocusStyle(this, false)
                        onFocusChangeListener = View.OnFocusChangeListener { view, focused -> setButtonFocusStyle(view as Button, focused) }
                        setOnClickListener {
                            hostInput.setText(box.ip)
                            hostInput.setSelection(hostInput.text.length)
                            discoveryStatus.text = "Selected ${box.name} at ${box.ip}. Choose PAIR WITH AIRTEL."
                            pairButton.requestFocus()
                        }
                    }
                    discoveryResults.addView(resultButton)
                    resultButton.nextFocusDownId = pairButton.id
                }
                discoveryStatus.text = when {
                    done && boxes.isEmpty() -> "No boxes found. Check the same Wi-Fi/LAN or enter the IP manually."
                    done -> "Found ${boxes.size} device${if (boxes.size == 1) "" else "s"}. Select one or enter an IP manually."
                    boxes.isNotEmpty() -> "Found ${boxes.size} device${if (boxes.size == 1) "" else "s"}… still searching."
                    else -> "Searching with mDNS and local network scan…"
                }
            }
        }
        activeDiscovery = scan
        discoveryExecutor.execute {
            scan.start()
            runOnUiThread { if (activeDiscovery === scan) activeDiscovery = null }
        }
    }

    override fun onDestroy() {
        activeDiscovery?.stop()
        activeDiscovery = null
        discoveryExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startHostService() {
        val intent = Intent(this, HostService::class.java).setAction(HostService.ACTION_START)
        androidx.core.content.ContextCompat.startForegroundService(this, intent)
    }

    private fun stopServerAndExit() {
        stopService(Intent(this, HostService::class.java))
        finishAndRemoveTask()
        handler.postDelayed({ Process.killProcess(Process.myPid()) }, 500)
    }

    private fun startPairing() {
        try {
            remote.startPair(hostInput.text.toString())
            codeInput.post {
                codeInput.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(codeInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        catch (t: Throwable) { remoteStatus.text = t.message ?: "Could not start pairing" }
    }
    private fun confirmPairing() {
        try { remote.finishPair(codeInput.text.toString()); codeInput.text.clear() }
        catch (t: Throwable) { remoteStatus.text = t.message ?: "Pairing failed" }
    }
    private fun refresh() {
        val ip = lanAddress()
        val running = HostService.running
        val serverError = HostService.error
        serverStatus.text = when {
            running -> "● SERVER LIVE · PORT ${HostService.PORT}"
            serverError != null -> "● SERVER OFFLINE · RETRYING"
            else -> "● SERVER STARTING"
        }
        serverStatus.setTextColor(when {
            running -> 0xff35e58f.toInt()
            serverError != null -> 0xffff6b6b.toInt()
            else -> 0xffffcc66.toInt()
        })
        url.text = if (running && ip != "No network") "http://$ip:${HostService.PORT}" else ""
        val state = remote.snapshot()
        remoteStatus.text = when {
            state.status == "waiting_code" -> state.error?.let { "Pairing code: $it" } ?: "Airtel box is showing a pairing code. Enter it below."
            state.connected -> "Connected to ${state.model ?: state.host}${state.currentApp?.let { " · $it" } ?: ""}"
            state.error != null -> "${state.status}: ${state.error}"
            else -> "Remote: ${state.status}${state.host.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}"
        }
        remoteStatus.setTextColor(if (state.connected) 0xff27d7a1.toInt() else 0xffffcc66.toInt())
        imeToggleButton.text = imeToggleLabel()
    }

    private fun imeToggleLabel(): String = if (remote.remoteTextInputEnabled)
        "REMOTE TEXT INPUT: ON"
    else "REMOTE TEXT INPUT: OFF · GAMEPAD SAFE"

    private fun setButtonFocusStyle(button: Button, focused: Boolean) {
        val density = resources.displayMetrics.density
        button.backgroundTintList = null
        button.background = GradientDrawable().apply {
            cornerRadius = 8f * density
            setColor(if (focused) 0xffffc928.toInt() else 0xff1a2941.toInt())
            setStroke(((if (focused) 4 else 1) * density).toInt(), if (focused) Color.WHITE else 0xff7185a3.toInt())
        }
        button.setTextColor(if (focused) 0xff101827.toInt() else Color.WHITE)
        button.elevation = (if (focused) 14f else 2f) * density
        button.scaleX = if (focused) 1.04f else 1f
        button.scaleY = if (focused) 1.04f else 1f
    }

    private fun setFieldFocusStyle(field: EditText, focused: Boolean) {
        val density = resources.displayMetrics.density
        field.background = GradientDrawable().apply {
            cornerRadius = 6f * density
            setColor(if (focused) 0xff283a54.toInt() else 0xff1a2540.toInt())
            setStroke(((if (focused) 4 else 2) * density).toInt(), if (focused) 0xffffc928.toInt() else 0xff7084a2.toInt())
        }
        field.setTextColor(if (focused) 0xfffff2bd.toInt() else Color.WHITE)
    }
}
