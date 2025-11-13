package com.pira.ccloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import kotlin.math.abs
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.pira.ccloud.data.model.SubtitleSettings
import com.pira.ccloud.data.model.VideoPlayerSettings
import com.pira.ccloud.data.model.FontSettings
import com.pira.ccloud.utils.StorageUtils
import com.pira.ccloud.ui.theme.FontManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Extension function to set subtitle text size on PlayerView
fun PlayerView.setSubtitleTextSize(spSize: Float) {
    // Convert sp to pixels
    val displayMetrics = context.resources.displayMetrics
    val pixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, spSize, displayMetrics)
    
    // Set the subtitle text size
    subtitleView?.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, pixels)
}

// Extension function to set subtitle colors and font
fun PlayerView.setSubtitleColors(settings: SubtitleSettings, typeface: Typeface? = null) {
    // Create a custom CaptionStyleCompat with the typeface
    // Use transparent background as default
    val style = CaptionStyleCompat(
        settings.textColor,
        android.graphics.Color.TRANSPARENT, // Always use transparent background
        settings.borderColor,
        CaptionStyleCompat.EDGE_TYPE_OUTLINE,
        settings.borderColor,
        typeface
    )
    subtitleView?.setStyle(style)
    
    // Note: ExoPlayer's subtitle rendering has limited support for custom fonts.
    // The font may not be applied to all subtitle formats or on all Android versions.
    // This is a known limitation of ExoPlayer's subtitle rendering system.
}

class VideoPlayerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_VIDEO_URL = "video_url"
        const val REQUEST_WRITE_SETTINGS = 1001
        
        fun start(context: Context, videoUrl: String) {
            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_URL, videoUrl)
            }
            context.startActivity(intent)
        }
    }
    
    private var exoPlayer: ExoPlayer? = null
    private var videoUrl: String? = null
    private var playerInitialized = false
    private var isActivityResumed = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set fullscreen landscape mode
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        
        // Enable immersive full-screen mode
        enableFullScreenMode()
        
        // Keep screen on while in video player
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        
        if (videoUrl != null) {
            setContent {
                VideoPlayerScreen(videoUrl!!, this::finish) { player ->
                    exoPlayer = player
                    playerInitialized = true
                }
            }
        } else {
            finish()
        }
    }
    
    // Handle TV remote control key events
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        try {
            exoPlayer?.let { player ->
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER -> {
                        player.playWhenReady = !player.playWhenReady
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        player.playWhenReady = true
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        player.playWhenReady = false
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val newPosition = (player.currentPosition - 10000).coerceAtLeast(0L) // Rewind 10 seconds
                        player.seekTo(newPosition)
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val newPosition = (player.currentPosition + 10000).coerceAtMost(player.duration) // Forward 10 seconds
                        player.seekTo(newPosition)
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_BACK -> {
                        finish()
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore key event errors
        }
        return super.onKeyDown(keyCode, event)
    }
    
    private fun enableFullScreenMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // For Android 11 and above
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                // For Android 4.4 to Android 10
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
            } else {
                // For even older versions
                @Suppress("DEPRECATION")
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        } catch (e: Exception) {
            // Fallback to basic fullscreen if there's an issue
            try {
                @Suppress("DEPRECATION")
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            } catch (e2: Exception) {
                // Ignore fullscreen errors
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            exoPlayer?.release()
        } catch (e: Exception) {
            // Ignore any exceptions during release
        }
        exoPlayer = null
        playerInitialized = false
        
        // Remove keep screen on flag to conserve battery
        try {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            // Ignore flag clear errors
        }
    }
    
    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        // Re-enable full-screen mode when resuming
        try {
            enableFullScreenMode()
        } catch (e: Exception) {
            // Ignore fullscreen errors
        }
        
        // Resume player when activity resumes
        try {
            if (playerInitialized && exoPlayer != null) {
                exoPlayer?.playWhenReady = true
            }
        } catch (e: Exception) {
            // Ignore player resume errors
        }
    }
    
    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        
        // Pause player when activity pauses
        try {
            if (playerInitialized && exoPlayer != null) {
                exoPlayer?.playWhenReady = false
            }
        } catch (e: Exception) {
            // Ignore player pause errors
        }
    }
}

