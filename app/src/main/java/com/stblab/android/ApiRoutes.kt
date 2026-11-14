package com.stblab.android

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import com.xstream.remote.RemoteV2Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable private data class ErrorDto(val title: String, val detail: String? = null)
@Serializable private data class HealthDto(val status: String, val deviceConnected: Boolean, val detail: String?)
@Serializable private data class RemoteState(val connected: Boolean = false, val isOn: Boolean? = null, val currentApp: String? = null, val volume: Volume? = null, val device: Device? = null, val features: List<String> = emptyList())
@Serializable private data class Volume(val level: Int, val max: Int, val muted: Boolean)
@Serializable private data class Device(val manufacturer: String, val model: String, val softwareVersion: String)
@Serializable private data class TuneBody(val redirectNumber: Int, val channelName: String? = null, val fastTune: Boolean = false)
@Serializable private data class TuneResult(val redirectNumber: Int, val channelName: String? = null)
@Serializable private data class KeyBody(val key: String, val direction: String = "SHORT")
@Serializable private data class TextBody(val text: String)
@Serializable private data class LaunchBody(val app: String)
@Serializable private data class GenreBody(val name: String, val sortOrder: Int = 0)
@Serializable private data class ReorderGenres(val genreIds: List<Int>)
@Serializable private data class ChannelBody(val genreId: Int, val name: String, val dummyNumber: Int, val redirectNumber: Int, val sortOrder: Int = 0)
@Serializable private data class ReorderChannels(val genreId: Int, val channelIds: List<Int>)
@Serializable private data class ReorderFavorites(val channelIds: List<Int>)
@Serializable private data class KeyAck(val key: String, val direction: String)
@Serializable private data class TextAck(val text: String, val length: Int)
@Serializable private data class LaunchAck(val app: String)

