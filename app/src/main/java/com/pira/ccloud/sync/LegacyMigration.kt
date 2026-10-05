package com.pira.ccloud.sync

import com.pira.ccloud.data.model.FavoriteGroup
import com.pira.ccloud.data.model.FavoriteItem
import com.pira.ccloud.data.model.FontSettings
import com.pira.ccloud.data.model.VideoPlayerSettings
import com.pira.ccloud.data.model.WatchedEpisode
import com.pira.ccloud.data.model.sync.FavoriteValue
import com.pira.ccloud.data.model.sync.PlaylistItemValue
import com.pira.ccloud.data.model.sync.PlaylistValue
import com.pira.ccloud.data.model.sync.SettingValue
import com.pira.ccloud.data.model.sync.SyncRecord
import com.pira.ccloud.data.model.sync.WatchedValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Moves the pre-sync storage (`favorites.json`, `favorite_groups.json`, `watched_episodes.json`
 * and the synced part of the settings files) into records, once.
 *
 * Imported records get timestamp [LEGACY_TIMESTAMP], so any real change made on another device
 * wins over them when this device first syncs. The favorites, groups and watched files are renamed
 * to `*.v1.bak` afterwards; the settings files stay, because they still hold the settings that
 * don't sync (like the subtitle size).
 */
class LegacyMigration(
    private val directory: File,
    private val records: RecordStore
) {
    companion object {
        const val LEGACY_TIMESTAMP = 1L
        private const val MARKER = "sync_migrated_v2"
        private val legacyFiles = listOf("favorites.json", "favorite_groups.json", "watched_episodes.json")
    }

    private val json = SyncJson

    fun runIfNeeded() {
        val marker = File(directory, MARKER)
        if (marker.exists()) return
        records.edit {
            importFavorites()
            importGroups()
            importWatched()
            importSettings()
        }
        for (name in legacyFiles) {
            val file = File(directory, name)
            if (file.exists()) file.renameTo(File(directory, "$name.v1.bak"))
        }
        marker.writeText("1")
    }

    private fun RecordStore.Editor.importFavorites() {
        val favorites = read("favorites.json", ListSerializer(FavoriteItem.serializer())) ?: return
        // The file is newest first and has no dates; spread them out to keep that order.
        val newest = records.now()
        favorites.forEachIndexed { index, item ->
            val value = FavoriteValue(addedAt = newest - index, item = MediaSnapshotMapping.toSnapshot(item))
            import(legacy(SyncCollection.FAVORITE, SyncKeys.media(item.type, item.id), encode(value, FavoriteValue.serializer())))
        }
    }

    private fun RecordStore.Editor.importGroups() {
        val groups = read("favorite_groups.json", ListSerializer(FavoriteGroup.serializer())) ?: return
        val custom = groups.filter { !it.isDefault }
        val start = records.now() - custom.size
        custom.forEachIndexed { index, group ->
            val value = PlaylistValue(name = group.name, createdAt = start + index)
            import(legacy(SyncCollection.PLAYLIST, group.id, encode(value, PlaylistValue.serializer())))

            val members = group.movieIds.map { "movie" to it } + group.seriesIds.map { "series" to it }
            val first = records.now() - members.size
            members.forEachIndexed { position, (type, id) ->
                import(
                    legacy(
                        SyncCollection.PLAYLIST_ITEM,
                        SyncKeys.playlistItem(group.id, type, id),
                        encode(PlaylistItemValue(first + position), PlaylistItemValue.serializer())
                    )
                )
            }
        }
    }

    private fun RecordStore.Editor.importWatched() {
        val watched = read("watched_episodes.json", ListSerializer(WatchedEpisode.serializer())) ?: return
        for (episode in watched) {
            import(
                legacy(
                    SyncCollection.WATCHED,
                    SyncKeys.episode(episode.seriesId, episode.seasonId, episode.episodeId),
                    encode(WatchedValue(episode.watchedAt), WatchedValue.serializer())
                )
            )
        }
    }

    /** Only settings that differ from the default become records. */
    private fun RecordStore.Editor.importSettings() {
        read("font_settings.json", FontSettings.serializer())?.let { settings ->
            val name = SettingsMapping.fontName(settings.fontType)
            if (name != SettingsMapping.DEFAULT_FONT) importSetting(SyncSetting.FONT, JsonPrimitive(name))
        }
        read("video_player_settings.json", VideoPlayerSettings.serializer())?.let { settings ->
            val seconds = SettingsMapping.seekInterval(settings.seekTimeSeconds)
            if (seconds != null && seconds != SettingsMapping.DEFAULT_SEEK_INTERVAL) {
                importSetting(SyncSetting.SEEK_INTERVAL, JsonPrimitive(seconds))
            }
        }
        // SubtitleSettings depends on Android classes, so read its two color fields directly
        readObject("subtitle_settings.json")?.let { settings ->
            settings["textColor"]?.jsonPrimitive?.intOrNull
                ?.let(SettingsMapping::textColorName)
                ?.takeIf { it != SettingsMapping.DEFAULT_TEXT_COLOR }
                ?.let { importSetting(SyncSetting.SUBTITLE_TEXT_COLOR, JsonPrimitive(it)) }
            settings["borderColor"]?.jsonPrimitive?.intOrNull
                ?.let(SettingsMapping::backgroundName)
                ?.takeIf { it != SettingsMapping.DEFAULT_BACKGROUND }
                ?.let { importSetting(SyncSetting.SUBTITLE_BACKGROUND, JsonPrimitive(it)) }
        }
    }

    private fun RecordStore.Editor.importSetting(key: String, value: JsonPrimitive) {
        import(legacy(SyncCollection.SETTING, key, encode(SettingValue(value), SettingValue.serializer())))
    }

    private fun legacy(collection: String, key: String, value: JsonObject) =
        SyncRecord(c = collection, k = key, v = value, t = LEGACY_TIMESTAMP)

    private fun <T> read(name: String, serializer: KSerializer<T>): T? {
        val file = File(directory, name)
        if (!file.exists()) return null
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun readObject(name: String): JsonObject? {
        val file = File(directory, name)
        if (!file.exists()) return null
        return try {
            json.parseToJsonElement(file.readText()).jsonObject
        } catch (e: Exception) {
            null
        }
    }

    private fun <T> encode(value: T, serializer: KSerializer<T>): JsonObject =
        json.encodeToJsonElement(serializer, value).jsonObject
}
