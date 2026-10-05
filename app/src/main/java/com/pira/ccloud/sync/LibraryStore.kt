package com.pira.ccloud.sync

import com.pira.ccloud.data.model.FavoriteGroup
import com.pira.ccloud.data.model.FavoriteItem
import com.pira.ccloud.data.model.WatchedEpisode
import com.pira.ccloud.data.model.sync.FavoriteValue
import com.pira.ccloud.data.model.sync.PlaylistItemValue
import com.pira.ccloud.data.model.sync.PlaylistValue
import com.pira.ccloud.data.model.sync.ProgressValue
import com.pira.ccloud.data.model.sync.SettingValue
import com.pira.ccloud.data.model.sync.SyncRecord
import com.pira.ccloud.data.model.sync.WatchedValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Favorites, playlists, watched episodes, playback positions and the synced settings, stored as
 * records in a [RecordStore]. `StorageUtils` keeps its old API and delegates here.
 *
 * Playlists are this app's favorite groups. The built-in default group isn't stored: it always
 * holds every favorite.
 */
class LibraryStore(val records: RecordStore) {

    // Favorites

    /** Newest first. */
    fun favorites(): List<FavoriteItem> =
        records.live(SyncCollection.FAVORITE)
            .mapNotNull { record -> decode(record, FavoriteValue.serializer())?.let { record.k to it } }
            .sortedWith(compareByDescending<Pair<String, FavoriteValue>> { it.second.addedAt }.thenBy { it.first })
            .map { MediaSnapshotMapping.toFavoriteItem(it.second.item) }

    fun isFavorite(type: String, id: Int): Boolean =
        records.value(SyncCollection.FAVORITE, SyncKeys.media(type, id)) != null

    /** Adds [item] as the newest favorite. Adding it again moves it to the top. */
    fun addFavorite(item: FavoriteItem) {
        records.edit {
            val value = FavoriteValue(addedAt = records.now(), item = MediaSnapshotMapping.toSnapshot(item))
            put(SyncCollection.FAVORITE, SyncKeys.media(item.type, item.id), encode(value, FavoriteValue.serializer()))
        }
    }

    /** Removes the favorite and takes it out of every playlist. */
    fun removeFavorite(type: String, id: Int) {
        val mediaKey = SyncKeys.media(type, id)
        records.edit {
            delete(SyncCollection.FAVORITE, mediaKey)
            live(SyncCollection.PLAYLIST_ITEM)
                .filter { SyncKeys.parsePlaylistItem(it.k)?.second == mediaKey }
                .forEach { delete(SyncCollection.PLAYLIST_ITEM, it.k) }
        }
    }

    /** Removes every favorite. Playlists stay, but become empty. */
    fun clearFavorites() {
        records.edit {
            live(SyncCollection.FAVORITE).forEach { delete(SyncCollection.FAVORITE, it.k) }
            live(SyncCollection.PLAYLIST_ITEM).forEach { delete(SyncCollection.PLAYLIST_ITEM, it.k) }
        }
    }

    // Playlists

    /** The user's playlists (not the default group), oldest first. */
    fun playlists(): List<FavoriteGroup> {
        val members = memberships()
        return records.live(SyncCollection.PLAYLIST)
            .mapNotNull { record -> decode(record, PlaylistValue.serializer())?.let { record.k to it } }
            .sortedWith(compareBy<Pair<String, PlaylistValue>> { it.second.createdAt }.thenBy { it.first })
            .map { (id, value) -> group(id, value.name, members[id].orEmpty()) }
    }

    fun playlist(id: String): FavoriteGroup? {
        val value = records.value(SyncCollection.PLAYLIST, id)?.let { decode(it, PlaylistValue.serializer()) }
            ?: return null
        return group(id, value.name, memberships()[id].orEmpty())
    }

