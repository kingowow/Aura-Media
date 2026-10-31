package org.akkirrai.hibiki.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.akkirrai.hibiki.R
import org.akkirrai.hibiki.core.design.component.AppModalBottomSheet
import org.akkirrai.hibiki.core.localvideo.LocalVideoItem
import org.akkirrai.hibiki.core.model.formatPlaybackTime

/**
 * The "local videos" section of the library: pick a video from the device and keep
 * playing the ones the user already started, without ever asking for full storage access.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalVideosSheet(
    items: List<LocalVideoItem>,
    onDismissRequest: () -> Unit,
    onPickVideo: () -> Unit,
    onPlay: (LocalVideoItem) -> Unit,
    onRemove: (LocalVideoItem) -> Unit,
    onOpenWithAnotherApp: (LocalVideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    fun dismiss(after: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismissRequest()
            after()
        }
    }

    AppModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.library_local_videos),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            FilledTonalButton(
                onClick = { dismiss(after = onPickVideo) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.local_video_pick),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                text = stringResource(R.string.local_video_recent),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.local_video_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                items.forEach { item ->
                    LocalVideoRow(
                        item = item,
                        onPlay = { dismiss(after = { onPlay(item) }) },
                        onRemove = { onRemove(item) },
                        onOpenWithAnotherApp = { onOpenWithAnotherApp(item) },
                    )
                }
            }

            HorizontalDivider()
        }
    }
}

@Composable
private fun LocalVideoRow(
    item: LocalVideoItem,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onOpenWithAnotherApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onPlay,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val progressLabel = progressLabelFor(item)
                if (progressLabel != null) {
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(R.string.local_video_continue),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onOpenWithAnotherApp) {
                Icon(
                    imageVector = Icons.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.local_video_open_with),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.local_video_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "12:04 / 41:30" while a resume point exists, plain duration otherwise, null when unknown. */
private fun progressLabelFor(item: LocalVideoItem): String? {
    if (item.durationMs <= 0L) return null
    val position = item.positionMs.coerceIn(0L, item.durationMs)
    return "${formatPlaybackTime(position)} / ${formatPlaybackTime(item.durationMs)}"
}
