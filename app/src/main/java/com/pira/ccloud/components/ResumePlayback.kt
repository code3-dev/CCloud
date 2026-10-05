package com.pira.ccloud.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pira.ccloud.VideoPlayerActivity
import com.pira.ccloud.sync.ProgressPolicy
import com.pira.ccloud.sync.android.SyncService
import com.pira.ccloud.utils.StorageUtils
import kotlinx.coroutines.delay

/** An episode's ids, for marking it watched and keying its playback position. */
data class EpisodeIds(val seriesId: Int, val seasonId: Int, val episodeId: Int)

/** A playback waiting for the user to choose between resuming and starting over. */
data class PendingPlayback(
    val url: String,
    val progressKey: String,
    val episode: EpisodeIds?,
    val resumeAtMs: Long
)

/**
 * Starts playing [url], or returns a [PendingPlayback] when there's a saved position to offer
 * (show it with [ResumePlaybackDialog]). [progressKey] is null for trailers, which never resume.
 *
 * With sync on, it first fetches positions saved on other devices (waiting at most 1.5 s).
 */
suspend fun playOrAskToResume(
    context: Context,
    url: String,
    progressKey: String?,
    episode: EpisodeIds? = null
): PendingPlayback? {
    if (progressKey != null) SyncService.engine(context)?.prepareToPlay()
    val resumeAt = progressKey
        ?.let { StorageUtils.loadPlaybackProgress(context, it) }
        ?.let { ProgressPolicy.resumePosition(it) }
    if (progressKey == null || resumeAt == null) {
        startPlayback(context, url, progressKey, episode, startPositionMs = 0L)
        return null
    }
    return PendingPlayback(url, progressKey, episode, resumeAt)
}

private fun startPlayback(
    context: Context,
    url: String,
    progressKey: String?,
    episode: EpisodeIds?,
    startPositionMs: Long
) {
    if (episode != null) {
        VideoPlayerActivity.startWithEpisodeInfo(
            context, url, episode.seriesId, episode.seasonId, episode.episodeId,
            progressKey = progressKey,
            startPositionMs = startPositionMs
        )
    } else {
        VideoPlayerActivity.start(context, url, progressKey = progressKey, startPositionMs = startPositionMs)
    }
}

/**
 * Asks whether to continue from the saved position or start over. With a remote, "Continue" has
 * focus, so OK resumes. Starting over forgets the saved position.
 */
@Composable
fun ResumePlaybackDialog(pending: PendingPlayback, onDone: () -> Unit) {
    val context = LocalContext.current
    val position = ProgressPolicy.format(pending.resumeAtMs)
    val continueFocus = remember { FocusRequester() }
    val usingRemote = LocalInputModeManager.current.inputMode == InputMode.Keyboard

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Continue watching?") },
        text = {
            Text(
                text = "You stopped at $position.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        onDone()
                        startPlayback(context, pending.url, pending.progressKey, pending.episode, pending.resumeAtMs)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(continueFocus)
                        .focusRing(RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Continue from $position")
                }
                OutlinedButton(
                    onClick = {
                        onDone()
                        StorageUtils.clearPlaybackProgress(context, pending.progressKey)
                        startPlayback(context, pending.url, pending.progressKey, pending.episode, 0L)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRing(RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start over")
                }
                TextButton(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRing(RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Cancel")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp
    )

    // With a remote, start on "Continue" (the dialog's window needs a frame before its content
    // can take focus). With touch, leave focus alone so no outline shows.
    LaunchedEffect(Unit) {
        if (!usingRemote) return@LaunchedEffect
        delay(50)
        try {
            continueFocus.requestFocus()
        } catch (e: Exception) {
            // Focus stays where Android puts it
        }
    }
}
