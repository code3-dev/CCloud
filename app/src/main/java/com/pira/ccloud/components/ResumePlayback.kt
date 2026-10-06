package com.pira.ccloud.components

import android.content.Context
import com.pira.ccloud.VideoPlayerActivity
import com.pira.ccloud.sync.ProgressPolicy
import com.pira.ccloud.sync.android.SyncService
import com.pira.ccloud.utils.StorageUtils

/** An episode's ids, for marking it watched and keying its playback position. */
data class EpisodeIds(val seriesId: Int, val seasonId: Int, val episodeId: Int)

/**
 * Starts playing [url] from its saved position, or from the beginning when there is none (or the
 * title was finished). [progressKey] is null for trailers, which never resume.
 *
 * With sync on, it first fetches positions saved on other devices (waiting at most 1.5 s).
 */
suspend fun playFromSavedPosition(
    context: Context,
    url: String,
    progressKey: String?,
    episode: EpisodeIds? = null
) {
    if (progressKey != null) SyncService.engine(context)?.prepareToPlay()
    val resumeAt = progressKey
        ?.let { StorageUtils.loadPlaybackProgress(context, it) }
        ?.let { ProgressPolicy.resumePosition(it) }
    startPlayback(context, url, progressKey, episode, startPositionMs = resumeAt ?: 0L)
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
