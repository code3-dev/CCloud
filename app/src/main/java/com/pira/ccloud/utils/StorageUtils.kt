package com.pira.ccloud.utils

import android.content.Context
import android.util.Log
import com.pira.ccloud.data.model.FavoriteGroup
import com.pira.ccloud.data.model.FavoriteItem
import com.pira.ccloud.data.model.Movie
import com.pira.ccloud.data.model.Series
import com.pira.ccloud.data.model.SubtitleSettings
import com.pira.ccloud.data.model.VideoPlayerSettings
import com.pira.ccloud.data.model.FontSettings
import com.pira.ccloud.data.model.WatchedEpisode
import com.pira.ccloud.data.model.sync.ProgressValue
import com.pira.ccloud.sync.LibraryStore
import com.pira.ccloud.sync.SettingsMapping
import com.pira.ccloud.sync.SyncCollection
import com.pira.ccloud.sync.SyncLibrary
import com.pira.ccloud.sync.SyncSetting
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * The app's storage. Favorites, playlists (favorite groups), watched episodes, playback positions
 * and the synced settings live in [LibraryStore] as sync records; the functions here keep their
 * old signatures and delegate to it. Settings that don't sync (like the subtitle size) and the
 * detail-screen cache stay in their own files.
 */
object StorageUtils {
    private const val TAG = "StorageUtils"
    private const val DEFAULT_GROUP_ID = "default"

    private fun library(context: Context): LibraryStore = SyncLibrary.get(context)

    /** Increases whenever [collection] (a [SyncCollection]) changes, on this device or by sync. */
    fun changes(context: Context, collection: String): StateFlow<Int> =
        library(context).records.changes(collection)

    // Favorites functions

    fun saveFavorite(context: Context, favorite: FavoriteItem) {
        try {
            library(context).addFavorite(favorite)
            Log.d(TAG, "Favorite saved: ${favorite.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving favorite", e)
        }
    }

    /** Removes the favorite and takes it out of every playlist. */
    fun removeFavorite(context: Context, id: Int, type: String) {
        try {
            library(context).removeFavorite(type, id)
            Log.d(TAG, "Favorite removed: $id ($type)")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing favorite", e)
        }
    }

    /** Removes every favorite. Playlists stay, but become empty. */
    fun clearAllFavorites(context: Context) {
        try {
            library(context).clearFavorites()
            Log.d(TAG, "All favorites cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all favorites", e)
        }
    }

    fun isFavorite(context: Context, id: Int, type: String): Boolean {
        return try {
            library(context).isFavorite(type, id)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if favorite exists", e)
            false
        }
    }

    /** Newest first. */
    fun loadAllFavorites(context: Context): List<FavoriteItem> {
        return try {
            library(context).favorites()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading all favorites", e)
            emptyList()
        }
    }

