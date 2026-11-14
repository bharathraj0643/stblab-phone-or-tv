package com.stblab.android

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.security.SecureRandom

@Serializable private data class ErrorDto(val title: String, val detail: String? = null)
@Serializable private data class HealthDto(val deviceConnected: Boolean, val message: String)
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
@Serializable private data class PinBody(val pin: String)
@Serializable private data class TokenDto(val token: String)

fun Application.installStbLabRoutes(service: HostService) {
    val context = service.applicationContext
    val store = ChannelStore(context)
    val prefs = context.getSharedPreferences("stblab_host", Context.MODE_PRIVATE)
    val pin = prefs.getString("access_pin", null) ?: (100000 + SecureRandom().nextInt(900000)).toString().also { prefs.edit().putString("access_pin", it).apply() }
    val token = prefs.getString("access_token", null) ?: ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }.also { prefs.edit().putString("access_token", it).apply() }
    HostService.running = true
    routing {
        get("/") {
            val page = context.assets.open("web/index.html").bufferedReader().use { it.readText() }
            call.respondText(page, ContentType.Text.Html)
        }
        get("/api/v1/health") { call.respond(HttpStatusCode.ServiceUnavailable, HealthDto(false, "Pairing with the Android TV Remote target is not configured yet")) }
        post("/api/auth") {
            val body = call.receive<PinBody>()
            val now = System.currentTimeMillis()
            val blockedUntil = prefs.getLong("pin_blocked_until", 0L)
            if (now < blockedUntil) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorDto("Too many attempts", "Try again in a few minutes."))
            } else if (body.pin != pin) {
                val failures = prefs.getInt("pin_failures", 0) + 1
                prefs.edit().putInt("pin_failures", if (failures >= 8) 0 else failures)
                    .putLong("pin_blocked_until", if (failures >= 8) now + 180_000L else 0L).apply()
                call.respond(HttpStatusCode.Unauthorized, ErrorDto("Incorrect PIN"))
            } else {
                prefs.edit().putInt("pin_failures", 0).putLong("pin_blocked_until", 0L).apply()
                call.respond(TokenDto(token))
            }
        }
        get("/api/v1/remote/state") { call.respond(RemoteState()) }
        get("/api/v1/remote/keys") { call.respond(emptyList<String>()) }
        post("/api/v1/remote/key") { if (!call.authorized(token)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("PIN required")); call.receive<KeyBody>(); call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("Remote not paired", "Android TV Remote v2 pairing is not configured.")) }
        post("/api/v1/remote/text") { if (!call.authorized(token)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("PIN required")); call.receive<TextBody>(); call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("Remote not paired", "Android TV Remote v2 pairing is not configured.")) }
        post("/api/v1/remote/launch") { if (!call.authorized(token)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("PIN required")); call.receive<LaunchBody>(); call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("Remote not paired", "Android TV Remote v2 pairing is not configured.")) }
        post("/api/v1/remote/tune") { if (!call.authorized(token)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("PIN required")); call.receive<TuneBody>(); call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("Remote not paired", "Android TV Remote v2 pairing is not configured.")) }
        get("/api/v1/genres") { call.respond(store.genres()) }
        post("/api/v1/genres") {
            if (!call.authorized(token)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("PIN required", "Enter the access PIN shown on the host TV."))
            val body = call.receive<GenreBody>(); call.respond(HttpStatusCode.Created, store.createGenre(body.name, body.sortOrder))
        }
        put("/api/v1/genres/reorder") {
            if (!call.authorized(token)) return@put call.respond(HttpStatusCode.Unauthorized, ErrorDto("PIN required"))
            store.reorderGenres(call.receive<ReorderGenres>().genreIds); call.respond(HttpStatusCode.NoContent)
        }
        put("/api/v1/genres/{id}") {
            if (!call.authorized(token)) return@put call.respond(HttpStatusCode.Unauthorized, ErrorDto("PIN required"))
            val b = call.receive<GenreBody>(); val result = store.updateGenre(call.parameters["id"]!!.toInt(), b.name, b.sortOrder)
            if (result == null) call.respond(HttpStatusCode.NotFound) else call.respond(result)
        }
        delete("/api/v1/genres/{id}") {
            if (!call.authorized(token)) return@delete call.respond(HttpStatusCode.Unauthorized, ErrorDto("PIN required"))
            if (store.deleteGenre(call.parameters["id"]!!.toInt())) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound)
        }
        get("/api/v1/channels/favorites") { call.respond(store.favorites()) }
        get("/api/v1/channels/lookup") { val n = call.request.queryParameters["number"]?.toIntOrNull(); val c = n?.let(store::lookup); if (c == null) call.respond(HttpStatusCode.NotFound) else call.respond(c) }
        put("/api/v1/channels/reorder") {
            if (!call.authorized(token)) return@put call.respond(HttpStatusCode.Unauthorized, ErrorDto("PIN required"))
            val b = call.receive<ReorderChannels>(); store.reorderChannels(b.genreId, b.channelIds); call.respond(HttpStatusCode.NoContent)
        }
        put("/api/v1/channels/favorites/reorder") {
            if (!call.authorized(token)) return@put call.respond(HttpStatusCode.Unauthorized, ErrorDto("PIN required"))
            store.reorderFavorites(call.receive<ReorderFavorites>().channelIds); call.respond(HttpStatusCode.NoContent)
        }
        post("/api/v1/channels/{id}/favorite") {
            if (!call.authorized(token)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("PIN required"))
            val c = store.toggleFavorite(call.parameters["id"]!!.toInt()); if (c == null) call.respond(HttpStatusCode.NotFound) else call.respond(c)
        }
        post("/api/v1/channels") {
            if (!call.authorized(token)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("PIN required"))
            val b = call.receive<ChannelBody>(); call.respond(HttpStatusCode.Created, store.createChannel(b.genreId, b.name, b.dummyNumber, b.redirectNumber, b.sortOrder))
        }
        put("/api/v1/channels/{id}") {
            if (!call.authorized(token)) return@put call.respond(HttpStatusCode.Unauthorized, ErrorDto("PIN required"))
            val b = call.receive<ChannelBody>(); val c = store.updateChannel(call.parameters["id"]!!.toInt(), b.genreId, b.name, b.dummyNumber, b.redirectNumber, b.sortOrder)
            if (c == null) call.respond(HttpStatusCode.NotFound) else call.respond(c)
        }
        delete("/api/v1/channels/{id}") {
            if (!call.authorized(token)) return@delete call.respond(HttpStatusCode.Unauthorized, ErrorDto("PIN required"))
            if (store.deleteChannel(call.parameters["id"]!!.toInt())) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound)
        }
    }
}

private suspend fun ApplicationCall.authorized(token: String): Boolean {
    val supplied = request.headers["X-StbLab-Token"] ?: return false
    return supplied == token
}
