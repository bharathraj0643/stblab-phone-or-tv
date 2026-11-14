package com.stblab.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.NetworkInterface

class HostService : Service() {
    companion object {
        const val ACTION_START = "com.stblab.android.START"
        @Volatile var running = false
        @Volatile var error: String? = null
            private set
        const val PORT = 8080
        private const val CHANNEL = "stblab_server"
        private const val NOTIFICATION_ID = 42
    }
    @Volatile private var engine: ApplicationEngine? = null
    @Volatile private var startRequested = false
    private val serviceHandler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    override fun onCreate() { super.onCreate(); createChannel() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startRequested) return START_STICKY
        startRequested = true
        startForeground(NOTIFICATION_ID, notification("Starting StbLab server…"))
        startEngine()
        return START_STICKY
    }

    private fun startEngine() {
        if (!startRequested || engine != null) return
        Thread {
            try {
                val server = embeddedServer(CIO, host = "0.0.0.0", port = PORT) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                    installStbLabRoutes(this@HostService)
                }
                server.start(wait = false)
                if (!startRequested) {
                    server.stop(250, 1000)
                    return@Thread
                }
                engine = server
                running = true
                error = null
                retryCount = 0
                updateNotification("Server live · port $PORT")
            } catch (t: Throwable) {
                error = t.message ?: "Unable to start server"
                running = false
                engine = null
                scheduleRetry()
            }
        }.apply { name = "StbLabHttp"; isDaemon = true; start() }
    }

    private fun scheduleRetry() {
        if (!startRequested) return
        val delay = (1_000L shl retryCount.coerceAtMost(5)).coerceAtMost(30_000L)
        retryCount++
        updateNotification("Server offline · retrying in ${delay / 1000}s")
        serviceHandler.postDelayed({ if (startRequested && engine == null) startEngine() }, delay)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }
    override fun onDestroy() {
        running = false
        startRequested = false
        serviceHandler.removeCallbacksAndMessages(null)
        val old = engine; engine = null
        Thread { runCatching { old?.stop(250, 1000) } }.start()
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "StbLab web server", NotificationManager.IMPORTANCE_LOW).apply { description = "LAN picker server status" })
    }
    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("StbLab").setContentText(text).setContentIntent(pi).setOngoing(true).build()
    }
}

fun lanAddress(): String = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress ?: "No network"
}.getOrDefault("No network")