@Composable
fun VideoPlayerScreen(
    videoUrl: String,
    onBack: () -> Unit,
    onPlayerReady: (ExoPlayer) -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }
    var isSeeking by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var isRetrying by remember { mutableStateOf(false) }
    var showForwardIndicator by remember { mutableStateOf(false) }
    var showRewindIndicator by remember { mutableStateOf(false) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var showSpeedDropdown by remember { mutableStateOf(false) }
    var playerInitialized by remember { mutableStateOf(false) }
    
    // Predefined playback speed options
    val speedOptions = remember {
        listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f, 3.5f)
    }
    
    // Load font settings
    val fontSettings = remember(context) {
        try {
            StorageUtils.loadFontSettings(context)
        } catch (e: Exception) {
            com.pira.ccloud.data.model.FontSettings.DEFAULT
        }
    }
    
    // Load custom font typeface
    val customTypeface = remember(fontSettings.fontType) {
        try {
            when (fontSettings.fontType) {
                com.pira.ccloud.data.model.FontType.DEFAULT -> null
                com.pira.ccloud.data.model.FontType.VAZIRMATN -> {
                    try {
                        // Load the Vazirmatn font from assets
                        Typeface.createFromAsset(context.assets, "font/vazirmatn_regular.ttf")
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    // Load video player settings (without affecting playback speed)
    val videoPlayerSettings = remember(context) {
        try {
            StorageUtils.loadVideoPlayerSettings(context)
        } catch (e: Exception) {
            com.pira.ccloud.data.model.VideoPlayerSettings.DEFAULT
        }
    }
    
    // Load subtitle settings
    val subtitleSettings = remember(context) {
        try {
            StorageUtils.loadSubtitleSettings(context)
        } catch (e: Exception) {
            SubtitleSettings.getDefaultSettings(context)
        }
    }
    
    val exoPlayer = remember(context) {
        try {
            ExoPlayer.Builder(context).build().apply {
                try {
                    setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
                    prepare()
                    // If we're retrying, seek to the current position
                    if (isRetrying && currentPosition > 0) {
                        seekTo(currentPosition)
                    }
                    playWhenReady = true // Start playing by default
                    // Set initial playback speed
                    setPlaybackSpeed(playbackSpeed)
                } catch (e: Exception) {
                    // Don't show error, just mark as retrying
                    isRetrying = true
                }
            }
        } catch (e: Exception) {
            // Don't show error, just mark as retrying
            isRetrying = true
            null
        }
    }
    
    // Notify activity of player reference
    LaunchedEffect(Unit) {
        try {
            exoPlayer?.let { onPlayerReady(it) }
            playerInitialized = true
        } catch (e: Exception) {
            // Ignore callback errors
        }
    }
    
    // Update player state
    LaunchedEffect(isPlaying, exoPlayer) {
        try {
            exoPlayer?.playWhenReady = isPlaying
        } catch (e: Exception) {
            // Ignore player state errors
        }
    }
    
    // Update playback speed when it changes
    LaunchedEffect(playbackSpeed, exoPlayer) {
        try {
            exoPlayer?.setPlaybackSpeed(playbackSpeed)
        } catch (e: Exception) {
            // Ignore playback speed errors
        }
    }
    
    // Listen to player events and handle cleanup
    val playerListener = remember(exoPlayer) {
        object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                // Only update isPlaying if we're not currently seeking
                if (!isSeeking) {
                    isPlaying = playing
                }
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                try {
                    if (playbackState == Player.STATE_READY) {
                        duration = exoPlayer?.duration ?: 0L
                    }
                } catch (e: Exception) {
                    // Ignore duration errors
                }
            }
            
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                try {
                    if (!isSeeking) {
                        currentPosition = exoPlayer?.currentPosition ?: 0L
                    }
                } catch (e: Exception) {
                    // Ignore position errors
                }
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Don't show error message, just mark as retrying
                isRetrying = true
                playerError = error.message
                
                // Store current position before retrying
                val retryPosition = currentPosition
                
                // Attempt to retry after a delay
                CoroutineScope(Dispatchers.Main).launch {
                    delay(3000) // Wait 3 seconds before retrying
                    try {
                        exoPlayer?.let { player ->
                            // Retry loading the media
                            player.setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
                            player.prepare()
                            // Seek to the stored position after preparing
                            player.seekTo(retryPosition)
                            player.playWhenReady = true
                            isRetrying = false
                            playerError = null
                        }
                    } catch (e: Exception) {
                        // If retry fails, keep isRetrying true
                    }
                }
            }
        }
    }
    
    LaunchedEffect(exoPlayer) {
        if (exoPlayer == null) return@LaunchedEffect
        
        try {
            exoPlayer.addListener(playerListener)
        } catch (e: Exception) {
            // Ignore listener errors
        }
    }
    
    DisposableEffect(exoPlayer) {
        onDispose {
            try {
                exoPlayer?.removeListener(playerListener)
            } catch (e: Exception) {
                // Ignore listener removal errors
            }
        }
    }
    
    // Periodically update the current position for real-time progress tracking
    LaunchedEffect(exoPlayer, isPlaying) {
        if (exoPlayer == null) return@LaunchedEffect
        
        try {
            while (true) {
                delay(100) // Update every 100ms for smooth progress tracking
                if (isPlaying && !isSeeking) {
                    try {
                        exoPlayer?.let { player ->
                            if (player.isPlaying) {
                                currentPosition = player.currentPosition
                                duration = player.duration
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore position/duration errors
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore coroutine errors
        }
    }
    
    // Hide controls after a delay
    LaunchedEffect(showControls, isPlaying) {
        try {
            if (showControls && isPlaying) {
                delay(3000) // Hide controls after 3 seconds
                showControls = false
            }
        } catch (e: Exception) {
            // Ignore delay errors
        }
    }
    
    // Hide forward/rewind indicators after a delay
    LaunchedEffect(showForwardIndicator) {
        try {
            if (showForwardIndicator) {
                delay(500) // Hide after 500ms
                showForwardIndicator = false
            }
        } catch (e: Exception) {
            // Ignore delay errors
        }
    }
    
    LaunchedEffect(showRewindIndicator) {
        try {
            if (showRewindIndicator) {
                delay(500) // Hide after 500ms
                showRewindIndicator = false
            }
        } catch (e: Exception) {
            // Ignore delay errors
        }
    }
    
    // Clean up player
    DisposableEffect(exoPlayer) {
        onDispose {
            try {
                exoPlayer?.release()
            } catch (e: Exception) {
                // Ignore release errors
            }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                try {
                    detectTapGestures(
                        onDoubleTap = { offset -> 
                            // Calculate if the tap is on the left or right side
                            val screenWidth = size.width
                            val tapX = offset.x
                            
                            // Store the playing state before seeking
                            wasPlayingBeforeSeek = isPlaying
                            isSeeking = true
                            
                            if (tapX < screenWidth / 2) {
                                // Left side - rewind specified seconds
                                try {
                                    exoPlayer?.let { player ->
                                        val seekTimeMs = videoPlayerSettings.seekTimeSeconds * 1000L
                                        val newPosition = (player.currentPosition - seekTimeMs).coerceAtLeast(0L)
                                        player.seekTo(newPosition)
                                        currentPosition = newPosition
                                        showRewindIndicator = true
                                        // Keep the player playing during seeking if it was playing before
                                        if (wasPlayingBeforeSeek) {
                                            player.playWhenReady = true
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Ignore seek errors
                                }
                            } else {
                                // Right side - forward specified seconds
                                try {
                                    exoPlayer?.let { player ->
                                        val seekTimeMs = videoPlayerSettings.seekTimeSeconds * 1000L
                                        val newPosition = (player.currentPosition + seekTimeMs).coerceAtMost(player.duration)
                                        player.seekTo(newPosition)
                                        currentPosition = newPosition
                                        showForwardIndicator = true
                                        // Keep the player playing during seeking if it was playing before
                                        if (wasPlayingBeforeSeek) {
                                            player.playWhenReady = true
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Ignore seek errors
                                }
                            }
                            
                            // Reset seeking state after a short delay using a coroutine scope
                            CoroutineScope(Dispatchers.Main).launch {
                                try {
                                    delay(500) // Reset after 500ms
                                    isSeeking = false
                                    // Restore the playing state after seeking is finished
                                    try {
                                        exoPlayer?.playWhenReady = wasPlayingBeforeSeek
                                        // Update isPlaying state to match the player's actual state
                                        isPlaying = wasPlayingBeforeSeek
                                    } catch (e: Exception) {
                                        // Ignore errors
                                    }
                                } catch (e: Exception) {
                                    // Ignore delay errors
                                }
                            }
                        },
                        onTap = {
                            showControls = !showControls
                            // Reset the auto-hide timer when controls are shown
                            if (showControls && isPlaying) {
                                // The LaunchedEffect above will handle the auto-hide
                            }
                        }
                    )
                } catch (e: Exception) {
                    // Ignore gesture detection errors
                }
            }
    ) {
        // Check if player is initialized
        if (exoPlayer == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Initializing player...",
                    color = Color.White,
                    modifier = Modifier.padding(16.dp)
                )
            }
            return@Box
        }
        
        // Video player
        AndroidView(
            factory = { ctx ->
                try {
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false // We're using our own controls
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        // Make the player view fill the entire screen
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        
                        // Apply subtitle settings to the player view
                        // Set subtitle styling with custom font
                        setSubtitleTextSize(subtitleSettings.textSize)
                        setSubtitleColors(subtitleSettings, customTypeface)
                    }
                } catch (e: Exception) {
                    // Return a simple view if PlayerView fails to initialize
                    View(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { playerView ->
                try {
                    // Update the player view when subtitle settings change
                    // Update subtitle styling when settings change
                    if (playerView is PlayerView) {
                        playerView.setSubtitleTextSize(subtitleSettings.textSize)
                        playerView.setSubtitleColors(subtitleSettings, customTypeface)
                    }
                } catch (e: Exception) {
                    // Ignore update errors
                }
            }
        )
        
        // Rewind indicator
        if (showRewindIndicator) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay,
                        contentDescription = "Rewind ${videoPlayerSettings.seekTimeSeconds} seconds",
                        tint = Color.White,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = "${videoPlayerSettings.seekTimeSeconds}s",
                        color = Color.White,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
        
        // Forward indicator
        if (showForwardIndicator) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Forward,
                        contentDescription = "Forward ${videoPlayerSettings.seekTimeSeconds} seconds",
                        tint = Color.White,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = "${videoPlayerSettings.seekTimeSeconds}s",
                        color = Color.White,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
        
        // Custom controls overlay
        if (showControls) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                // Top bar with back button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.7f),
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                }
                
                // Middle play/pause button
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = { isPlaying = !isPlaying },
                        modifier = Modifier
                            .size(64.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.7f),
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                
                // Bottom controls
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(16.dp)
                ) {
                    // Progress slider with retry animation
                    if (isRetrying) {
                        // Show animated progress bar when retrying
                        androidx.compose.material3.LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )
                    } else {
                        Slider(
                            value = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f,
                            onValueChange = { progress ->
                                // Store the playing state before seeking
                                if (!isSeeking) {
                                    wasPlayingBeforeSeek = isPlaying
                                }
                                isSeeking = true
                                val newPosition = (progress * duration).toLong()
                                try {
                                    exoPlayer?.seekTo(newPosition)
                                    currentPosition = newPosition
                                    // Keep the player playing during seeking if it was playing before
                                    if (wasPlayingBeforeSeek) {
                                        exoPlayer?.playWhenReady = true
                                    }
                                } catch (e: Exception) {
                                    // Ignore seek errors
                                }
                            },
                            onValueChangeFinished = {
                                isSeeking = false
                                // Restore the playing state after seeking is finished
                                try {
                                    exoPlayer?.playWhenReady = wasPlayingBeforeSeek
                                    // Update isPlaying state to match the player's actual state
                                    isPlaying = wasPlayingBeforeSeek
                                } catch (e: Exception) {
                                    // Ignore errors
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    // Time and controls row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(currentPosition),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontManager.loadFontFamily(context, fontSettings.fontType)
                        )
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        // Retry button when there's an error
                        if (isRetrying) {
                            Text(
                                text = "Retrying...",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .clickable { 
                                        // Manual retry
                                        try {
                                            exoPlayer?.let { player ->
                                                // Store current position before retrying
                                                val retryPosition = currentPosition
                                                player.setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
                                                player.prepare()
                                                // Seek to the stored position after preparing
                                                player.seekTo(retryPosition)
                                                player.playWhenReady = true
                                                isRetrying = false
                                                playerError = null
                                            }
                                        } catch (e: Exception) {
                                            // If manual retry fails, keep isRetrying true
                                        }
                                    }
                                    .padding(horizontal = 8.dp)
                            )
                        }
                        
                        // Video speed controls
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clickable { showSpeedDropdown = true }
                                        .padding(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Speed,
                                        contentDescription = "Playback speed",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    
                                    Text(
                                        text = String.format("%.2fx", playbackSpeed),
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                        fontFamily = FontManager.loadFontFamily(context, fontSettings.fontType)
                                    )
                                }
                                
                                DropdownMenu(
                                    expanded = showSpeedDropdown,
                                    onDismissRequest = { showSpeedDropdown = false },
                                    modifier = Modifier.background(Color.Black)
                                ) {
                                    speedOptions.forEach { speed ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = String.format("%.2fx", speed),
                                                    color = if (speed == playbackSpeed) MaterialTheme.colorScheme.primary else Color.White,
                                                    fontFamily = FontManager.loadFontFamily(context, fontSettings.fontType)
                                                )
                                            },
                                            onClick = {
                                                playbackSpeed = speed
                                                showSpeedDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.width(4.dp))
                            
                            // Normal speed button
                            Text(
                                text = "Normal",
                                color = if (playbackSpeed == 1.0f) MaterialTheme.colorScheme.primary else Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (playbackSpeed == 1.0f) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .clickable { playbackSpeed = 1.0f }
                                    .padding(4.dp),
                                fontFamily = FontManager.loadFontFamily(context, fontSettings.fontType)
                            )
                        }
                        
                        Text(
                            text = formatTime(duration),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontManager.loadFontFamily(context, fontSettings.fontType)
                        )
                    }
                }
            }
        }
    }
}

fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).toInt()
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, remainingMinutes, remainingSeconds)
    } else {
        String.format("%02d:%02d", remainingMinutes, remainingSeconds)
    }
}