    /**
     * Creates or renames a playlist and makes its contents exactly [group]'s ids, like the old
     * storage did when it replaced the whole group. The default group isn't stored.
     */
    fun savePlaylist(group: FavoriteGroup) {
        if (group.isDefault) return
        records.edit {
            val existing = get(SyncCollection.PLAYLIST, group.id)
                ?.takeUnless { it.d }
                ?.v?.let { decode(it, PlaylistValue.serializer()) }
            if (existing == null || existing.name != group.name) {
                val value = PlaylistValue(name = group.name, createdAt = existing?.createdAt ?: records.now())
                put(SyncCollection.PLAYLIST, group.id, encode(value, PlaylistValue.serializer()))
            }

            val wanted = group.movieIds.map { SyncKeys.playlistItem(group.id, "movie", it) } +
                group.seriesIds.map { SyncKeys.playlistItem(group.id, "series", it) }
            val current = live(SyncCollection.PLAYLIST_ITEM)
                .map { it.k }
                .filter { SyncKeys.parsePlaylistItem(it)?.first == group.id }
                .toSet()
            val now = records.now()
            wanted.filter { it !in current }.forEach { key ->
                put(SyncCollection.PLAYLIST_ITEM, key, encode(PlaylistItemValue(now), PlaylistItemValue.serializer()))
            }
            (current - wanted.toSet()).forEach { delete(SyncCollection.PLAYLIST_ITEM, it) }
        }
    }

    fun removePlaylist(id: String) {
        records.edit {
            delete(SyncCollection.PLAYLIST, id)
            live(SyncCollection.PLAYLIST_ITEM)
                .filter { SyncKeys.parsePlaylistItem(it.k)?.first == id }
                .forEach { delete(SyncCollection.PLAYLIST_ITEM, it.k) }
        }
    }

    fun addToPlaylist(playlistId: String, type: String, id: Int) {
        val key = SyncKeys.playlistItem(playlistId, type, id)
        if (records.value(SyncCollection.PLAYLIST_ITEM, key) != null) return
        records.edit {
            put(SyncCollection.PLAYLIST_ITEM, key, encode(PlaylistItemValue(records.now()), PlaylistItemValue.serializer()))
        }
    }

    fun removeFromPlaylist(playlistId: String, type: String, id: Int) {
        records.edit { delete(SyncCollection.PLAYLIST_ITEM, SyncKeys.playlistItem(playlistId, type, id)) }
    }

    fun isInPlaylist(playlistId: String, type: String, id: Int): Boolean =
        records.value(SyncCollection.PLAYLIST_ITEM, SyncKeys.playlistItem(playlistId, type, id)) != null

    fun playlistsContaining(type: String, id: Int): List<FavoriteGroup> =
        playlists().filter { group ->
            when (type) {
                "movie" -> id in group.movieIds
                "series" -> id in group.seriesIds
                else -> false
            }
        }

    /** The favorites in a playlist, most recently added first. */
    fun favoritesInPlaylist(playlistId: String): List<FavoriteItem> {
        val favorites = favorites().associateBy { SyncKeys.media(it.type, it.id) }
        return records.live(SyncCollection.PLAYLIST_ITEM)
            .mapNotNull { record ->
                val (playlist, mediaKey) = SyncKeys.parsePlaylistItem(record.k) ?: return@mapNotNull null
                if (playlist != playlistId) return@mapNotNull null
                val item = favorites[mediaKey] ?: return@mapNotNull null
                val addedAt = decode(record, PlaylistItemValue.serializer())?.addedAt ?: 0
                Triple(addedAt, mediaKey, item)
            }
            .sortedWith(compareByDescending<Triple<Long, String, FavoriteItem>> { it.first }.thenBy { it.second })
            .map { it.third }
    }

    /** Media keys of each playlist's live memberships, oldest first. */
    private fun memberships(): Map<String, List<String>> =
        records.live(SyncCollection.PLAYLIST_ITEM)
            .mapNotNull { record ->
                val (playlist, mediaKey) = SyncKeys.parsePlaylistItem(record.k) ?: return@mapNotNull null
                val addedAt = decode(record, PlaylistItemValue.serializer())?.addedAt ?: 0
                Triple(playlist, mediaKey, addedAt)
            }
            .sortedWith(compareBy<Triple<String, String, Long>> { it.third }.thenBy { it.second })
            .groupBy({ it.first }, { it.second })

    private fun group(id: String, name: String, mediaKeys: List<String>): FavoriteGroup {
        val parsed = mediaKeys.mapNotNull { SyncKeys.parseMedia(it) }
        return FavoriteGroup(
            id = id,
            name = name,
            isDefault = false,
            movieIds = parsed.filter { it.first == "movie" }.map { it.second }.toMutableList(),
            seriesIds = parsed.filter { it.first == "series" }.map { it.second }.toMutableList()
        )
    }

    // Watched episodes

