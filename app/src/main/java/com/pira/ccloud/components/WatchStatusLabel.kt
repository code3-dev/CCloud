package com.pira.ccloud.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pira.ccloud.sync.WatchStatus

/** "Watching" or "Watched", or null for a title that hasn't been started. */
fun watchStatusText(status: WatchStatus): String? = when (status) {
    WatchStatus.UNWATCHED -> null
    WatchStatus.WATCHING -> "Watching"
    WatchStatus.WATCHED -> "Watched"
}

/** A movie's "Watching" or "Watched" under its facts; nothing when it hasn't been started. */
@Composable
fun WatchStatusLabel(status: WatchStatus, modifier: Modifier = Modifier) {
    val text = watchStatusText(status) ?: return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (status == WatchStatus.WATCHED) Icons.Default.CheckCircle else Icons.Default.PlayCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}
