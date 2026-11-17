package com.stblab.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.xstream.remote.RemoteV2Client
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireLocks()
        registerScreenReceiver()
        registerNetworkMonitor()
    }
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
        unregisterScreenReceiver()
        unregisterNetworkMonitor()
        releaseLocks()
        val old = engine; engine = null
        Thread { runCatching { old?.stop(250, 1000) } }.start()
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StbLab:HostServiceWakeLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "StbLab:HostServiceWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        wifiLock = null
    }

    private fun registerScreenReceiver() {
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                            // BPL TV woke up from sleep: aggressively ensure engine and remote connection are alive
                            acquireLocks()
                            if (engine == null && startRequested) {
                                startEngine()
                            }
                            serviceHandler.postDelayed({
                                RemoteV2Client.get(applicationContext).connect()
                            }, 1000)
                        }
                    }
                }
            }
            screenReceiver = receiver
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            androidx.core.content.ContextCompat.registerReceiver(this, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        screenReceiver = null
    }

    private fun registerNetworkMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Network restored or re-associated after sleep
                    if (engine == null && startRequested) {
                        serviceHandler.post { startEngine() }
                    }
                    serviceHandler.postDelayed({
                        RemoteV2Client.get(applicationContext).connect()
                    }, 1500)
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }

    private fun unregisterNetworkMonitor() {
        networkCallback?.let {
            runCatching {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            }
        }
        networkCallback = null
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
