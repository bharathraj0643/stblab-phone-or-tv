package com.xstream.remote

import android.content.Context
import android.util.Base64
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class RemoteSnapshot(
    val paired: Boolean,
    val connecting: Boolean,
    val connected: Boolean,
    val status: String,
    val error: String?,
    val isOn: Boolean?,
    val currentApp: String?,
    val volumeLevel: Int?,
    val volumeMax: Int?,
    val muted: Boolean?,
    val manufacturer: String?,
    val model: String?,
    val softwareVersion: String?,
    val host: String,
    val peerFingerprint: String?
)

/** Android TV Remote v2 pairing and control adapter. Credentials stay app-private. */
class RemoteV2Client private constructor(context: Context) {
    companion object {
        @Volatile private var instance: RemoteV2Client? = null
        fun get(context: Context): RemoteV2Client = instance ?: synchronized(this) {
            instance ?: RemoteV2Client(context.applicationContext).also { instance = it }
        }
        private const val PREFS = "stblab_remote_v2"
        private const val PORT_PAIR = 6467
        private const val PORT_REMOTE = 6466
        private val KEYS = listOf(
            "POWER", "TV_POWER", "HOME", "BACK", "MENU", "UP", "DOWN", "LEFT", "RIGHT", "CENTER", "OK",
            "VOLUME_UP", "VOLUME_DOWN", "MUTE", "PLAY_PAUSE", "PLAY", "PAUSE", "STOP", "NEXT", "PREVIOUS",
            "REWIND", "FAST_FORWARD", "TV", "GUIDE", "INFO", "SETTINGS", "ENTER", "DEL", "SEARCH", "ASSIST",
            "CHANNEL_UP", "CHANNEL_DOWN", "TV_INPUT", "CAPTIONS", "SLEEP", "WAKEUP", "VOICE_ASSIST", "APP_SWITCH",
            "RED", "GREEN", "YELLOW", "BLUE", * (0..9).map { "NUM_$it" }.toTypedArray()
        )
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val identityDir = File(appContext.filesDir, "remote_identity")
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "stblab-remote-v2").apply { isDaemon = true } }
    @Volatile private var pairing: PairingSession? = null
    @Volatile private var session: AurSession? = null
    @Volatile private var status = "unpaired"
    @Volatile private var error: String? = null
    @Volatile private var connected = false
    @Volatile private var isOn: Boolean? = null
    @Volatile private var currentApp: String? = null
    @Volatile private var volumeLevel: Int? = null
    @Volatile private var volumeMax: Int? = null
    @Volatile private var muted: Boolean? = null
    @Volatile private var manufacturer: String? = null
    @Volatile private var model: String? = null
    @Volatile private var softwareVersion: String? = null
    @Volatile private var reconnectEnabled = true
    @Volatile private var closingByUs = false

    init { if (paired) connect() }

    val paired: Boolean get() = !prefs.getString("tv_certificate", null).isNullOrBlank() && !prefs.getString("tv_fingerprint", null).isNullOrBlank()
    fun savedHost(): String = prefs.getString("host", "") ?: ""
    fun keys(): List<String> = KEYS
    val remoteTextInputEnabled: Boolean get() = prefs.getBoolean("enable_remote_text_input", false)

    fun setRemoteTextInputEnabled(enabled: Boolean) {
        if (remoteTextInputEnabled == enabled) return
        prefs.edit().putBoolean("enable_remote_text_input", enabled).apply()
        if (paired) connect()
    }

    fun snapshot(): RemoteSnapshot = RemoteSnapshot(
        paired, status == "connecting", connected, status, error, isOn, currentApp,
        volumeLevel, volumeMax, muted, manufacturer, model, softwareVersion,
        savedHost(), prefs.getString("tv_fingerprint", null)
    )

    fun startPair(hostValue: String) {
        val host = hostValue.trim().removePrefix("http://").removePrefix("https://").substringBefore(':').trim()
        require(host.isNotBlank() && host.length <= 253 && host.matches(Regex("[A-Za-z0-9.-]+"))) { "Enter the Airtel box IP address or host name." }
        io.execute {
            reconnectEnabled = false
            runCatching { pairing?.close(); closingByUs = true; session?.close(); closingByUs = false }
            pairing = null; session = null; connected = false; error = null; status = "connecting"
            if (savedHost().isNotBlank() && savedHost() != host) {
                prefs.edit().remove("tv_certificate").remove("tv_fingerprint").apply()
            }
            prefs.edit().putString("host", host).apply()
            try {
                val identity = ClientIdentity.loadOrCreate(identityDir)
                val fresh = PairingSession(host, identity.certDer, identity.keyDer, object : PairingSession.Events {
                    override fun onWaiting() { status = "waiting_code"; error = null }
                    override fun onPaired(serverCert: ByteArray) {
                        prefs.edit()
                            .putString("tv_certificate", Base64.encodeToString(serverCert, Base64.NO_WRAP))
                            .putString("tv_fingerprint", ClientIdentity.sha256(serverCert))
                            .putString("host", host).apply()
                        pairing = null
                        status = "paired_connecting"
                        reconnectEnabled = true
                        connect()
                    }
                    override fun onError(message: String) { status = "pair_error"; error = message }
                })
                pairing = fresh
                fresh.start()
            } catch (t: Throwable) { status = "pair_error"; error = t.message ?: "Pairing could not start" }
        }
    }

    fun finishPair(code: String) {
        val current = pairing ?: throw IllegalStateException("Start pairing first")
        val clean = code.trim().uppercase(Locale.US)
        require(clean.matches(Regex("[0-9A-F]{6}"))) { "Enter the 6-character code shown on the Airtel box." }
        io.execute { try { current.finish(clean) } catch (t: Throwable) { error = t.message ?: "Pairing code was rejected"; status = "waiting_code" } }
    }

    fun connect() {
        if (!paired) { status = "unpaired"; return }
        reconnectEnabled = true
        io.execute { connectOnIo() }
    }

    private fun connectOnIo() {
        if (!paired) return
        val host = savedHost()
        status = "connecting"; error = null; connected = false
        try {
            closingByUs = true
            runCatching { session?.close() }
            closingByUs = false
            val identity = ClientIdentity.loadOrCreate(identityDir)
            val certificate = Base64.decode(prefs.getString("tv_certificate", ""), Base64.DEFAULT)
            val fingerprint = prefs.getString("tv_fingerprint", "") ?: ""
            val fresh = AurSession(host, PORT_REMOTE, identity.certDer, identity.keyDer, fingerprint, remoteTextInputEnabled, object : AurSession.Events {
                override fun onConfigured(modelValue: String, vendor: String, software: String) {
                    model = modelValue; manufacturer = vendor; softwareVersion = software
                }
                override fun onReady(started: Boolean, rttMillis: Long) { isOn = started; connected = true; status = "connected"; error = null }
                override fun onVolume(level: Int, max: Int, mutedValue: Boolean) { volumeLevel = level; volumeMax = max; muted = mutedValue }
                override fun onApp(packageName: String) { currentApp = packageName }
                override fun onError(message: String) { error = message; status = "error" }
                override fun onClosed(reason: String) {
                    connected = false; error = reason; status = "disconnected"
                    if (!closingByUs && reconnectEnabled && paired) io.schedule({ connectOnIo() }, 2, TimeUnit.SECONDS)
                }
                override fun onPeerCert(sha256: String) { /* AurSession verifies this against the stored pin. */ }
            })
            session = fresh
            fresh.connect()
            if (!fresh.awaitReady(12_000)) throw IllegalStateException("The box connected but did not become ready.")
        } catch (t: Throwable) {
            connected = false; status = "disconnected"; error = t.message ?: "Could not connect to the Airtel box"
            if (reconnectEnabled && paired) io.schedule({ connectOnIo() }, 5, TimeUnit.SECONDS)
        }
    }

    fun sendKey(keyValue: String) {
        val key = normalizeKey(keyValue)
        require(KEYS.contains(key)) { "Unknown key: $keyValue" }
        requireSession().sendKey(key)
    }
    fun sendText(text: String) {
        check(remoteTextInputEnabled) { "Remote text input is disabled. Enable it in the Android app and reconnect." }
        require(text.length <= 1000) { "Text is too long" }
        requireSession().sendText(text)
    }
    fun launch(app: String) {
        require(app.isNotBlank() && app.length <= 512) { "Enter an app package or deep link" }
        val target = app.trim()
        val key = when (target.lowercase(Locale.US)) {
            "tv", "live", "livetv", "airteltv", "xstream", "com.airtel.tv" -> { sendKey("TV"); return }
            else -> target
        }
        val link = if (key.contains("://")) key else when (key.lowercase(Locale.US)) {
            "youtube", "com.google.android.youtube.tv", "com.google.android.youtube" -> "https://www.youtube.com/tv"
            "netflix", "com.netflix.ninja", "com.netflix.mediaclient" -> "https://www.netflix.com/browse"
            "prime", "primevideo", "com.amazon.amazonvideo.livingroom" -> "https://app.primevideo.com/"
            "hotstar", "in.startv.hotstar" -> "https://www.hotstar.com"
            "spotify", "com.spotify.tv.android" -> "market://search?q=spotify"
            "playstore", "com.android.vending" -> "market://details?id=com.android.vending"
            "jiocinema", "com.jio.media.ondemand" -> "https://www.jiocinema.com"
            "jiotv", "com.jio.tvapp" -> "https://www.jiotv.com"
            "zee5", "com.zee5.androiddt" -> "https://www.zee5.com"
            "sonyliv", "com.sonyliv" -> "https://www.sonyliv.com"
            else -> if (key.contains('.') && !key.contains(' ')) "market://details?id=$key" else key
        }
        requireSession().sendAppLink(link)
    }
    @Synchronized fun tune(redirectNumber: Int, fastTune: Boolean) {
        require(redirectNumber in 0..9999) { "Channel number must be 0–9999" }
        val remote = requireSession()
        remote.sendKey("HOME"); Thread.sleep(500)
        remote.sendKey("TV"); Thread.sleep(if (fastTune) 300 else 3000)
        redirectNumber.toString().forEach { digit -> remote.sendKey("NUM_$digit"); Thread.sleep(150) }
    }
    private fun requireSession(): AurSession = session?.takeIf { it.isReady } ?: throw IllegalStateException("Not connected to the Airtel box")

    private fun normalizeKey(supplied: String): String {
        var key = supplied.trim().uppercase(Locale.US).replace(Regex("[\\s-]+"), "_")
        if (key.startsWith("KEYCODE_")) key = key.removePrefix("KEYCODE_")
        if (key.startsWith("DPAD_")) key = key.removePrefix("DPAD_")
        if (key.matches(Regex("[0-9]"))) key = "NUM_$key"
        return key
    }
}