    fun watchedEpisodes(): List<WatchedEpisode> =
        records.live(SyncCollection.WATCHED).mapNotNull { record ->
            val (seriesId, seasonId, episodeId) = SyncKeys.parseEpisode(record.k) ?: return@mapNotNull null
            val value = decode(record, WatchedValue.serializer()) ?: return@mapNotNull null
            WatchedEpisode(seriesId, seasonId, episodeId, value.watchedAt)
        }

    fun isWatched(seriesId: Int, seasonId: Int, episodeId: Int): Boolean =
        records.value(SyncCollection.WATCHED, SyncKeys.episode(seriesId, seasonId, episodeId)) != null

    fun markWatched(episode: WatchedEpisode) {
        records.edit {
            put(
                SyncCollection.WATCHED,
                SyncKeys.episode(episode.seriesId, episode.seasonId, episode.episodeId),
                encode(WatchedValue(episode.watchedAt), WatchedValue.serializer())
            )
        }
    }

    fun unmarkWatched(seriesId: Int, seasonId: Int, episodeId: Int) {
        records.edit { delete(SyncCollection.WATCHED, SyncKeys.episode(seriesId, seasonId, episodeId)) }
    }

    fun clearWatched() {
        records.edit { live(SyncCollection.WATCHED).forEach { delete(SyncCollection.WATCHED, it.k) } }
    }

    // Playback progress

    fun progress(key: String): ProgressValue? =
        records.value(SyncCollection.PROGRESS, key)?.let { decode(it, ProgressValue.serializer()) }

    /**
     * Saves a playback position under [ProgressPolicy]: nothing before 30 s, and a finished title
     * is stored as completed. Returns what was stored, or null if nothing was.
     */
    fun recordProgress(key: String, positionMs: Long, durationMs: Long): ProgressValue? {
        val value = ProgressPolicy.evaluate(positionMs, durationMs) ?: return null
        if (progress(key) == value) return value
        records.edit {
            put(SyncCollection.PROGRESS, key, encode(value, ProgressValue.serializer()))
            prune()
        }
        return value
    }

    /** Forgets the position, so the title starts from the beginning next time ("Start over"). */
    fun clearProgress(key: String) {
        records.edit { delete(SyncCollection.PROGRESS, key) }
    }

    /**
     * Keeps at most [ProgressPolicy.MAX_RECORDS] progress records, dropping the oldest finished
     * ones (and tombstones) first, then the oldest unfinished ones. Pruning is local: it doesn't
     * delete the position on other devices.
     */
    private fun RecordStore.Editor.prune() {
        val all = all(SyncCollection.PROGRESS)
        val excess = all.size - ProgressPolicy.MAX_RECORDS
        if (excess <= 0) return
        val victims = all
            .sortedWith(
                compareBy<SyncRecord> { record ->
                    when {
                        record.d -> 0
                        decode(record, ProgressValue.serializer())?.completed == true -> 1
                        else -> 2
                    }
                }.thenBy { it.t }
            )
            .take(excess)
            .map { it.k }
        forget(SyncCollection.PROGRESS, victims)
    }

    // Settings

    fun settingString(key: String): String? =
        records.value(SyncCollection.SETTING, key)?.let { decode(it, SettingValue.serializer()) }
            ?.value?.contentOrNull

    fun settingInt(key: String): Int? =
        records.value(SyncCollection.SETTING, key)?.let { decode(it, SettingValue.serializer()) }
            ?.value?.intOrNull

    /**
     * Stores a synced setting. A value equal to the default isn't stored while the setting has
     * never been set (so a fresh device never overrides the other devices with defaults), but a
     * change back to the default is.
     */
    fun setSetting(key: String, value: JsonPrimitive, default: JsonPrimitive) {
        val existing = records.value(SyncCollection.SETTING, key)?.let { decode(it, SettingValue.serializer()) }
        if (existing == null && value == default) return
        if (existing?.value == value) return
        records.edit {
            put(SyncCollection.SETTING, key, encode(SettingValue(value), SettingValue.serializer()))
        }
    }

    private fun <T> decode(record: SyncRecord, serializer: KSerializer<T>): T? =
        record.v?.let { decode(it, serializer) }

    private fun <T> decode(value: JsonObject, serializer: KSerializer<T>): T? =
        try {
            SyncJson.decodeFromJsonElement(serializer, value)
        } catch (e: Exception) {
            null
        }

    private fun <T> encode(value: T, serializer: KSerializer<T>): JsonObject =
        SyncJson.encodeToJsonElement(serializer, value).jsonObject
}
