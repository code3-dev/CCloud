package com.pira.ccloud.sync

import com.pira.ccloud.data.model.Country
import com.pira.ccloud.data.model.FavoriteGroup
import com.pira.ccloud.data.model.FavoriteItem
import com.pira.ccloud.data.model.Genre
import com.pira.ccloud.data.model.Source
import com.pira.ccloud.data.model.WatchedEpisode
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000L
    private val library by lazy { LibraryStore(RecordStore(folder.root, "device-a") { now }) }

    private fun item(id: Int, type: String = "movie") = FavoriteItem(
        id = id,
        type = type,
        title = "Title $id",
        description = "توضیحات",
        year = 2024,
        imdb = 7.5,
        rating = 6.0,
        duration = "1:40",
        image = "https://example.com/$id.jpg",
        cover = "",
        genres = listOf(Genre(1, "Drama")),
        country = listOf(Country(2, "Iran", "https://example.com/ir.png")),
        sources = listOf(Source(9, "1080p", "mkv", "https://example.com/$id.mkv"))
    )

    @Test
    fun favoritesAreNewestFirstAndReAddingMovesToTop() {
        library.addFavorite(item(1)); now++
        library.addFavorite(item(2, "series")); now++
        library.addFavorite(item(3)); now++
        assertEquals(listOf(3, 2, 1), library.favorites().map { it.id })
        library.addFavorite(item(1)); now++
        assertEquals(listOf(1, 3, 2), library.favorites().map { it.id })
        assertTrue(library.isFavorite("series", 2))
        assertFalse(library.isFavorite("movie", 2))
    }

    @Test
    fun favoriteRoundTripsThroughTheSnapshot() {
        library.addFavorite(item(1))
        assertEquals(item(1), library.favorites().single())
    }

    @Test
    fun removingAFavoriteTakesItOutOfPlaylists() {
        library.addFavorite(item(1))
        library.savePlaylist(FavoriteGroup(id = "p", name = "Action"))
        library.addToPlaylist("p", "movie", 1)
        assertEquals(listOf(1), library.favoritesInPlaylist("p").map { it.id })

        library.removeFavorite("movie", 1)
        assertTrue(library.favorites().isEmpty())
        assertFalse(library.isInPlaylist("p", "movie", 1))
        // Adding it back doesn't bring back the old membership
        library.addFavorite(item(1))
        assertTrue(library.favoritesInPlaylist("p").isEmpty())
    }

    @Test
    fun clearingFavoritesKeepsEmptyPlaylists() {
        library.addFavorite(item(1))
        library.savePlaylist(FavoriteGroup(id = "p", name = "Action"))
        library.addToPlaylist("p", "movie", 1)
        library.clearFavorites()
        assertEquals(listOf("Action"), library.playlists().map { it.name })
        assertTrue(library.playlists().single().movieIds.isEmpty())
    }

    @Test
    fun playlistsCreateRenameAndReplaceContents() {
        library.savePlaylist(FavoriteGroup(id = "a", name = "First")); now++
        library.savePlaylist(FavoriteGroup(id = "b", name = "Second", movieIds = mutableListOf(1, 2), seriesIds = mutableListOf(5))); now++
        assertEquals(listOf("First", "Second"), library.playlists().map { it.name })
        assertEquals(listOf(1, 2), library.playlist("b")!!.movieIds)
        assertEquals(listOf(5), library.playlist("b")!!.seriesIds)

        // Renaming keeps the creation order; the saved ids replace the old contents
        library.savePlaylist(FavoriteGroup(id = "a", name = "Renamed", movieIds = mutableListOf(2))); now++
        library.savePlaylist(library.playlist("b")!!.copy(name = "Second").also { it.removeMovie(1) })
        assertEquals(listOf("Renamed", "Second"), library.playlists().map { it.name })
        assertEquals(listOf(2), library.playlist("b")!!.movieIds)
        assertEquals(listOf("Renamed", "Second"), library.playlistsContaining("movie", 2).map { it.name })

        library.removePlaylist("a")
        assertEquals(listOf("Second"), library.playlists().map { it.name })
        assertNull(library.playlist("a"))
    }

    @Test
    fun defaultGroupIsNeverStored() {
        library.savePlaylist(FavoriteGroup(id = "default", name = "Favorites", isDefault = true, movieIds = mutableListOf(1)))
        assertTrue(library.playlists().isEmpty())
    }

    @Test
    fun playlistContentsAreNewestFirst() {
        listOf(1, 2, 3).forEach { library.addFavorite(item(it)) }
        library.savePlaylist(FavoriteGroup(id = "p", name = "P"))
        library.addToPlaylist("p", "movie", 2); now++
        library.addToPlaylist("p", "movie", 1); now++
        library.addToPlaylist("p", "movie", 3); now++
        assertEquals(listOf(3, 1, 2), library.favoritesInPlaylist("p").map { it.id })
    }

    @Test
    fun watchedEpisodes() {
        library.markWatched(WatchedEpisode(1, 2, 3, watchedAt = 42))
        assertTrue(library.isWatched(1, 2, 3))
        assertEquals(listOf(WatchedEpisode(1, 2, 3, 42)), library.watchedEpisodes())
        library.unmarkWatched(1, 2, 3)
        assertFalse(library.isWatched(1, 2, 3))
        library.markWatched(WatchedEpisode(4, 5, 6, watchedAt = 1))
        library.clearWatched()
        assertTrue(library.watchedEpisodes().isEmpty())
    }

    @Test
    fun progressFollowsThePolicy() {
        val key = SyncKeys.movieProgress(7)
        assertNull("nothing before 30 s", library.recordProgress(key, 10_000, 6_000_000))
        assertNull(library.progress(key))

        library.recordProgress(key, 2_530_000, 6_000_000)
        assertEquals(2_530_000L, ProgressPolicy.resumePosition(library.progress(key)))

        // Going back under 30 s (seeking to the start) leaves the stored position alone
        library.recordProgress(key, 5_000, 6_000_000)
        assertEquals(2_530_000L, library.progress(key)!!.positionMs)

        library.recordProgress(key, 5_900_000, 6_000_000)
        assertTrue(library.progress(key)!!.completed)
        assertNull(ProgressPolicy.resumePosition(library.progress(key)))
    }

    @Test
    fun progressIsPrunedFinishedFirst() {
        repeat(ProgressPolicy.MAX_RECORDS) { index ->
            now++
            if (index < 10) {
                library.recordProgress(SyncKeys.movieProgress(index), 99_000, 100_000) // finished
            } else {
                library.recordProgress(SyncKeys.movieProgress(index), 60_000, 100_000)
            }
        }
        now++
        library.recordProgress(SyncKeys.movieProgress(1_000), 60_000, 100_000)
        val records = library.records.all(SyncCollection.PROGRESS)
        assertEquals(ProgressPolicy.MAX_RECORDS, records.size)
        assertNull("the oldest finished one goes first", library.records.get(SyncCollection.PROGRESS, SyncKeys.movieProgress(0)))
        assertTrue(library.progress(SyncKeys.movieProgress(10)) != null)
    }

    @Test
    fun defaultSettingsAreNotStoredUntilChanged() {
        val default = JsonPrimitive(SettingsMapping.DEFAULT_TEXT_COLOR)
        library.setSetting(SyncSetting.SUBTITLE_TEXT_COLOR, default, default)
        assertNull(library.settingString(SyncSetting.SUBTITLE_TEXT_COLOR))

        library.setSetting(SyncSetting.SUBTITLE_TEXT_COLOR, JsonPrimitive("red"), default)
        assertEquals("red", library.settingString(SyncSetting.SUBTITLE_TEXT_COLOR))

        // Changing back to the default is a real change and is stored
        now++
        library.setSetting(SyncSetting.SUBTITLE_TEXT_COLOR, default, default)
        assertEquals("yellow", library.settingString(SyncSetting.SUBTITLE_TEXT_COLOR))

        library.setSetting(SyncSetting.SEEK_INTERVAL, JsonPrimitive(15), JsonPrimitive(10))
        assertEquals(15, library.settingInt(SyncSetting.SEEK_INTERVAL))
    }

    @Test
    fun unchangedSettingDoesNotBumpTheRecord() {
        library.setSetting(SyncSetting.FONT, JsonPrimitive("vazirmatn"), JsonPrimitive("system"))
        val first = library.records.get(SyncCollection.SETTING, SyncSetting.FONT)!!.t
        now += 100
        library.setSetting(SyncSetting.FONT, JsonPrimitive("vazirmatn"), JsonPrimitive("system"))
        assertEquals(first, library.records.get(SyncCollection.SETTING, SyncSetting.FONT)!!.t)
    }
}
