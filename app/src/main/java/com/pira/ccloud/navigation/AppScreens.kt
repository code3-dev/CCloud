package com.pira.ccloud.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tv
import androidx.compose.ui.graphics.vector.ImageVector
import com.pira.ccloud.R

sealed class AppScreens(
    val route: String,
    @StringRes val resourceId: Int,
    val icon: ImageVector? = null,
    val showBottomBar: Boolean = true,
    val showSidebar: Boolean = true
) {
    data object Splash : AppScreens(
        route = "splash",
        resourceId = R.string.app_name
    )

    data object Movies : AppScreens(
        route = "movies",
        resourceId = R.string.movies,
        icon = Icons.Default.Movie
    )

    data object Series : AppScreens(
        route = "series",
        resourceId = R.string.series,
        icon = Icons.Default.Tv
    )

    data object Search : AppScreens(
        route = "search",
        resourceId = R.string.search,
        icon = Icons.Default.Search
    )

    data object Settings : AppScreens(
        route = "settings",
        resourceId = R.string.settings,
        icon = Icons.Default.Settings
    )

    data object SingleMovie : AppScreens(
        route = "single_movie/{movieId}",
        resourceId = R.string.movie_details,
        icon = Icons.Default.Movie,
        showBottomBar = false,
        showSidebar = false
    )
    
    data object SingleSeries : AppScreens(
        route = "single_series/{seriesId}",
        resourceId = R.string.series_details,
        icon = Icons.Default.Tv,
        showBottomBar = false,
        showSidebar = false
    )

    data object Favorites : AppScreens(
        route = "favorites",
        resourceId = R.string.favorites,
        icon = Icons.Default.Favorite,
        showBottomBar = true,
        showSidebar = true
    )

    data object About : AppScreens(
        route = "about",
        resourceId = R.string.about,
        icon = Icons.Default.Info,
        showBottomBar = false,
        showSidebar = false
    )
    
    data object Country : AppScreens(
        route = "country/{countryId}",
        resourceId = R.string.country,
        showBottomBar = false,
        showSidebar = false
    )

    // Settings → Sync; "code" comes from a scanned ccloud://pair link
    data object Sync : AppScreens(
        route = "sync?code={code}",
        resourceId = R.string.sync,
        icon = Icons.Default.Sync,
        showBottomBar = false,
        showSidebar = false
    )

    companion object {
        val screens = listOf(Movies, Series, Search, Favorites, Settings)
    }
}