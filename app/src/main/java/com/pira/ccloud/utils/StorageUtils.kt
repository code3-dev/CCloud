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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Date

object StorageUtils {
    private const val TAG = "StorageUtils"
    
    // Favorites functions - using a single JSON file for all favorites
    fun saveFavorite(context: Context, favorite: FavoriteItem) {
        try {
            val favorites = loadAllFavorites(context).toMutableList()
            
            // Remove existing favorite with same id and type if it exists
            favorites.removeAll { it.id == favorite.id && it.type == favorite.type }
            
            // Add the new favorite at the beginning of the list (newest first)
            favorites.add(0, favorite)
            
            // Save all favorites to a single file
            val jsonString = Json.encodeToString(favorites)
            val file = File(context.filesDir, "favorites.json")
            file.writeText(jsonString)
            Log.d(TAG, "Favorite saved: ${favorite.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving favorite", e)
        }
    }
    
    fun removeFavorite(context: Context, id: Int, type: String) {
        try {
            val favorites = loadAllFavorites(context).toMutableList()
            
            // Remove the favorite with matching id and type
            favorites.removeAll { it.id == id && it.type == type }
            
            // Save updated favorites list
            val jsonString = Json.encodeToString(favorites)
            val file = File(context.filesDir, "favorites.json")
            file.writeText(jsonString)
            Log.d(TAG, "Favorite removed: $id ($type)")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing favorite", e)
        }
    }
    
    fun clearAllFavorites(context: Context) {
        try {
            val file = File(context.filesDir, "favorites.json")
            if (file.exists()) {
                file.delete()
            }
            Log.d(TAG, "All favorites cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all favorites", e)
        }
    }
    
    fun isFavorite(context: Context, id: Int, type: String): Boolean {
        return try {
            val favorites = loadAllFavorites(context)
            favorites.any { it.id == id && it.type == type }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if favorite exists", e)
            false
        }
    }
    
