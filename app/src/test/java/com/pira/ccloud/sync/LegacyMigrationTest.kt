package com.pira.ccloud.sync

import com.pira.ccloud.data.model.FavoriteGroup
import com.pira.ccloud.data.model.FavoriteItem
import com.pira.ccloud.data.model.FontSettings
import com.pira.ccloud.data.model.FontType
import com.pira.ccloud.data.model.Genre
import com.pira.ccloud.data.model.Source
import com.pira.ccloud.data.model.VideoPlayerSettings
import com.pira.ccloud.data.model.WatchedEpisode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyMigrationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val now = 1_000_000L
    private val records by lazy { RecordStore(folder.root, "device-a") { now } }
    private val library by lazy { LibraryStore(records) }

    // The old StorageUtils wrote with the default Json instance
    private fun write(name: String, text: String) = File(folder.root, name).writeText(text)

    private fun item(id: Int, type: String) = FavoriteItem(
        id = id, type = type, title = "T$id", description = "", year = 2020, imdb = 7.0, rating = 6.0,
        duration = null, image = "img$id", cover = "cover$id", genres = listOf(Genre(1, "G")),
        country = emptyList(), sources = if (type == "movie") listOf(Source(1, "720p", "mp4", "https://x/$id.mp4")) else emptyList()
    )

    private fun writeLegacyFiles() {
        // Newest first, as saveFavorite kept it
        write("favorites.json", Json.encodeToString(ListSerializer(FavoriteItem.serializer()), listOf(item(3, "movie"), item(2, "series"), item(1, "movie"))))
        write(
            "favorite_groups.json",
            Json.encodeToString(
                ListSerializer(FavoriteGroup.serializer()),
                listOf(
                    FavoriteGroup(id = "default", name = "Favorites", isDefault = true),
                    FavoriteGroup(id = "g1", name = "Action", movieIds = mutableListOf(1, 3)),
                    FavoriteGroup(id = "g2", name = "Later", seriesIds = mutableListOf(2))
                )
            )
        )
        write("watched_episodes.json", Json.encodeToString(ListSerializer(WatchedEpisode.serializer()), listOf(WatchedEpisode(10, 20, 30, watchedAt = 555))))
        write("font_settings.json", Json.encodeToString(FontSettings.serializer(), FontSettings(FontType.VAZIRMATN)))
        write("video_player_settings.json", Json.encodeToString(VideoPlayerSettings.serializer(), VideoPlayerSettings(seekTimeSeconds = 10)))
        // Red text, default glass background, a TV-sized font
        write("subtitle_settings.json", """{"textColor":${0xFFFF0000.toInt()},"borderColor":${0x80000000.toInt()},"textSize":25.0}""")
    }

    @Test
    fun importsEverythingOnce() {
        writeLegacyFiles()
        LegacyMigration(folder.root, records).runIfNeeded()

        assertEquals(listOf(3, 2, 1), library.favorites().map { it.id })
        assertEquals(item(3, "movie"), library.favorites().first())
        assertEquals(listOf("Action", "Later"), library.playlists().map { it.name })
        assertEquals(setOf(1, 3), library.playlist("g1")!!.movieIds.toSet())
        assertEquals(listOf(2), library.playlist("g2")!!.seriesIds)
        assertEquals(listOf(WatchedEpisode(10, 20, 30, 555)), library.watchedEpisodes())

        // Only settings that differ from the defaults become records
        assertEquals("vazirmatn", library.settingString(SyncSetting.FONT))
        assertNull(library.settingInt(SyncSetting.SEEK_INTERVAL))
        assertEquals("red", library.settingString(SyncSetting.SUBTITLE_TEXT_COLOR))
        assertNull(library.settingString(SyncSetting.SUBTITLE_BACKGROUND))

        // Migrated records lose to any real change from another device, and are queued for upload
        val favorite = records.get(SyncCollection.FAVORITE, "movie:3")!!
        assertEquals(LegacyMigration.LEGACY_TIMESTAMP, favorite.t)
        assertTrue(favorite.dirty)

        // Old library files are kept as backups; settings files stay in use
        assertFalse(File(folder.root, "favorites.json").exists())
        assertTrue(File(folder.root, "favorites.json.v1.bak").exists())
        assertTrue(File(folder.root, "subtitle_settings.json").exists())

        // A second run (and a stray old file) changes nothing
        write("favorites.json", Json.encodeToString(ListSerializer(FavoriteItem.serializer()), listOf(item(99, "movie"))))
        LegacyMigration(folder.root, records).runIfNeeded()
        assertFalse(library.isFavorite("movie", 99))
    }

    @Test
    fun freshInstallHasNothingToImport() {
        LegacyMigration(folder.root, records).runIfNeeded()
        assertTrue(library.favorites().isEmpty())
        assertTrue(library.playlists().isEmpty())
        assertNull(library.settingString(SyncSetting.FONT))
    }

    @Test
    fun unreadableOldFileDoesNotBlockTheRest() {
        write("favorites.json", "{broken")
        write("watched_episodes.json", Json.encodeToString(ListSerializer(WatchedEpisode.serializer()), listOf(WatchedEpisode(1, 2, 3, 4))))
        LegacyMigration(folder.root, records).runIfNeeded()
        assertTrue(library.favorites().isEmpty())
        assertTrue(library.isWatched(1, 2, 3))
    }

    @Test
    fun migratedFavoriteLosesToARemoteDeletion() {
        writeLegacyFiles()
        LegacyMigration(folder.root, records).runIfNeeded()
        val deletion = records.get(SyncCollection.FAVORITE, "movie:1")!!.copy(v = null, d = true, t = 2, dev = "device-b")
        assertEquals(1, records.applyRemote(listOf(deletion)))
        assertFalse(library.isFavorite("movie", 1))
    }
}
