package com.stblab.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
        const val ACTION_STOP = "com.stblab.android.STOP"
        @Volatile var running = false
        @Volatile var error: String? = null
            private set
        const val PORT = 8080
        private const val CHANNEL = "stblab_server"
        private const val NOTIFICATION_ID = 42
    }
    private var engine: ApplicationEngine? = null
    override fun onCreate() { super.onCreate(); createChannel() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, notification("Starting StbLab server…"))
        Thread {
            try {
                val server = embeddedServer(CIO, host = "0.0.0.0", port = PORT) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                    installStbLabRoutes(this@HostService)
                }
                engine = server
                server.start(wait = true)
            } catch (t: Throwable) { error = t.message ?: "Unable to start server"; running = false; stopSelf() }
        }.apply { name = "StbLabHttp"; isDaemon = true; start() }
        return START_STICKY
    }
    override fun onDestroy() {
        running = false
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
