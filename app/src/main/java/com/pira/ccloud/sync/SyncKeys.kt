package com.pira.ccloud.sync

import kotlinx.serialization.json.Json

/** The collections a [com.pira.ccloud.data.model.sync.SyncRecord] can belong to. */
object SyncCollection {
    const val FAVORITE = "favorite"
    const val PLAYLIST = "playlist"
    const val PLAYLIST_ITEM = "playlistItem"
    const val WATCHED = "watched"
    const val PROGRESS = "progress"
    const val SETTING = "setting"

    val all = listOf(FAVORITE, PLAYLIST, PLAYLIST_ITEM, WATCHED, PROGRESS, SETTING)
}

/** Keys of the synced settings. Everything else in Settings stays on the device. */
object SyncSetting {
    const val FONT = "font"
    const val SEEK_INTERVAL = "seekInterval"
    const val SUBTITLE_TEXT_COLOR = "subtitleTextColor"
    const val SUBTITLE_BACKGROUND = "subtitleBackground"
}

/**
 * Canonical record keys, shared with the Apple app. A title is `movie:<id>` or `series:<id>` (the
 * same as `FavoriteItem.type`), because movies and series have separate id spaces.
 */
object SyncKeys {
    fun media(type: String, id: Int) = "$type:$id"

    /** The type and id of a [media] key, or null if [key] isn't one. */
    fun parseMedia(key: String): Pair<String, Int>? {
        val parts = key.split(':')
        if (parts.size != 2) return null
        val id = parts[1].toIntOrNull() ?: return null
        return parts[0] to id
    }

    fun playlistItem(playlistId: String, type: String, id: Int) = "$playlistId|${media(type, id)}"

    /** The playlist id and media key of a [playlistItem] key, or null if [key] isn't one. */
    fun parsePlaylistItem(key: String): Pair<String, String>? {
        val separator = key.indexOf('|')
        if (separator <= 0 || separator == key.length - 1) return null
        return key.substring(0, separator) to key.substring(separator + 1)
    }

    fun episode(seriesId: Int, seasonId: Int, episodeId: Int) = "$seriesId:$seasonId:$episodeId"

    fun parseEpisode(key: String): Triple<Int, Int, Int>? {
        val parts = key.split(':').map { it.toIntOrNull() ?: return null }
        if (parts.size != 3) return null
        return Triple(parts[0], parts[1], parts[2])
    }

    fun movieProgress(movieId: Int) = "movie:$movieId"

    fun episodeProgress(seriesId: Int, seasonId: Int, episodeId: Int) =
        "episode:${episode(seriesId, seasonId, episodeId)}"
}

/** JSON settings for records: tolerant of fields added by newer app versions or the Apple app. */
val SyncJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