fun Application.installStbLabRoutes(service: HostService) {
    val context = service.applicationContext
    val store = ChannelStore(context)
    val remote = RemoteV2Client.get(context)
    HostService.running = true
    routing {
        get("/") {
            val page = context.assets.open("web/index.html").bufferedReader().use { it.readText() }
            call.respondText(page, ContentType.Text.Html)
        }
        get("/api/v1/health") {
            val state = remote.snapshot()
            val health = HealthDto(if (state.connected) "ok" else "degraded", state.connected, if (state.connected) null else state.error ?: "Remote ${state.status}")
            call.respond(if (state.connected) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, health)
        }
        get("/api/v1/remote/state") {
            val s = remote.snapshot()
            call.respond(RemoteState(s.connected, s.isOn, s.currentApp,
                if (s.volumeLevel != null && s.volumeMax != null && s.muted != null) Volume(s.volumeLevel, s.volumeMax, s.muted) else null,
                if (s.manufacturer != null && s.model != null && s.softwareVersion != null) Device(s.manufacturer, s.model, s.softwareVersion) else null,
                if (s.connected) listOf("KEY", "IME", "APP_LINK", "VOLUME", "POWER") else emptyList()))
        }
        get("/api/v1/remote/keys") { call.respond(remote.keys()) }
        post("/api/v1/remote/key") {
            val body = call.receive<KeyBody>()
            if (!body.direction.equals("SHORT", true)) return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("Unsupported key direction", "Only SHORT key presses are supported."))
            call.remoteAction { remote.sendKey(body.key); KeyAck(body.key.uppercase(), "SHORT") }
        }
        post("/api/v1/remote/text") {
            val body = call.receive<TextBody>(); call.remoteAction { remote.sendText(body.text); TextAck(body.text, body.text.length) }
        }
        post("/api/v1/remote/launch") {
            val body = call.receive<LaunchBody>(); call.remoteAction { remote.launch(body.app); LaunchAck(body.app) }
        }
        post("/api/v1/remote/tune") {
            val body = call.receive<TuneBody>()
            call.remoteAction { withContext(Dispatchers.IO) { remote.tune(body.redirectNumber, body.fastTune) }; TuneResult(body.redirectNumber, body.channelName) }
        }
        get("/api/v1/genres") { call.respond(store.genres()) }
        post("/api/v1/genres") {
            val body = call.receive<GenreBody>(); call.respond(HttpStatusCode.Created, store.createGenre(body.name, body.sortOrder))
        }
        put("/api/v1/genres/reorder") {
            store.reorderGenres(call.receive<ReorderGenres>().genreIds); call.respond(HttpStatusCode.NoContent)
        }
        put("/api/v1/genres/{id}") {
            val b = call.receive<GenreBody>(); val result = store.updateGenre(call.parameters["id"]!!.toInt(), b.name, b.sortOrder)
            if (result == null) call.respond(HttpStatusCode.NotFound) else call.respond(result)
        }
        delete("/api/v1/genres/{id}") {
            if (store.deleteGenre(call.parameters["id"]!!.toInt())) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound)
        }
        get("/api/v1/channels/favorites") { call.respond(store.favorites()) }
        get("/api/v1/channels/lookup") { val n = call.request.queryParameters["number"]?.toIntOrNull(); val c = n?.let(store::lookup); if (c == null) call.respond(HttpStatusCode.NotFound) else call.respond(c) }
        put("/api/v1/channels/reorder") {
            val b = call.receive<ReorderChannels>(); store.reorderChannels(b.genreId, b.channelIds); call.respond(HttpStatusCode.NoContent)
        }
        put("/api/v1/channels/favorites/reorder") {
            store.reorderFavorites(call.receive<ReorderFavorites>().channelIds); call.respond(HttpStatusCode.NoContent)
        }
        post("/api/v1/channels/{id}/favorite") {
            val c = store.toggleFavorite(call.parameters["id"]!!.toInt()); if (c == null) call.respond(HttpStatusCode.NotFound) else call.respond(c)
        }
        post("/api/v1/channels") {
            val b = call.receive<ChannelBody>(); call.respond(HttpStatusCode.Created, store.createChannel(b.genreId, b.name, b.dummyNumber, b.redirectNumber, b.sortOrder))
        }
        put("/api/v1/channels/{id}") {
            val b = call.receive<ChannelBody>(); val c = store.updateChannel(call.parameters["id"]!!.toInt(), b.genreId, b.name, b.dummyNumber, b.redirectNumber, b.sortOrder)
            if (c == null) call.respond(HttpStatusCode.NotFound) else call.respond(c)
        }
        delete("/api/v1/channels/{id}") {
            if (store.deleteChannel(call.parameters["id"]!!.toInt())) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound)
        }

        route("/_next") {
            // Next emits root-relative URLs such as /_next/static/css/<hash>.css.
            // Use Ktor's tailcard selector here so nested chunk paths are captured
            // as individual path segments and then resolve directly in APK assets.
            get("/{assetPath...}") {
                val assetPath = call.parameters.getAll("assetPath").orEmpty().joinToString("/")
                call.serveWebAsset(context, "_next/$assetPath")
            }
        }

        // Next.js static export files: /_next chunks, styles, icons, and exported routes.
        get("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/").orEmpty()
            call.serveWebAsset(context, path)
        }
    }
}

private suspend fun ApplicationCall.serveWebAsset(context: android.content.Context, path: String) {
    if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.split('/').any { it == ".." }) {
        respond(HttpStatusCode.NotFound)
        return
    }
    val candidates = buildList {
        add(path)
        if (!path.substringAfterLast('/').contains('.')) {
            add("$path.html")
            add("$path/index.html")
        }
    }
    val content = withContext(Dispatchers.IO) {
        candidates.firstNotNullOfOrNull { candidate ->
            runCatching { context.assets.open("web/$candidate").use { it.readBytes() } }.getOrNull()
        }
    }
    if (content == null) {
        if (path.startsWith("_next/static/")) Log.w("StbLabAssets", "Static asset missing: $path")
        respond(HttpStatusCode.NotFound)
        return
    }
    if (path.startsWith("_next/static/")) Log.d("StbLabAssets", "Served $path (${content.size} bytes)")
    val extension = path.substringAfterLast('.', "").lowercase()
    val mime = when (extension) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "txt" -> "text/plain; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "ico" -> "image/x-icon"
        else -> "application/octet-stream"
    }
    respondBytes(content, ContentType.parse(mime))
}

private suspend fun ApplicationCall.remoteAction(action: suspend () -> Any) {
    try {
        respond(action())
    } catch (t: Throwable) {
        respond(HttpStatusCode.ServiceUnavailable, ErrorDto("Remote unavailable", t.message ?: "Could not reach the Airtel box."))
    }
}
