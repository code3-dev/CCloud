package com.pira.ccloud

import android.content.pm.ActivityInfo
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.pira.ccloud.navigation.AppNavigation
import com.pira.ccloud.navigation.AppScreens
import com.pira.ccloud.navigation.BottomNavigationBar
import com.pira.ccloud.ui.theme.CCloudTheme
import com.pira.ccloud.ui.theme.ThemeManager
import com.pira.ccloud.ui.theme.ThemeSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set default orientation to portrait
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        setContent {
            MainApp()
        }
    }
}

@Composable
fun MainApp() {
    val themeManager = ThemeManager(androidx.compose.ui.platform.LocalContext.current)
    var themeSettings by remember { mutableStateOf(themeManager.loadThemeSettings()) }
    
    // Save theme settings when they change
    LaunchedEffect(themeSettings) {
        themeManager.saveThemeSettings(themeSettings)
    }
    
    CCloudTheme(themeSettings) {
        MainScreen(onThemeSettingsChanged = { themeSettings = it })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onThemeSettingsChanged: (ThemeSettings) -> Unit = {}) {
    val navController = rememberNavController()
    val themeManager = ThemeManager(androidx.compose.ui.platform.LocalContext.current)
    val themeSettings = themeManager.loadThemeSettings()
    
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    
    // Find the current screen, including the SingleMovie screen
    val currentScreen = when {
        currentRoute?.startsWith("single_movie") == true -> AppScreens.SingleMovie
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
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            // Only show top bar if the current screen requires it
            if (currentScreen.showBottomBar) {
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220, delayMillis = 90)) togetherWith
                        fadeOut(animationSpec = tween(90))
                    },
                    label = "topBar"
                ) { screen ->
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                text = stringResource(screen.resourceId),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                            titleContentColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.height(38.dp) // Reduced height for less spacing
                    )
                }
            }
        },
        bottomBar = {
            // Only show bottom bar if the current screen requires it
            if (currentScreen.showBottomBar) {
                BottomNavigationBar(navController)
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            // Pass theme settings callback to navigation
            AppNavigation(navController, onThemeSettingsChanged)
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
