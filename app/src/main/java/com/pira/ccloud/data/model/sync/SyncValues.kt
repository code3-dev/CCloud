package com.pira.ccloud.data.model.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

// Values stored in SyncRecord.v, one class per collection. Field names are shared with the Apple
// app, so they must not change.

@Serializable
data class FavoriteValue(
    val addedAt: Long,
    val item: MediaSnapshot
)

@Serializable
data class PlaylistValue(
    val name: String,
    val createdAt: Long
)

@Serializable
data class PlaylistItemValue(
    val addedAt: Long
)

@Serializable
data class WatchedValue(
    val watchedAt: Long
)

@Serializable
data class ProgressValue(
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean = false
)

@Serializable
data class SettingValue(
    val value: JsonPrimitive
)

/**
 * A movie or series as stored with a favorite. The catalog API can't fetch a single title by id,
 * so a favorite synced from another device is shown and played from this copy (including its
 * sources).
 */
@Serializable
data class MediaSnapshot(
    val id: Int,
    val kind: String,
    val title: String = "",
    val overview: String = "",
    val year: Int? = null,
    val imdb: Double? = null,
    val rating: Double? = null,
    val duration: String? = null,
    val poster: String? = null,
    val cover: String? = null,
    val genres: List<SnapshotGenre> = emptyList(),
    val countries: List<SnapshotCountry> = emptyList(),
    val sources: List<SnapshotSource> = emptyList()
)

@Serializable
data class SnapshotGenre(
    val id: Int,
    val title: String = ""
)

@Serializable
data class SnapshotCountry(
    val id: Int,
    val title: String = "",
    val image: String? = null
)

@Serializable
data class SnapshotSource(
    val id: Int,
    val quality: String = "",
    val format: String = "",
    val url: String
)