    // Function to save favorite to movie or series database when navigating to it
    fun saveFavoriteToDatabase(context: Context, favorite: FavoriteItem) {
        try {
            when (favorite.type) {
                "movie" -> {
                    // Convert FavoriteItem to Movie
                    val movie = Movie(
                        id = favorite.id,
                        type = favorite.type,
                        title = favorite.title,
                        description = favorite.description,
                        year = favorite.year,
                        imdb = favorite.imdb,
                        rating = favorite.rating,
                        duration = favorite.duration,
                        image = favorite.image,
                        cover = favorite.cover,
                        genres = favorite.genres,
                        sources = favorite.sources, // Include sources from favorites
                        country = favorite.country
                    )
                    saveMovieToFile(context, movie)
                }
                "series" -> {
                    // Convert FavoriteItem to Series
                    val series = Series(
                        id = favorite.id,
                        type = favorite.type,
                        title = favorite.title,
                        description = favorite.description,
                        year = favorite.year,
                        imdb = favorite.imdb,
                        rating = favorite.rating,
                        duration = favorite.duration,
                        image = favorite.image,
                        cover = favorite.cover,
                        genres = favorite.genres,
                        country = favorite.country
                    )
                    saveSeriesToFile(context, series)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving favorite to database", e)
        }
    }
    
    fun saveMovieToFile(context: Context, movie: Movie) {
        try {
            // Clear all existing movie files first
            clearAllMovies(context)
            
            val jsonString = Json.encodeToString(movie)
            val fileName = "movie_${movie.id}.json"
            val file = File(context.filesDir, fileName)
            file.writeText(jsonString)
            Log.d(TAG, "Movie saved to file: $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving movie to file", e)
        }
    }
    
    fun loadMovieFromFile(context: Context, movieId: Int): Movie? {
        return try {
            val fileName = "movie_$movieId.json"
            val file = File(context.filesDir, fileName)
            if (file.exists()) {
                val jsonString = file.readText()
                Json.decodeFromString<Movie>(jsonString)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading movie from file", e)
            null
        }
    }
    
    /**
     * Delete all movie files from storage
     */
    fun clearAllMovies(context: Context) {
        try {
            val filesDir = context.filesDir
            val movieFiles = filesDir.listFiles { file ->
                file.name.startsWith("movie_") && file.name.endsWith(".json")
            }
            
            movieFiles?.forEach { file ->
                file.delete()
                Log.d(TAG, "Deleted movie file: ${file.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all movies", e)
        }
    }
    
    // Series functions
    fun saveSeriesToFile(context: Context, series: Series) {
        try {
            // Clear all existing series files first
            clearAllSeries(context)
            
            val jsonString = Json.encodeToString(series)
            val fileName = "series_${series.id}.json"
            val file = File(context.filesDir, fileName)
            file.writeText(jsonString)
            Log.d(TAG, "Series saved to file: $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving series to file", e)
        }
    }
    
    fun loadSeriesFromFile(context: Context, seriesId: Int): Series? {
        return try {
            val fileName = "series_$seriesId.json"
            val file = File(context.filesDir, fileName)
            if (file.exists()) {
                val jsonString = file.readText()
                Json.decodeFromString<Series>(jsonString)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading series from file", e)
            null
        }
    }
    
    /**
     * Delete all series files from storage
     */
    fun clearAllSeries(context: Context) {
        try {
            val filesDir = context.filesDir
            val seriesFiles = filesDir.listFiles { file ->
                file.name.startsWith("series_") && file.name.endsWith(".json")
            }
            
            seriesFiles?.forEach { file ->
                file.delete()
                Log.d(TAG, "Deleted series file: ${file.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all series", e)
        }
    }
    
    // Group functions. The default group isn't stored: it always holds every favorite.

    private fun defaultGroup() = FavoriteGroup(id = DEFAULT_GROUP_ID, name = "Favorites", isDefault = true)

    fun saveFavoriteGroup(context: Context, group: FavoriteGroup) {
        try {
            library(context).savePlaylist(group)
            Log.d(TAG, "Favorite group saved: ${group.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving favorite group", e)
        }
    }

    fun removeFavoriteGroup(context: Context, groupId: String) {
        if (groupId == DEFAULT_GROUP_ID) return
        try {
            library(context).removePlaylist(groupId)
            Log.d(TAG, "Favorite group removed: $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing favorite group", e)
        }
    }

    /** The default group first, then the playlists in the order they were created. */
    fun loadAllFavoriteGroups(context: Context): List<FavoriteGroup> {
        return try {
            listOf(defaultGroup()) + library(context).playlists()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading all favorite groups", e)
            listOf(defaultGroup())
        }
    }

    fun getDefaultGroup(context: Context): FavoriteGroup = defaultGroup()

    fun addFavoriteToGroup(context: Context, groupId: String, favoriteId: Int, type: String) {
        if (groupId == DEFAULT_GROUP_ID) return
        try {
            library(context).addToPlaylist(groupId, type, favoriteId)
            Log.d(TAG, "Favorite added to group: $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding favorite to group", e)
        }
    }

    fun removeFavoriteFromGroup(context: Context, groupId: String, favoriteId: Int, type: String) {
        if (groupId == DEFAULT_GROUP_ID) return
        try {
            library(context).removeFromPlaylist(groupId, type, favoriteId)
            Log.d(TAG, "Favorite removed from group: $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing favorite from group", e)
        }
    }

    /** The playlists (not the default group) that contain a favorite. */
    fun getGroupsForFavorite(context: Context, favoriteId: Int, type: String): List<FavoriteGroup> {
        return try {
            library(context).playlistsContaining(type, favoriteId)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting groups for favorite", e)
            emptyList()
        }
    }

    fun isFavoriteInGroup(context: Context, groupId: String, favoriteId: Int, type: String): Boolean {
        if (groupId == DEFAULT_GROUP_ID) return isFavorite(context, favoriteId, type)
        return try {
            library(context).isInPlaylist(groupId, type, favoriteId)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if favorite is in group", e)
            false
        }
    }

    /** The favorites in a group, most recently added first. */
    fun getFavoritesInGroup(context: Context, groupId: String): List<FavoriteItem> {
        if (groupId == DEFAULT_GROUP_ID) return loadAllFavorites(context)
        return try {
            library(context).favoritesInPlaylist(groupId)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting favorites in group", e)
            emptyList()
        }
    }

    // Settings. Each settings file holds every field; the synced fields are also stored as sync
    // records, and the records win when loading, since another device may have changed them.

    private inline fun <reified T> readSettingsFile(context: Context, name: String): T? {
        val file = File(context.filesDir, name)
        return if (file.exists()) Json.decodeFromString<T>(file.readText()) else null
    }

    private inline fun <reified T> writeSettingsFile(context: Context, name: String, settings: T) {
        File(context.filesDir, name).writeText(Json.encodeToString(settings))
    }

    // Subtitle settings functions. The text and background colors sync; the size doesn't.
    fun saveSubtitleSettings(context: Context, settings: SubtitleSettings) {
        try {
            writeSettingsFile(context, "subtitle_settings.json", settings)
            val library = library(context)
            SettingsMapping.textColorName(settings.textColor)?.let {
                library.setSetting(SyncSetting.SUBTITLE_TEXT_COLOR, JsonPrimitive(it), JsonPrimitive(SettingsMapping.DEFAULT_TEXT_COLOR))
            }
            SettingsMapping.backgroundName(settings.borderColor)?.let {
                library.setSetting(SyncSetting.SUBTITLE_BACKGROUND, JsonPrimitive(it), JsonPrimitive(SettingsMapping.DEFAULT_BACKGROUND))
            }
            Log.d(TAG, "Subtitle settings saved to file")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving subtitle settings to file", e)
        }
    }

    fun loadSubtitleSettings(context: Context): SubtitleSettings {
        val local = try {
            readSettingsFile<SubtitleSettings>(context, "subtitle_settings.json")
                ?: SubtitleSettings.getDefaultSettings(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading subtitle settings from file", e)
            SubtitleSettings.getDefaultSettings(context)
        }
        return try {
            val library = library(context)
            local.copy(
                textColor = library.settingString(SyncSetting.SUBTITLE_TEXT_COLOR)
                    ?.let(SettingsMapping::textColorArgb) ?: local.textColor,
                borderColor = library.settingString(SyncSetting.SUBTITLE_BACKGROUND)
                    ?.let(SettingsMapping::backgroundArgb) ?: local.borderColor
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading synced subtitle settings", e)
            local
        }
    }

    // Video player settings functions
    fun saveVideoPlayerSettings(context: Context, settings: VideoPlayerSettings) {
        try {
            writeSettingsFile(context, "video_player_settings.json", settings)
            SettingsMapping.seekInterval(settings.seekTimeSeconds)?.let {
                library(context).setSetting(SyncSetting.SEEK_INTERVAL, JsonPrimitive(it), JsonPrimitive(SettingsMapping.DEFAULT_SEEK_INTERVAL))
            }
            Log.d(TAG, "Video player settings saved to file")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving video player settings to file", e)
        }
    }

    fun loadVideoPlayerSettings(context: Context): VideoPlayerSettings {
        val local = try {
            readSettingsFile<VideoPlayerSettings>(context, "video_player_settings.json") ?: VideoPlayerSettings.DEFAULT
        } catch (e: Exception) {
            Log.e(TAG, "Error loading video player settings from file", e)
            VideoPlayerSettings.DEFAULT
        }
        return try {
            library(context).settingInt(SyncSetting.SEEK_INTERVAL)
                ?.let(SettingsMapping::seekInterval)
                ?.let { local.copy(seekTimeSeconds = it) }
                ?: local
        } catch (e: Exception) {
            Log.e(TAG, "Error loading synced video player settings", e)
            local
        }
    }

    // Font settings functions
    fun saveFontSettings(context: Context, settings: FontSettings) {
        try {
            writeSettingsFile(context, "font_settings.json", settings)
            library(context).setSetting(
                SyncSetting.FONT,
                JsonPrimitive(SettingsMapping.fontName(settings.fontType)),
                JsonPrimitive(SettingsMapping.DEFAULT_FONT)
            )
            Log.d(TAG, "Font settings saved to file")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving font settings to file", e)
        }
    }

    fun loadFontSettings(context: Context): FontSettings {
        val local = try {
            readSettingsFile<FontSettings>(context, "font_settings.json") ?: FontSettings.DEFAULT
        } catch (e: Exception) {
            Log.e(TAG, "Error loading font settings from file", e)
            FontSettings.DEFAULT
        }
        return try {
            library(context).settingString(SyncSetting.FONT)
                ?.let(SettingsMapping::fontType)
                ?.let { local.copy(fontType = it) }
                ?: local
        } catch (e: Exception) {
            Log.e(TAG, "Error loading synced font settings", e)
            local
        }
    }

    // Welcome screen state functions
    fun saveWelcomeCompleted(context: Context) {
        try {
            val file = File(context.filesDir, "welcome_completed.flag")
            file.writeText("completed")
            Log.d(TAG, "Welcome screen marked as completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving welcome completed state", e)
        }
    }
    
    fun isWelcomeCompleted(context: Context): Boolean {
        return try {
            val file = File(context.filesDir, "welcome_completed.flag")
            file.exists()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking welcome completed state", e)
            false
        }
    }
    
    // Watched episodes functions
    fun saveWatchedEpisode(context: Context, watchedEpisode: WatchedEpisode) {
        try {
            library(context).markWatched(watchedEpisode)
            Log.d(TAG, "Watched episode saved: Series ${watchedEpisode.seriesId}, Season ${watchedEpisode.seasonId}, Episode ${watchedEpisode.episodeId}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving watched episode", e)
        }
    }

    fun removeWatchedEpisode(context: Context, seriesId: Int, seasonId: Int, episodeId: Int) {
        try {
            library(context).unmarkWatched(seriesId, seasonId, episodeId)
            Log.d(TAG, "Watched episode removed: Series $seriesId, Season $seasonId, Episode $episodeId")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing watched episode", e)
        }
    }

    fun isEpisodeWatched(context: Context, seriesId: Int, seasonId: Int, episodeId: Int): Boolean {
        return try {
            library(context).isWatched(seriesId, seasonId, episodeId)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if episode is watched", e)
            false
        }
    }

    fun loadAllWatchedEpisodes(context: Context): List<WatchedEpisode> {
        return try {
            library(context).watchedEpisodes()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading all watched episodes", e)
            emptyList()
        }
    }

    fun clearAllWatchedEpisodes(context: Context) {
        try {
            library(context).clearWatched()
            Log.d(TAG, "All watched episodes cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all watched episodes", e)
        }
    }

    /**
     * Bytes the watched-episodes history takes on disk, or 0 when nothing is marked (cleared marks
     * stay on disk as deletions until they have synced).
     */
    fun watchedEpisodesStorageSize(context: Context): Long {
        return try {
            val records = library(context).records
            if (records.live(SyncCollection.WATCHED).isEmpty()) 0L else records.fileSize(SyncCollection.WATCHED)
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating watched episodes size", e)
            0L
        }
    }

    // Playback positions. Keys come from SyncKeys.movieProgress / episodeProgress.

    fun loadPlaybackProgress(context: Context, key: String): ProgressValue? {
        return try {
            library(context).progress(key)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading playback progress", e)
            null
        }
    }

    /** Saves a position under ProgressPolicy (nothing before 30 s; finished from 95 %). */
    fun savePlaybackProgress(context: Context, key: String, positionMs: Long, durationMs: Long): ProgressValue? {
        return try {
            library(context).recordProgress(key, positionMs, durationMs)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving playback progress", e)
            null
        }
    }
}
