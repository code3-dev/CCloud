package com.pira.ccloud

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.pira.ccloud.components.ScreenFocusHost
import com.pira.ccloud.navigation.AppNavigation
import com.pira.ccloud.navigation.AppScreens
import com.pira.ccloud.navigation.BottomNavigationBar
import com.pira.ccloud.navigation.SidebarNavigation
import com.pira.ccloud.ui.theme.CCloudTheme
import com.pira.ccloud.ui.theme.ThemeManager
import com.pira.ccloud.ui.theme.ThemeSettings
import com.pira.ccloud.utils.StorageUtils
import com.pira.ccloud.sync.SyncCollection
import com.pira.ccloud.data.model.FontSettings
import com.pira.ccloud.utils.DeviceUtils

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set default orientation to portrait for mobile/tablet
        // For TV, we don't set orientation as it's typically fixed
        if (!DeviceUtils.isTv(this)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        
        // Only use edge-to-edge on supported versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            enableEdgeToEdge()
        }
        
        setContent {
            MainApp()
        }
    }
}

@Composable
fun MainApp() {
    val themeManager = ThemeManager(androidx.compose.ui.platform.LocalContext.current)
    var themeSettings by remember { mutableStateOf(themeManager.loadThemeSettings()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var fontSettings by remember { mutableStateOf(StorageUtils.loadFontSettings(context)) }
    // The font is a synced setting, so it can also change on another device
    val syncedSettingsVersion by StorageUtils.changes(context, SyncCollection.SETTING).collectAsState()
    LaunchedEffect(syncedSettingsVersion) {
        fontSettings = StorageUtils.loadFontSettings(context)
    }
    
    // Save theme settings when they change
    LaunchedEffect(themeSettings) {
        themeManager.saveThemeSettings(themeSettings)
    }
    
    CCloudTheme(themeSettings, fontSettings) {
        MainScreen(
            onThemeSettingsChanged = { themeSettings = it },
            onFontSettingsChanged = { fontSettings = it }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onThemeSettingsChanged: (ThemeSettings) -> Unit = {},
    onFontSettingsChanged: (FontSettings) -> Unit = {}
) {
    val navController = rememberNavController()
    val themeManager = ThemeManager(androidx.compose.ui.platform.LocalContext.current)
    val themeSettings = themeManager.loadThemeSettings()
    val context = androidx.compose.ui.platform.LocalContext.current
    val fontSettings = StorageUtils.loadFontSettings(context)
    val configuration = LocalConfiguration.current
    val isTv = DeviceUtils.isTv(androidx.compose.ui.platform.LocalContext.current)
    
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    
    // Find the current screen, including the SingleMovie and SingleSeries screens
    val currentScreen = when {
        currentRoute?.startsWith("single_movie") == true -> AppScreens.SingleMovie
        currentRoute?.startsWith("single_series") == true -> AppScreens.SingleSeries
        currentRoute?.startsWith("country") == true -> AppScreens.Country
        currentRoute == "favorites" -> AppScreens.Favorites
        currentRoute == "about" -> AppScreens.About
        else -> AppScreens.screens.find { it.route == currentRoute } ?: AppScreens.Movies
    }
    
    // System UI controller for edge-to-edge support
    val systemUiController = rememberSystemUiController()
    val useDarkIcons = themeSettings.themeMode != com.pira.ccloud.ui.theme.ThemeMode.DARK
    
    LaunchedEffect(themeSettings) {
        systemUiController.setStatusBarColor(
            color = androidx.compose.ui.graphics.Color.Transparent,
            darkIcons = useDarkIcons
        )
        systemUiController.setNavigationBarColor(
            color = androidx.compose.ui.graphics.Color.Transparent,
            darkIcons = useDarkIcons
        )
    }
    
    // Lets each screen move D-pad focus into itself when it's shown (see AppNavigation)
    ScreenFocusHost {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                // Only show bottom bar if the current screen requires it and we're not on splash
                // and we're not on TV (TV uses sidebar instead)
                if (!isTv && currentScreen.showBottomBar && currentRoute != AppScreens.Splash.route) {
                    BottomNavigationBar(navController)
                }
            }
        ) { innerPadding ->
            if (isTv) {
                // TV layout with sidebar navigation
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    // Sidebar navigation for TV
                    SidebarNavigation(navController)
                    
                    // Main content area with padding to separate from sidebar
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp, end = 24.dp, top = 24.dp, bottom = 24.dp)
                    ) {
                        // Pass theme settings and font settings callback to navigation
                        AppNavigation(navController, onThemeSettingsChanged, onFontSettingsChanged)
                    }
                }
            } else {
                // Mobile/tablet layout with bottom navigation
                Box(modifier = Modifier.padding(innerPadding)) {
                    // Pass theme settings and font settings callback to navigation
                    AppNavigation(navController, onThemeSettingsChanged, onFontSettingsChanged)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    val themeManager = ThemeManager(androidx.compose.ui.platform.LocalContext.current)
    val themeSettings = themeManager.loadThemeSettings()
    
    CCloudTheme(themeSettings) {
        MainScreen()
    }
}