    fun loadAllFavorites(context: Context): List<FavoriteItem> {
        return try {
            val file = File(context.filesDir, "favorites.json")
            if (file.exists()) {
                val jsonString = file.readText()
                Json.decodeFromString<List<FavoriteItem>>(jsonString)
            } else {
                emptyList()
            }
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
    
    // Group functions
    fun saveFavoriteGroup(context: Context, group: FavoriteGroup) {
        try {
            val groups = loadAllFavoriteGroups(context).toMutableList()
            
            // Remove existing group with same id if it exists
            groups.removeAll { it.id == group.id }
            
            // Add the new group
            groups.add(group)
            
            // Save all groups to a single file
            val jsonString = Json.encodeToString(groups)
            val file = File(context.filesDir, "favorite_groups.json")
            file.writeText(jsonString)
            Log.d(TAG, "Favorite group saved: ${group.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving favorite group", e)
        }
    }
    
    fun removeFavoriteGroup(context: Context, groupId: String) {
        try {
            val groups = loadAllFavoriteGroups(context).toMutableList()
            
            // Remove the group with matching id (but don't allow removing default group)
            groups.removeAll { it.id == groupId && !it.isDefault }
            
            // Save updated groups list
            val jsonString = Json.encodeToString(groups)
            val file = File(context.filesDir, "favorite_groups.json")
            file.writeText(jsonString)
            Log.d(TAG, "Favorite group removed: $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing favorite group", e)
        }
    }
    
    fun loadAllFavoriteGroups(context: Context): List<FavoriteGroup> {
        return try {
            val file = File(context.filesDir, "favorite_groups.json")
            if (file.exists()) {
                val jsonString = file.readText()
                Json.decodeFromString<List<FavoriteGroup>>(jsonString)
            } else {
                // Return a default group if no groups exist
                listOf(FavoriteGroup(id = "default", name = "Favorites", isDefault = true))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading all favorite groups", e)
            // Return a default group if there's an error
            listOf(FavoriteGroup(id = "default", name = "Favorites", isDefault = true))
        }
    }
    
    fun getDefaultGroup(context: Context): FavoriteGroup {
        val groups = loadAllFavoriteGroups(context)
        return groups.find { it.isDefault } ?: FavoriteGroup(id = "default", name = "Favorites", isDefault = true)
    }
    
    fun addFavoriteToGroup(context: Context, groupId: String, favoriteId: Int, type: String) {
        try {
            val groups = loadAllFavoriteGroups(context).toMutableList()
            val group = groups.find { it.id == groupId }
            
            if (group != null) {
                // Add to the specified group (allowing multiple groups)
                if (type == "movie") {
                    group.addMovie(favoriteId)
                } else if (type == "series") {
                    group.addSeries(favoriteId)
                }
                
                // Save updated groups
                val jsonString = Json.encodeToString(groups)
                val file = File(context.filesDir, "favorite_groups.json")
                file.writeText(jsonString)
                Log.d(TAG, "Favorite added to group: $groupId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding favorite to group", e)
        }
    }
    
    fun removeFavoriteFromGroup(context: Context, groupId: String, favoriteId: Int, type: String) {
        try {
            val groups = loadAllFavoriteGroups(context).toMutableList()
            val group = groups.find { it.id == groupId }
            
            if (group != null) {
                if (type == "movie") {
                    group.removeMovie(favoriteId)
                } else if (type == "series") {
                    group.removeSeries(favoriteId)
                }
                
                // Save updated groups
                val jsonString = Json.encodeToString(groups)
                val file = File(context.filesDir, "favorite_groups.json")
                file.writeText(jsonString)
                Log.d(TAG, "Favorite removed from group: $groupId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing favorite from group", e)
        }
    }
    
    // New function to get all groups for a specific favorite
    fun getGroupsForFavorite(context: Context, favoriteId: Int, type: String): List<FavoriteGroup> {
        return try {
            val groups = loadAllFavoriteGroups(context)
            groups.filter { group ->
                if (type == "movie") {
                    group.containsMovie(favoriteId)
                } else if (type == "series") {
                    group.containsSeries(favoriteId)
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting groups for favorite", e)
            emptyList()
        }
    }
    
    // New function to check if a favorite is in a specific group
    fun isFavoriteInGroup(context: Context, groupId: String, favoriteId: Int, type: String): Boolean {
        return try {
            val groups = loadAllFavoriteGroups(context)
            val group = groups.find { it.id == groupId }
            if (group != null) {
                if (type == "movie") {
                    group.containsMovie(favoriteId)
                } else if (type == "series") {
                    group.containsSeries(favoriteId)
                } else {
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if favorite is in group", e)
            false
        }
    }
    
    fun getFavoritesInGroup(context: Context, groupId: String): List<FavoriteItem> {
        return try {
            val allFavorites = loadAllFavorites(context)
            val groups = loadAllFavoriteGroups(context)
            val group = groups.find { it.id == groupId }
            
            if (group != null) {
                val movieFavorites = allFavorites.filter { favorite ->
                    favorite.type == "movie" && group.movieIds.contains(favorite.id)
                }
                
                val seriesFavorites = allFavorites.filter { favorite ->
                    favorite.type == "series" && group.seriesIds.contains(favorite.id)
                }
                
                // Combine and sort by insertion order (newest first)
                (movieFavorites + seriesFavorites).sortedByDescending { favorite ->
                    // We don't have timestamps, so we'll just return them as is
                    favorite.id
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting favorites in group", e)
            emptyList()
        }
    }
    
    // Subtitle settings functions
    fun saveSubtitleSettings(context: Context, settings: SubtitleSettings) {
        try {
            val jsonString = Json.encodeToString(settings)
            val file = File(context.filesDir, "subtitle_settings.json")
            file.writeText(jsonString)
            Log.d(TAG, "Subtitle settings saved to file")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving subtitle settings to file", e)
        }
    }
    
    fun loadSubtitleSettings(context: Context): SubtitleSettings {
        return try {
            val file = File(context.filesDir, "subtitle_settings.json")
            if (file.exists()) {
                val jsonString = file.readText()
                Json.decodeFromString<SubtitleSettings>(jsonString)
            } else {
                SubtitleSettings.getDefaultSettings(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading subtitle settings from file", e)
            SubtitleSettings.getDefaultSettings(context)
        }
    }
    
    // Video player settings functions
    fun saveVideoPlayerSettings(context: Context, settings: VideoPlayerSettings) {
        try {
            val jsonString = Json.encodeToString(settings)
            val file = File(context.filesDir, "video_player_settings.json")
            file.writeText(jsonString)
            Log.d(TAG, "Video player settings saved to file")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving video player settings to file", e)
        }
    }
    
    fun loadVideoPlayerSettings(context: Context): VideoPlayerSettings {
        return try {
            val file = File(context.filesDir, "video_player_settings.json")
            if (file.exists()) {
                val jsonString = file.readText()
                Json.decodeFromString<VideoPlayerSettings>(jsonString)
            } else {
                VideoPlayerSettings.DEFAULT
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading video player settings from file", e)
            VideoPlayerSettings.DEFAULT
        }
    }
    
    // Font settings functions
    fun saveFontSettings(context: Context, settings: FontSettings) {
        try {
            val jsonString = Json.encodeToString(settings)
            val file = File(context.filesDir, "font_settings.json")
            file.writeText(jsonString)
            Log.d(TAG, "Font settings saved to file")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving font settings to file", e)
        }
    }
    
    fun loadFontSettings(context: Context): FontSettings {
        return try {
            val file = File(context.filesDir, "font_settings.json")
            if (file.exists()) {
                val jsonString = file.readText()
                Json.decodeFromString<FontSettings>(jsonString)
            } else {
                FontSettings.DEFAULT
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading font settings from file", e)
            FontSettings.DEFAULT
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
            val watchedEpisodes = loadAllWatchedEpisodes(context).toMutableList()
            
            // Remove existing entry for the same episode if it exists
            watchedEpisodes.removeAll { 
                it.seriesId == watchedEpisode.seriesId && 
                it.seasonId == watchedEpisode.seasonId && 
                it.episodeId == watchedEpisode.episodeId 
            }
            
            // Add the new watched episode
            watchedEpisodes.add(watchedEpisode)
            
            // Save all watched episodes to a single file
            val jsonString = Json.encodeToString(watchedEpisodes)
            val file = File(context.filesDir, "watched_episodes.json")
            file.writeText(jsonString)
            Log.d(TAG, "Watched episode saved: Series ${watchedEpisode.seriesId}, Season ${watchedEpisode.seasonId}, Episode ${watchedEpisode.episodeId}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving watched episode", e)
        }
    }
    
    fun removeWatchedEpisode(context: Context, seriesId: Int, seasonId: Int, episodeId: Int) {
        try {
            val watchedEpisodes = loadAllWatchedEpisodes(context).toMutableList()
            
            // Remove the watched episode with matching ids
            watchedEpisodes.removeAll { 
                it.seriesId == seriesId && 
                it.seasonId == seasonId && 
                it.episodeId == episodeId 
            }
            
            // Save updated watched episodes list
            val jsonString = Json.encodeToString(watchedEpisodes)
            val file = File(context.filesDir, "watched_episodes.json")
            file.writeText(jsonString)
            Log.d(TAG, "Watched episode removed: Series $seriesId, Season $seasonId, Episode $episodeId")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing watched episode", e)
        }
    }
    
    fun isEpisodeWatched(context: Context, seriesId: Int, seasonId: Int, episodeId: Int): Boolean {
        return try {
            val watchedEpisodes = loadAllWatchedEpisodes(context)
            watchedEpisodes.any { 
                it.seriesId == seriesId && 
                it.seasonId == seasonId && 
                it.episodeId == episodeId 
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if episode is watched", e)
            false
        }
    }
    
    fun loadAllWatchedEpisodes(context: Context): List<WatchedEpisode> {
        return try {
            val file = File(context.filesDir, "watched_episodes.json")
            if (file.exists()) {
                val jsonString = file.readText()
                Json.decodeFromString<List<WatchedEpisode>>(jsonString)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading all watched episodes", e)
            emptyList()
        }
    }
    
    fun clearAllWatchedEpisodes(context: Context) {
        try {
            val file = File(context.filesDir, "watched_episodes.json")
            if (file.exists()) {
                file.delete()
            }
            Log.d(TAG, "All watched episodes cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all watched episodes", e)
        }
    }
}