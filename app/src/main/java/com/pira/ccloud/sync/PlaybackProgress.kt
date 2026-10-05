package com.pira.ccloud.sync

import com.pira.ccloud.data.model.Episode
import com.pira.ccloud.data.model.Source
import com.pira.ccloud.data.model.sync.ProgressValue
import java.util.Locale

/** When playback positions are saved and offered for resuming. The same rules as the Apple app. */
object ProgressPolicy {
    /** Nothing is saved before this much has been watched, and no resume is offered below it. */
    const val MIN_POSITION_MS = 30_000L

    /** From this fraction of the duration on, the title counts as finished. */
    const val COMPLETED_FRACTION = 0.95

    /** How often the position is saved while playing. */
    const val SAVE_INTERVAL_MS = 10_000L

    /** At most this many positions are kept; the oldest finished ones go first. */
    const val MAX_RECORDS = 300

    /**
     * What to store for a position, or null to leave the stored progress as it is (too early to
     * save, or an unknown position).
     */
    fun evaluate(positionMs: Long, durationMs: Long): ProgressValue? {
        val duration = durationMs.coerceAtLeast(0)
        if (positionMs < 0) return null
        if (duration > 0 && positionMs >= duration * COMPLETED_FRACTION) {
            return ProgressValue(positionMs = 0, durationMs = duration, completed = true)
        }
        if (positionMs < MIN_POSITION_MS) return null
        return ProgressValue(positionMs = positionMs, durationMs = duration, completed = false)
    }

    /** The position to offer resuming from, or null if there's nothing to resume. */
    fun resumePosition(progress: ProgressValue?): Long? {
        if (progress == null || progress.completed) return null
        if (progress.positionMs < MIN_POSITION_MS) return null
        if (progress.durationMs > 0 && progress.positionMs >= progress.durationMs) return null
        return progress.positionMs
    }

    /** "42:10" or "1:42:10". */
    fun format(positionMs: Long): String {
        val totalSeconds = positionMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}

/**
 * The catalog lists trailers as sources ("تیزر", "Trailer") and as the first "episode" of a
 * season. Their playback is never saved. Same markers as the Apple app.
 */
object TrailerMarkers {
    private val words = listOf("تیزر", "تریلر", "trailer", "teaser")

    fun matches(text: String): Boolean {
        val lowercased = text.lowercase()
        return words.any { lowercased.contains(it) }
    }

    fun isTrailer(source: Source): Boolean = matches(source.quality)

    fun isTrailer(episode: Episode): Boolean =
        matches(episode.title) ||
            (episode.sources.isNotEmpty() && episode.sources.all { isTrailer(it) })
}
