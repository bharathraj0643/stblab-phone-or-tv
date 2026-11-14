package com.stblab.android

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable data class ChannelDto(val id: Int, val genreId: Int, val name: String, val dummyNumber: Int, val redirectNumber: Int, val sortOrder: Int = 0, val isFavorite: Boolean = false, val favoriteOrder: Int = 0)
@Serializable data class GenreDto(val id: Int, val name: String, val sortOrder: Int = 0, val channels: List<ChannelDto> = emptyList())
@Serializable data class ChannelConfigDto(val version: Int = 1, val genres: List<GenreDto>)

/** Small atomic JSON store; data stays in app-private storage and survives upgrades. */
class ChannelStore(context: Context) {
    private val file = File(context.filesDir, "channels.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    init {
        if (!file.exists()) {
            val defaults = context.assets.open("default-channels.json").bufferedReader().use { it.readText() }
            val config = json.decodeFromString<ChannelConfigDto>(defaults)
            require(config.version == 1) { "Unsupported bundled channel config version ${config.version}" }
            write(config.genres)
        }
    }

    private fun read(): List<GenreDto> = if (!file.exists()) emptyList() else runCatching {
        json.decodeFromString<List<GenreDto>>(file.readText())
    }.getOrDefault(emptyList())
    private fun write(genres: List<GenreDto>) {
        val temp = File(file.parentFile, "channels.json.tmp")
        temp.writeText(json.encodeToString(genres))
        if (!temp.renameTo(file)) { file.writeText(temp.readText()); temp.delete() }
    }
    fun genres(): List<GenreDto> = synchronized(lock) { read().sortedBy { it.sortOrder }.map { it.copy(channels = it.channels.sortedBy(ChannelDto::sortOrder)) } }
    fun exportConfig(): ChannelConfigDto = synchronized(lock) { ChannelConfigDto(genres = genres()) }
    fun importConfig(config: ChannelConfigDto) = synchronized(lock) {
        require(config.version == 1) { "Unsupported JSON version ${config.version}; expected 1" }
        require(config.genres.isNotEmpty()) { "JSON must contain at least one genre" }
        require(config.genres.all { it.id > 0 && it.name.isNotBlank() && it.name.length <= 100 }) { "Every genre needs a positive id and a name up to 100 characters" }
        require(config.genres.map { it.id }.distinct().size == config.genres.size) { "Genre IDs must be unique" }
        require(config.genres.map { it.name.trim().lowercase() }.distinct().size == config.genres.size) { "Genre names must be unique" }
        val channels = config.genres.flatMap { genre -> genre.channels.map { it to genre } }
        require(channels.map { it.first.id }.distinct().size == channels.size) { "Channel IDs must be unique" }
        require(channels.all { (channel, genre) -> channel.id > 0 && channel.genreId == genre.id && channel.name.isNotBlank() && channel.name.length <= 100 && channel.dummyNumber in 0..9999 && channel.redirectNumber in 0..9999 }) {
            "Each channel needs a unique positive id, matching genreId, name, and channel numbers from 0 to 9999"
        }
        write(config.genres)
    }
    fun favorites(): List<ChannelDto> = synchronized(lock) { read().flatMap { it.channels }.filter { it.isFavorite }.sortedBy(ChannelDto::favoriteOrder) }
    fun createGenre(name: String, sort: Int): GenreDto = synchronized(lock) {
        require(name.isNotBlank() && name.length <= 100)
        val all = read(); require(all.none { it.name.equals(name, true) }) { "Genre already exists" }
        val item = GenreDto((all.maxOfOrNull { it.id } ?: 0) + 1, name.trim(), sort)
        write(all + item); item
    }
    fun updateGenre(id: Int, name: String, sort: Int): GenreDto? = synchronized(lock) {
        require(name.isNotBlank() && name.length <= 100)
        var result: GenreDto? = null
        val all = read().map { if (it.id == id) it.copy(name = name.trim(), sortOrder = sort).also { v -> result = v } else it }
        if (result != null) write(all); result
    }
    fun deleteGenre(id: Int): Boolean = synchronized(lock) { val all = read(); val next = all.filterNot { it.id == id }; if (next.size == all.size) false else { write(next); true } }
    fun createChannel(genreId: Int, name: String, dummy: Int, redirect: Int, sort: Int): ChannelDto = synchronized(lock) {
        validateChannel(name, dummy, redirect)
        val all = read(); require(all.any { it.id == genreId }) { "Genre $genreId does not exist" }
        val id = all.flatMap { it.channels }.maxOfOrNull { it.id }?.plus(1) ?: 1
        val item = ChannelDto(id, genreId, name.trim(), dummy, redirect, sort)
        write(all.map { if (it.id == genreId) it.copy(channels = it.channels + item) else it }); item
    }
    fun updateChannel(id: Int, genreId: Int, name: String, dummy: Int, redirect: Int, sort: Int): ChannelDto? = synchronized(lock) {
        validateChannel(name, dummy, redirect)
        val all = read(); require(all.any { it.id == genreId }) { "Genre $genreId does not exist" }
        val old = all.flatMap { it.channels }.firstOrNull { it.id == id } ?: return@synchronized null
        val item = old.copy(genreId = genreId, name = name.trim(), dummyNumber = dummy, redirectNumber = redirect, sortOrder = sort)
        write(all.map { g -> g.copy(channels = (if (g.id == old.genreId) g.channels.filterNot { it.id == id } else g.channels) + if (g.id == genreId) listOf(item) else emptyList()) })
        item
    }
    fun deleteChannel(id: Int): Boolean = synchronized(lock) { val all = read(); val next = all.map { it.copy(channels = it.channels.filterNot { c -> c.id == id }) }; if (next.sumOf { it.channels.size } == all.sumOf { it.channels.size }) false else { write(next); true } }
    fun toggleFavorite(id: Int): ChannelDto? = synchronized(lock) {
        var result: ChannelDto? = null
        val all = read().map { g -> g.copy(channels = g.channels.map { c -> if (c.id == id) c.copy(isFavorite = !c.isFavorite).also { result = it } else c }) }
        if (result != null) write(all); result
    }
    fun reorderGenres(ids: List<Int>) = synchronized(lock) { val all = read(); write(all.map { it.copy(sortOrder = ids.indexOf(it.id).takeIf { n -> n >= 0 } ?: it.sortOrder) }) }
    fun reorderChannels(genreId: Int, ids: List<Int>) = synchronized(lock) { val all = read(); write(all.map { g -> if (g.id == genreId) g.copy(channels = g.channels.map { it.copy(sortOrder = ids.indexOf(it.id).takeIf { n -> n >= 0 } ?: it.sortOrder) }) else g }) }
    fun reorderFavorites(ids: List<Int>) = synchronized(lock) { val all = read(); write(all.map { g -> g.copy(channels = g.channels.map { c -> c.copy(favoriteOrder = ids.indexOf(c.id).takeIf { n -> n >= 0 } ?: c.favoriteOrder) }) }) }
    fun lookup(number: Int): ChannelDto? = synchronized(lock) { read().flatMap { it.channels }.firstOrNull { it.dummyNumber == number } ?: read().flatMap { it.channels }.firstOrNull { it.redirectNumber == number } }
    private fun validateChannel(name: String, dummy: Int, redirect: Int) { require(name.isNotBlank() && name.length <= 100); require(dummy in 0..9999 && redirect in 0..9999) }
}
