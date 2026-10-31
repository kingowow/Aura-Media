package org.akkirrai.hibiki.feature.player

/**
 * Shared player chrome: every overlay, control, timeline and panel that both the online
 * player ([PlayerScreen]) and the local-video player render. None of this knows about
 * episodes, sources or PlayerUiState -- it only takes values and callbacks, which is what
 * lets one implementation serve both screens.
 */

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SubtitlesOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes.TEXT_VTT
import androidx.media3.common.MimeTypes.TEXT_SSA
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.net.URI
import kotlin.math.max
import kotlin.math.min
import org.akkirrai.hibiki.core.model.formatPlaybackTime
import org.akkirrai.hibiki.R
import org.akkirrai.hibiki.core.download.OfflineMediaCache
import org.akkirrai.hibiki.app.settings.LocalAppPreferences
import org.akkirrai.hibiki.app.settings.LocalAppPreferencesState
import org.akkirrai.hibiki.app.settings.VideoScaleMode
import org.akkirrai.hibiki.core.design.UiDimens
import org.akkirrai.hibiki.core.design.component.AppFilledIconButton
import org.akkirrai.hibiki.core.design.component.AppFilledIconButtonStyle
import org.akkirrai.hibiki.core.log.AppLogger
import org.akkirrai.hibiki.core.model.PlaybackSegment
import org.akkirrai.hibiki.core.model.PlaybackSegmentType
import org.akkirrai.hibiki.core.model.PlaybackStream
import org.akkirrai.hibiki.core.model.PlaybackSubtitle
import org.akkirrai.hibiki.core.model.PlaybackStreamType
import org.akkirrai.hibiki.core.model.WatchEpisode
import org.akkirrai.hibiki.core.model.WatchSource
import org.akkirrai.hibiki.core.source.ResumeFrameRepository
import org.akkirrai.hibiki.core.source.watchTitleIdFromSourceId
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset

@Composable
internal fun PlayerOverlayPanel(
    onDismissRequest: () -> Unit,
    widthFraction: Float,
    maxWidth: Dp,
    restingOffsetY: Dp = 0.dp,
    swipeToDismissEnabled: Boolean = true,
    content: @Composable ((() -> Unit)) -> Unit,
) {
    val density = LocalDensity.current
    val openedAtMs = remember { SystemClock.elapsedRealtime() }

    var animatingIn by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val exitOffsetPx = with(density) { PLAYER_OVERLAY_PANEL_EXIT_OFFSET.toPx() }
    val dismissThresholdPx = with(density) { PLAYER_PANEL_DISMISS_DRAG_THRESHOLD.toPx() }

    val scrimBaseAlpha by animateFloatAsState(
        targetValue = if (animatingIn) PLAYER_OVERLAY_SCRIM_ALPHA else 0f,
        animationSpec = tween(durationMillis = PLAYER_OVERLAY_ANIMATION_MS),
        label = "playerOverlayScrimAlpha"
    )

    val panelAlpha by animateFloatAsState(
        targetValue = if (animatingIn) 1f else 0f,
        animationSpec = tween(durationMillis = PLAYER_OVERLAY_ANIMATION_MS),
        label = "playerOverlayPanelAlpha"
    )

    val panelScale by animateFloatAsState(
        targetValue = if (animatingIn) 1f else 0.96f,
        animationSpec = tween(durationMillis = PLAYER_OVERLAY_ANIMATION_MS),
        label = "playerOverlayPanelScale"
    )

    val panelBaseOffsetPx by animateFloatAsState(
        targetValue = if (animatingIn) 0f else exitOffsetPx,
        animationSpec = tween(durationMillis = PLAYER_OVERLAY_ANIMATION_MS),
        label = "playerOverlayPanelBaseOffset"
    )

    val animatedDragOffsetPx by animateFloatAsState(
        targetValue = dragOffsetPx,
        animationSpec = tween(durationMillis = if (isDragging) 0 else 160),
        label = "playerOverlayDragOffset"
    )

    val dragProgress = (animatedDragOffsetPx / dismissThresholdPx).coerceIn(0f, 1f)
    val effectiveScrimAlpha = scrimBaseAlpha * (1f - dragProgress * 0.45f)
    val effectivePanelScale = panelScale * (1f - dragProgress * 0.02f)
    val effectivePanelAlpha = panelAlpha * (1f - dragProgress * 0.12f)

    LaunchedEffect(Unit) {
        animatingIn = true
    }

    fun dismissPanel() {
        if (dismissing) return
        dismissing = true
        isDragging = false
        animatingIn = false
    }

    fun finishPanelDrag() {
        if (dismissing) return

        isDragging = false
        if (dragOffsetPx >= dismissThresholdPx) {
            dismissPanel()
        } else {
            dragOffsetPx = 0f
        }
    }

    val nestedScrollConnection = remember(dismissing, dismissThresholdPx, swipeToDismissEnabled) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                consumed
                if (
                    !swipeToDismissEnabled ||
                    dismissing ||
                    source != NestedScrollSource.UserInput ||
                    available.y <= 0f
                ) {
                    return Offset.Zero
                }

                isDragging = true
                dragOffsetPx = (dragOffsetPx + available.y).coerceAtLeast(0f)
                return Offset(x = 0f, y = available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (swipeToDismissEnabled && dragOffsetPx > 0f) {
                    val shouldDismiss =
                        dragOffsetPx >= dismissThresholdPx || available.y >= PLAYER_PANEL_DISMISS_FLING_VELOCITY
                    if (shouldDismiss) {
                        dismissPanel()
                    } else {
                        finishPanelDrag()
                    }
                    return Velocity.Zero
                }

                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(dismissing) {
        if (dismissing) {
            delay(PLAYER_OVERLAY_ANIMATION_MS.toLong())
            onDismissRequest()
        }
    }

    BackHandler(enabled = !dismissing, onBack = ::dismissPanel)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = effectiveScrimAlpha))
                .clickable(enabled = !dismissing) {
                    val canDismiss =
                        SystemClock.elapsedRealtime() - openedAtMs >= PLAYER_OVERLAY_TAP_GUARD_MS
                    if (canDismiss) {
                        dismissPanel()
                    }
                }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .widthIn(max = maxWidth)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .offset(y = restingOffsetY)
                .then(
                    if (swipeToDismissEnabled) {
                        Modifier.nestedScroll(nestedScrollConnection)
                    } else {
                        Modifier
                    }
                )
                .graphicsLayer {
                    alpha = effectivePanelAlpha
                    scaleX = effectivePanelScale
                    scaleY = effectivePanelScale
                    translationY = panelBaseOffsetPx + animatedDragOffsetPx
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    // Consume clicks inside dialog so scrim does not receive them.
                },
            shape = RoundedCornerShape(20.dp),
            color = PLAYER_SHEET_COLOR,
            contentColor = Color.White,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (swipeToDismissEnabled) {
                    PlayerOverlayHandle(
                        onDragDelta = { deltaY ->
                            if (dismissing) return@PlayerOverlayHandle

                            isDragging = true
                            dragOffsetPx = (dragOffsetPx + deltaY).coerceAtLeast(0f)
                        },
                        onDragEnd = {
                            if (dismissing) return@PlayerOverlayHandle

                            finishPanelDrag()
                        }
                    )
                } else {
                    SpacerBox(12.dp)
                }

                content(::dismissPanel)
            }
        }
    }
}

@Composable
internal fun PlayerOverlayHandle(
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount.y)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.28f))
        )
    }
}

@Composable
internal fun PlayerSkipSegmentOverlay(
    countdownSeconds: Int,
    countdownStartSeconds: Int,
    autoSkipEnabled: Boolean,
    onSkipClick: () -> Unit,
    onWatchClick: () -> Unit,
) {
    val skipLabel = stringResource(R.string.watch_player_skip)

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (autoSkipEnabled) {
                PlayerSkipSegmentButton(
                text = stringResource(R.string.watch_player_watch),
                onClick = onWatchClick,
                primary = false,
            )
        }
        PlayerSkipSegmentButton(
            text = "$skipLabel (${countdownSeconds.coerceIn(0, countdownStartSeconds)})",
            onClick = onSkipClick,
            primary = true,
        )
    }
}

@Composable
internal fun PlayerSkipSegmentButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (primary) Color.White.copy(alpha = 0.92f) else Color.Black.copy(alpha = 0.58f),
        contentColor = if (primary) Color.Black else Color.White,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun PlayerTopOverlay(
    title: String,
    subtitle: String,
    titleVisible: Boolean,
    onBackClick: () -> Unit,
    onPlaylistClick: () -> Unit,
    playlistEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.82f),
                    1f to Color.Transparent
                )
            )
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        WatchBackButton(
            onBackClick = onBackClick,
            modifier = Modifier.align(Alignment.TopStart)
        )

        if (playlistEnabled) {
            AppFilledIconButton(
                onClick = onPlaylistClick,
                modifier = Modifier.align(Alignment.TopEnd),
                style = AppFilledIconButtonStyle.DarkOverlay,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                    contentDescription = stringResource(R.string.watch_player_playlist),
                    tint = Color.White
                )
            }
        }

        AnimatedVisibility(
            visible = titleVisible,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = 64.dp, end = 64.dp),
            enter = fadeIn(animationSpec = tween(140)),
            exit = fadeOut(animationSpec = tween(140)),
        ) {
            Column(
                modifier = Modifier.padding(top = 2.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title.preventTrailingOrphanWrap(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Start,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

internal fun String.preventTrailingOrphanWrap(): String {
    val trimmed = trim()
    val lastSpaceIndex = trimmed.indexOfLast { it.isWhitespace() }
    if (lastSpaceIndex <= 0 || lastSpaceIndex >= trimmed.lastIndex) return this
    return buildString(trimmed.length) {
        append(trimmed, 0, lastSpaceIndex)
        append('\u00A0')
        append(trimmed, lastSpaceIndex + 1, trimmed.length)
    }
}
@Composable
internal fun PlayerSettingsSheet(
    destination: PlayerSettingsDestination,
    selectedSpeed: Float,
    selectedSourceId: String,
    selectedPlayerName: String?,
    selectedQualityLabel: String?,
    availableQualityLabels: List<String>,
    subtitleValues: List<SelectableValue>,
    autoSkipSegments: Boolean,
    autoPlayNextEpisode: Boolean,
    options: org.akkirrai.hibiki.core.model.PlaybackSettingsOptions,
    onNavigate: (PlayerSettingsDestination) -> Unit,
    onBack: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectVoiceover: (WatchSource) -> Unit,
    onSelectPlayer: (String?) -> Unit,
    onSelectQuality: (String?) -> Unit,
    onAddCustomSubtitle: () -> Unit,
    onTranslateSubtitle: () -> Unit,
    onSearchSubtitle: () -> Unit,
    onAutoSkipSegmentsChange: (Boolean) -> Unit,
    onAutoPlayNextEpisodeChange: (Boolean) -> Unit,
    // Audio tracks (e.g. Japanese / English dub). Non-empty only for the local player which
    // enumerates ExoPlayer's C.TRACK_TYPE_AUDIO groups; the online player always streams one
    // audio track and leaves this empty.
    audioTrackValues: List<SelectableValue> = emptyList(),
    // The local-video player passes false for both: it has no episodes to skip between and
    // no subtitle tracks of its own to configure.
    showSubtitles: Boolean = true,
    showEpisodeOptions: Boolean = true,
    ) {
    val speedValues = PLAYBACK_SPEEDS.map { speed ->
        SelectableValue(
            id = speed.toString(),
            label = if (speed == 1f) "1x" else "${speed}x",
            selected = selectedSpeed == speed,
            onClick = { onSelectSpeed(speed) },
        )
    }
    val voiceoverValues = options.voiceovers.map { source ->
        SelectableValue(
            id = source.sourceId,
            label = source.title.ifBlank { source.sourceId },
            description = source.qualityLabel,
            selected = selectedSourceId == source.sourceId,
            onClick = { onSelectVoiceover(source) },
        )
    }
    val playerValues = options.links.mapNotNull { link ->
        val name = link.playerName ?: return@mapNotNull null
        SelectableValue(
            id = name,
            label = name,
            selected = selectedPlayerName == name || (selectedPlayerName == null && options.links.firstOrNull()?.playerName == name),
            onClick = { onSelectPlayer(name) },
        )
    }.distinctBy { it.id }
    val qualityValues = (options.links.mapNotNull { it.qualityLabel } + availableQualityLabels)
        .mapNotNull { it.trim().takeIf(String::isNotBlank) }
        .distinct()
        .sortedByDescending { value -> value.filter(Char::isDigit).toIntOrNull() ?: 0 }
        .map { quality ->
            SelectableValue(
                id = quality,
                label = quality,
                selected = selectedQualityLabel == quality,
                onClick = { onSelectQuality(quality) },
            )
        }
    val rootEntries = playerSettingsRootEntries(
        speedValues = speedValues,
        voiceoverValues = voiceoverValues,
        playerValues = playerValues,
        qualityValues = qualityValues,
        subtitleValues = subtitleValues,
        audioTrackValues = audioTrackValues,
        autoSkipSegments = autoSkipSegments,
        autoPlayNextEpisode = autoPlayNextEpisode,
        onNavigate = onNavigate,
        onAutoSkipSegmentsChange = onAutoSkipSegmentsChange,
        onAutoPlayNextEpisodeChange = onAutoPlayNextEpisodeChange,
        showSubtitles = showSubtitles,
        showEpisodeOptions = showEpisodeOptions,
        )

    BackHandler(enabled = destination != PlayerSettingsDestination.Root) {
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        AnimatedContent(
            targetState = destination,
            transitionSpec = { playerSettingsPageTransition() },
            label = "PlayerSettingsPage",
        ) { targetDestination ->
            Column(modifier = Modifier.fillMaxWidth()) {
                if (targetDestination != PlayerSettingsDestination.Root) {
                    PlayerSettingsHeader(
                        title = stringResource(targetDestination.titleResId),
                        icon = targetDestination.icon,
                        showBack = true,
                        onBack = onBack,
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = PLAYER_SETTINGS_PANEL_MAX_HEIGHT),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        bottom = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    userScrollEnabled = true,
                ) {
                    playerSettingsItems(
                        destination = targetDestination,
                        rootEntries = rootEntries,
                        speedValues = speedValues,
                        voiceoverValues = voiceoverValues,
                        playerValues = playerValues,
                        qualityValues = qualityValues,
                        subtitleValues = subtitleValues,
                        audioTrackValues = audioTrackValues,
                        onAddCustomSubtitle = onAddCustomSubtitle,
                        onTranslateSubtitle = onTranslateSubtitle,
                        onSearchSubtitle = onSearchSubtitle,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlayerSettingsHeader(
    title: String,
    icon: ImageVector,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 2.dp, end = 18.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBack) {
            WatchBackButton(
                onBackClick = onBack,
            )
        } else {
            SpacerBox(8.dp)
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = Color.White.copy(alpha = 0.72f),
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun PlayerSettingsEntry(
    title: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color.White.copy(alpha = 0.72f),
            )
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Clip,
            )
            Text(
                text = value,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.52f),
            )
        }
    }
}

@Composable
internal fun PlayerSettingsChoiceRow(
    value: SelectableValue,
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = value.onClick),
        color = if (value.selected) {
            Color.White.copy(alpha = 0.10f)
        } else {
            Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = if (value.selected) 1f else 0.86f),
                fontWeight = if (value.selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            value.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.54f),
                    maxLines = 1,
                )
            }
            if (value.selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
            value.onDelete?.let { onDelete ->
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.watch_player_subtitles_delete),
                        tint = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

internal fun AnimatedContentTransitionScope<PlayerSettingsDestination>.playerSettingsPageTransition(): ContentTransform {
    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
    return (
        slideInHorizontally(animationSpec = tween(180)) { width -> direction * width / 5 } +
            fadeIn(animationSpec = tween(140))
        ).togetherWith(
            slideOutHorizontally(animationSpec = tween(180)) { width -> -direction * width / 5 } +
                fadeOut(animationSpec = tween(120))
        ).using(SizeTransform(clip = false))
}

@Composable
internal fun SpacerBox(size: Dp) {
    Box(modifier = Modifier.size(size))
}

internal data class SelectableValue(
    val id: String,
    val label: String,
    val description: String? = null,
    val selected: Boolean,
    val onClick: () -> Unit,
    /** Renders a trash icon on the row (the player-created translated subtitle can be removed). */
    val onDelete: (() -> Unit)? = null,
)

internal data class PlayerSettingsEntryItem(
    val id: String,
    val title: String,
    val value: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

internal fun List<SelectableValue>.firstSelectedLabelOrDefault(defaultLabel: String = first().label): String {
    return firstOrNull { it.selected }?.label ?: defaultLabel
}

@Composable
internal fun playerSettingsRootEntries(
    speedValues: List<SelectableValue>,
    voiceoverValues: List<SelectableValue>,
    playerValues: List<SelectableValue>,
    qualityValues: List<SelectableValue>,
    subtitleValues: List<SelectableValue>,
    autoSkipSegments: Boolean,
    autoPlayNextEpisode: Boolean,
    onNavigate: (PlayerSettingsDestination) -> Unit,
    onAutoSkipSegmentsChange: (Boolean) -> Unit,
    onAutoPlayNextEpisodeChange: (Boolean) -> Unit,
    audioTrackValues: List<SelectableValue> = emptyList(),
    showSubtitles: Boolean = true,
    showEpisodeOptions: Boolean = true,
    ): List<PlayerSettingsEntryItem> = buildList {
    if (showSubtitles) {
        add(
            PlayerSettingsEntryItem(
                id = PlayerSettingsDestination.Subtitles.name,
                title = stringResource(R.string.watch_player_settings_subtitles),
                value = subtitleValues.firstSelectedLabelOrDefault(
                    defaultLabel = stringResource(R.string.watch_player_subtitles_off),
                ),
                icon = Icons.Outlined.Subtitles,
                onClick = { onNavigate(PlayerSettingsDestination.Subtitles) },
            )
        )
    }
    if (voiceoverValues.size > 1) {
        add(
            PlayerSettingsEntryItem(
                id = PlayerSettingsDestination.Voiceover.name,
                title = stringResource(R.string.watch_player_settings_voiceover),
                value = voiceoverValues.firstSelectedLabelOrDefault(),
                icon = Icons.Outlined.RecordVoiceOver,
                onClick = { onNavigate(PlayerSettingsDestination.Voiceover) },
            )
        )
    }
    if (qualityValues.size > 1) {
        add(
            PlayerSettingsEntryItem(
                id = PlayerSettingsDestination.Quality.name,
                title = stringResource(R.string.watch_player_settings_quality),
                value = qualityValues.firstSelectedLabelOrDefault(),
                icon = Icons.Outlined.HighQuality,
                onClick = { onNavigate(PlayerSettingsDestination.Quality) },
            )
        )
    }
    add(
        PlayerSettingsEntryItem(
            id = PlayerSettingsDestination.Speed.name,
            title = stringResource(R.string.watch_player_settings_speed),
            value = speedValues.firstSelectedLabelOrDefault(defaultLabel = "1x"),
            icon = Icons.Outlined.Speed,
            onClick = { onNavigate(PlayerSettingsDestination.Speed) },
        )
    )
    if (audioTrackValues.size > 1) add(
        PlayerSettingsEntryItem(
            id = PlayerSettingsDestination.AudioTrack.name,
            title = stringResource(R.string.watch_player_settings_audio_track),
            value = audioTrackValues.firstSelectedLabelOrDefault(),
            icon = Icons.Outlined.RecordVoiceOver,
            onClick = { onNavigate(PlayerSettingsDestination.AudioTrack) },
        )
    )
    if (showEpisodeOptions) add(
        PlayerSettingsEntryItem(
            id = "auto_skip",
            title = stringResource(R.string.watch_player_settings_auto_skip),
            value = stringResource(
                if (autoSkipSegments) R.string.watch_player_settings_on
                else R.string.watch_player_settings_off,
            ),
            icon = Icons.Outlined.SkipNext,
            onClick = { onAutoSkipSegmentsChange(!autoSkipSegments) },
        )
    )
    if (showEpisodeOptions) add(
        PlayerSettingsEntryItem(
            id = "auto_play_next",
            title = stringResource(R.string.watch_player_settings_auto_play_next),
            value = stringResource(
                if (autoPlayNextEpisode) R.string.watch_player_settings_on
                else R.string.watch_player_settings_off,
            ),
            icon = Icons.Outlined.PlayCircle,
            onClick = { onAutoPlayNextEpisodeChange(!autoPlayNextEpisode) },
        )
    )
    if (playerValues.isNotEmpty()) {
        add(
            PlayerSettingsEntryItem(
                id = PlayerSettingsDestination.Player.name,
                title = stringResource(R.string.watch_player_settings_player),
                value = playerValues.firstSelectedLabelOrDefault(),
                icon = Icons.Outlined.VideoSettings,
                onClick = { onNavigate(PlayerSettingsDestination.Player) },
            )
        )
    }
}

internal fun androidx.compose.foundation.lazy.LazyListScope.playerSettingsItems(
    destination: PlayerSettingsDestination,
    rootEntries: List<PlayerSettingsEntryItem>,
    speedValues: List<SelectableValue>,
    voiceoverValues: List<SelectableValue>,
    playerValues: List<SelectableValue>,
    qualityValues: List<SelectableValue>,
    subtitleValues: List<SelectableValue>,
    audioTrackValues: List<SelectableValue> = emptyList(),
    onAddCustomSubtitle: () -> Unit,
    onTranslateSubtitle: () -> Unit,
    onSearchSubtitle: () -> Unit,
) {
    when (destination) {
        PlayerSettingsDestination.Root -> items(rootEntries, key = PlayerSettingsEntryItem::id) { entry ->
            PlayerSettingsEntry(
                title = entry.title,
                value = entry.value,
                icon = entry.icon,
                onClick = entry.onClick,
            )
        }
        PlayerSettingsDestination.Speed -> playerSettingsChoices(speedValues)
        PlayerSettingsDestination.Voiceover -> playerSettingsChoices(voiceoverValues)
        PlayerSettingsDestination.Player -> playerSettingsChoices(playerValues)
        PlayerSettingsDestination.Quality -> playerSettingsChoices(qualityValues)
        PlayerSettingsDestination.AudioTrack -> playerSettingsChoices(audioTrackValues)
        PlayerSettingsDestination.Subtitles -> {
            item(key = "add_custom_subtitle") {
                PlayerSettingsEntry(
                    title = stringResource(R.string.watch_player_subtitles_add),
                    value = "",
                    icon = Icons.Outlined.Subtitles,
                    onClick = onAddCustomSubtitle,
                )
            }
            // Sits directly under "add subtitle": one tap translates the video's own subtitle
            // (or an already-added one) with the mode chosen in Settings → Preferences.
            item(key = "translate_subtitle") {
                PlayerSettingsEntry(
                    title = stringResource(R.string.watch_player_subtitles_translate),
                    value = "",
                    icon = Icons.Outlined.Translate,
                    onClick = onTranslateSubtitle,
                )
            }
            // Next to the translate button: search the Persian subtitle sites for this episode.
            item(key = "search_subtitle") {
                PlayerSettingsEntry(
                    title = stringResource(R.string.watch_player_subtitles_search),
                    value = "",
                    icon = Icons.Outlined.Search,
                    onClick = onSearchSubtitle,
                )
            }
            item(key = "subtitle_size") {
                val appPreferences = LocalAppPreferences.current
                val prefs = LocalAppPreferencesState.current
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.watch_player_subtitles_size) +
                            " · ${(prefs.subtitleTextScale * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Slider(
                        value = prefs.subtitleTextScale,
                        onValueChange = appPreferences::setSubtitleTextScale,
                        valueRange = SUBTITLE_SCALE_MIN..SUBTITLE_SCALE_MAX,
                    )
                }
            }
            item(key = "subtitle_color") {
                val appPreferences = LocalAppPreferences.current
                val prefs = LocalAppPreferencesState.current
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.watch_player_subtitles_color),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        SUBTITLE_COLOR_PRESETS.forEach { argb ->
                            val selected = prefs.subtitleTextColor == argb
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .background(Color(argb), CircleShape)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                        },
                                        shape = CircleShape,
                                    )
                                    .clickable { appPreferences.setSubtitleTextColor(argb) },
                            )
                        }
                    }
                }
            }
            item(key = "subtitle_offset") {
                val appPreferences = LocalAppPreferences.current
                val prefs = LocalAppPreferencesState.current
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.watch_player_subtitles_offset) +
                            " · ${formatSubtitleOffset(prefs.subtitleOffsetMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Slider(
                        value = prefs.subtitleOffsetMs.toFloat(),
                        onValueChange = { value -> appPreferences.setSubtitleOffsetMs(value.toInt()) },
                        valueRange = SUBTITLE_OFFSET_MIN_MS.toFloat()..SUBTITLE_OFFSET_MAX_MS.toFloat(),
                        steps = 119,
                    )
                }
            }
            playerSettingsChoices(subtitleValues)
        }
    }
}

internal fun androidx.compose.foundation.lazy.LazyListScope.playerSettingsChoices(
    values: List<SelectableValue>,
) {
    items(values, key = SelectableValue::id) { value ->
        PlayerSettingsChoiceRow(value = value)
    }
}

internal enum class PlayerSettingsDestination(
    @param:StringRes val titleResId: Int,
    val icon: ImageVector,
) {
    Root(R.string.watch_player_settings_root, Icons.Outlined.Settings),
    Speed(R.string.watch_player_settings_speed, Icons.Outlined.Speed),
    Voiceover(R.string.watch_player_settings_voiceover, Icons.Outlined.RecordVoiceOver),
    Player(R.string.watch_player_settings_player, Icons.Outlined.VideoSettings),
    Quality(R.string.watch_player_settings_quality, Icons.Outlined.HighQuality),
    Subtitles(R.string.watch_player_settings_subtitles, Icons.Outlined.Subtitles),
    AudioTrack(R.string.watch_player_settings_audio_track, Icons.Outlined.RecordVoiceOver),
}

// Kept in sync with AppPreferences.SUBTITLE_* bounds (declared locally so this file needs no
// extra import for the settings sliders).
internal val SUBTITLE_SCALE_MIN = 0.75f
internal val SUBTITLE_SCALE_MAX = 1.75f
internal const val SUBTITLE_OFFSET_MIN_MS = -15_000
internal const val SUBTITLE_OFFSET_MAX_MS = 15_000

/** Palette offered for the subtitle text color. */
internal val SUBTITLE_COLOR_PRESETS = listOf(
    0xFFFFFFFFL,
    0xFFFFE14DL,
    0xFF66E0F0L,
    0xFF8CE99AL,
    0xFFFFA94DL,
    0xFFFF7B9CL,
)

internal fun formatSubtitleOffset(offsetMs: Int): String {
    if (offsetMs == 0) return "0.0s"
    val sign = if (offsetMs > 0) "+" else "-"
    return "%s%.1fs".format(sign, kotlin.math.abs(offsetMs) / 1000.0)
}

/** Last event at or before [nowMs] decides which lines are on screen (the offset is applied by
 * the caller when it computes [nowMs]). */
internal fun cueLinesAt(events: List<Pair<Long, List<String>>>, nowMs: Long): List<String> {
    var visible: List<String> = emptyList()
    for ((timestampMs, lines) in events) {
        if (timestampMs <= nowMs) visible = lines else break
    }
    return visible
}
@Composable
internal fun PlayerSubtitleOverlay(
    lines: List<String>,
    visible: Boolean,
    controlsVisible: Boolean,
    textColor: Color,
    textScale: Float,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && lines.isNotEmpty(),
        modifier = modifier
            .navigationBarsPadding()
            .padding(
                start = 24.dp,
                end = 24.dp,
                bottom = if (controlsVisible) 108.dp else 34.dp,
            ),
        enter = fadeIn(animationSpec = tween(90)),
        exit = fadeOut(animationSpec = tween(90)),
    ) {
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = Color.Black.copy(alpha = 0.76f),
        ) {
            Text(
                text = lines.joinToString(separator = "\n"),
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                color = textColor,
                fontSize = MaterialTheme.typography.titleMedium.fontSize * textScale,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun PlayerBottomOverlay(
    durationMs: Long,
    bufferedPositionMs: Long,
    sliderPositionMs: Long,
    showRemainingTime: Boolean,
    onTimeLabelClick: () -> Unit,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    videoScaleMode: VideoScaleMode,
    onVideoScaleModeClick: () -> Unit,
    subtitlesEnabled: Boolean,
    onSubtitlesClick: () -> Unit,
    onSubtitlesLongClick: () -> Unit,
    settingsEnabled: Boolean,
    onSettingsClick: () -> Unit,
    pictureInPictureEnabled: Boolean,
    onPictureInPictureClick: () -> Unit,
    onLockClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.92f)
                )
            )
            .navigationBarsPadding()
            .padding(
                start = PLAYER_CONTROLS_HORIZONTAL_PADDING,
                end = PLAYER_CONTROLS_HORIZONTAL_PADDING,
                top = 8.dp,
                bottom = 18.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            PlayerTimeline(
                durationMs = durationMs,
                bufferedPositionMs = bufferedPositionMs,
                sliderPositionMs = sliderPositionMs,
                onSeekPreview = onSliderValueChange,
                onSeekFinished = onSliderValueChangeFinished,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 0.dp)
                    .offset(y = (-3).dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = if (showRemainingTime) {
                        "-${formatPlaybackTime((durationMs - sliderPositionMs).coerceAtLeast(0L))}"
                    } else {
                        "${formatPlaybackTime(sliderPositionMs)} / ${formatPlaybackTime(durationMs)}"
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onTimeLabelClick)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppFilledIconButton(
                        onClick = onVideoScaleModeClick,
                        modifier = Modifier.size(46.dp),
                        style = AppFilledIconButtonStyle.DarkOverlay,
                    ) {
                        Icon(
                            painter = painterResource(videoScaleMode.iconResId()),
                            contentDescription = stringResource(videoScaleMode.contentDescriptionResId()),
                            tint = Color.White,
                        )
                    }
                    PlayerSubtitleButton(
                        enabled = subtitlesEnabled,
                        onClick = onSubtitlesClick,
                        onLongClick = onSubtitlesLongClick,
                    )
                    AppFilledIconButton(
                        onClick = onLockClick,
                        modifier = Modifier.size(46.dp),
                        style = AppFilledIconButtonStyle.DarkOverlay,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = stringResource(R.string.watch_player_lock),
                            tint = Color.White,
                        )
                    }
                    AppFilledIconButton(
                        onClick = onPictureInPictureClick,
                        enabled = pictureInPictureEnabled,
                        modifier = Modifier.size(46.dp),
                        style = AppFilledIconButtonStyle.DarkOverlay,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_player_picture_in_picture_24),
                            contentDescription = stringResource(R.string.watch_player_picture_in_picture),
                            tint = Color.White,
                        )
                    }
                    AppFilledIconButton(
                        onClick = onSettingsClick,
                        enabled = settingsEnabled,
                        modifier = Modifier.size(46.dp),
                        style = AppFilledIconButtonStyle.DarkOverlay,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.watch_player_settings),
                            tint = Color.White,
                        )
                    }
                }
            }
        }

    }
}

@Composable
internal fun PlayerSubtitleButton(
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.58f),
        contentColor = Color.White,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (enabled) Icons.Filled.Subtitles else Icons.Outlined.Subtitles,
                contentDescription = stringResource(
                    if (enabled) R.string.watch_player_subtitles_disable
                    else R.string.watch_player_subtitles_enable,
                ),
            )
        }
    }
}

internal fun PlayerView.applyVideoScale(mode: VideoScaleMode, videoAspectRatio: Float) {
    val textureView = videoSurfaceView as? TextureView ?: return
    if (!textureView.isLaidOut || textureView.width == 0 || textureView.height == 0) {
        textureView.doOnLayout { applyVideoScale(mode, videoAspectRatio) }
        return
    }

    val containerAspectRatio = textureView.width.toFloat() / textureView.height
    val aspectRatioFactor = videoAspectRatio / containerAspectRatio
    val (scaleX, scaleY) = when (mode) {
        VideoScaleMode.FIT -> min(1f, aspectRatioFactor) to min(1f, 1f / aspectRatioFactor)
        VideoScaleMode.CROP -> max(1f, aspectRatioFactor) to max(1f, 1f / aspectRatioFactor)
        VideoScaleMode.STRETCH -> 1f to 1f
    }
    val target = TextureVideoScale(mode, scaleX, scaleY)
    if (textureView.tag == target) return

    textureView.tag = target
    textureView.animate()
        .cancel()
    textureView.animate()
        .scaleX(scaleX)
        .scaleY(scaleY)
        .setDuration(PLAYER_VIDEO_SCALE_ANIMATION_DURATION_MS)
        .setInterpolator(DecelerateInterpolator())
        .start()
}

internal data class TextureVideoScale(
    val mode: VideoScaleMode,
    val scaleX: Float,
    val scaleY: Float,
)

internal fun VideoScaleMode.iconResId(): Int = when (this) {
    VideoScaleMode.FIT -> R.drawable.ic_player_fit_to_screen_24
    VideoScaleMode.CROP -> R.drawable.ic_player_settings_overscan_24
    VideoScaleMode.STRETCH -> R.drawable.ic_player_aspect_ratio_24
}

@StringRes
internal fun VideoScaleMode.contentDescriptionResId(): Int = when (this) {
    VideoScaleMode.FIT -> R.string.watch_player_video_scale_fit
    VideoScaleMode.CROP -> R.string.watch_player_video_scale_crop
    VideoScaleMode.STRETCH -> R.string.watch_player_video_scale_stretch
}
@Composable
internal fun AutoHideVisibilityEffect(
    enabled: Boolean,
    visible: Boolean,
    interactionTick: Int,
    blocked: Boolean,
    onHide: () -> Unit,
) {
    LaunchedEffect(enabled, visible, interactionTick, blocked) {
        if (!enabled || !visible || blocked) return@LaunchedEffect
        delay(PLAYER_CONTROLS_AUTO_HIDE_DELAY_MS)
        onHide()
    }
}

@Composable
internal fun PlayerUnlockButton(
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = Color.Black.copy(alpha = 0.72f),
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.LockOpen,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.watch_player_unlock),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun PlayerCenterControls(
    isPlaying: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    onTogglePlay: () -> Unit,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerControlButton(
            painter = painterResource(R.drawable.ic_player_media_skip_previous_24),
            enabled = hasPreviousEpisode,
            onClick = onPreviousEpisode,
        )
        AppFilledIconButton(
            onClick = onTogglePlay,
            modifier = Modifier.size(PLAYER_CENTER_PRIMARY_BUTTON_SIZE),
            style = AppFilledIconButtonStyle.DarkOverlay,
        ) {
            Icon(
                painter = painterResource(
                    if (isPlaying) {
                        R.drawable.ic_player_media_pause_24
                    } else {
                        R.drawable.ic_player_media_play_arrow_24
                    }
                ),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = Color.White,
            )
        }
        PlayerControlButton(
            painter = painterResource(R.drawable.ic_player_media_skip_next_24),
            enabled = hasNextEpisode,
            onClick = onNextEpisode,
        )
    }
}

@Composable
internal fun PlayerTimeline(
    durationMs: Long,
    bufferedPositionMs: Long,
    sliderPositionMs: Long,
    onSeekPreview: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var trackWidthPx by remember { mutableFloatStateOf(1f) }
    val safeDuration = durationMs.coerceAtLeast(1L)
    val playedFraction = (sliderPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val bufferedFraction = (bufferedPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val playedColor = Color(0xFFE53935)
    val bufferedColor = playedColor.copy(alpha = 0.34f)
    val trackColor = Color.White.copy(alpha = 0.18f)
    val thumbRadiusPx = with(LocalDensity.current) { PLAYER_TIMELINE_THUMB_RADIUS.toPx() }

    fun seekFromX(x: Float) {
        val fraction = (x / trackWidthPx).coerceIn(0f, 1f)
        onSeekPreview((safeDuration * fraction).toLong())
    }

    Box(
        modifier = modifier
            .height(18.dp)
            .pointerInput(safeDuration, trackWidthPx) {
                detectTapGestures(
                    onTap = { offset ->
                        seekFromX(offset.x)
                        onSeekFinished()
                    }
                )
            }
            .pointerInput(safeDuration, trackWidthPx) {
                detectDragGestures(
                    onDragStart = { offset ->
                        seekFromX(offset.x)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        seekFromX(change.position.x)
                    },
                    onDragEnd = onSeekFinished,
                    onDragCancel = onSeekFinished,
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PLAYER_TIMELINE_TRACK_HEIGHT)
                .clip(RoundedCornerShape(999.dp))
                .background(trackColor)
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(bufferedFraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(bufferedColor)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(playedFraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(playedColor)
            )
        }

        Box(
            modifier = Modifier
                .graphicsLayer {
                    translationX = (trackWidthPx * playedFraction) - thumbRadiusPx
                }
                .size(PLAYER_TIMELINE_THUMB_SIZE)
                .clip(CircleShape)
                .background(playedColor)
        )
    }
}

@Composable
internal fun PlayerControlButton(
    painter: Painter,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    AppFilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(52.dp),
        style = AppFilledIconButtonStyle.DarkOverlay,
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = Color.White
        )
    }
}
internal const val PLAYER_CONTROLS_AUTO_HIDE_DELAY_MS = 2_500L
internal val PLAYER_SHEET_COLOR = Color(0xFF121212)
internal val PLAYER_SETTINGS_SHEET_MAX_WIDTH = 460.dp
internal val PLAYER_SETTINGS_PANEL_MAX_HEIGHT = 300.dp
internal val PLAYER_SETTINGS_PANEL_RESTING_OFFSET_Y = 0.dp
internal const val PLAYER_SETTINGS_PANEL_WIDTH_FRACTION = 0.68f
internal val PLAYER_PLAYLIST_PANEL_MAX_WIDTH = 380.dp
internal const val PLAYER_PLAYLIST_PANEL_WIDTH_FRACTION = 0.58f
internal val PLAYER_PLAYLIST_SHEET_MAX_HEIGHT = 360.dp
internal val PLAYER_SUBTITLE_SEARCH_PANEL_MAX_WIDTH = 440.dp
internal const val PLAYER_SUBTITLE_SEARCH_PANEL_WIDTH_FRACTION = 0.86f
internal val PLAYER_SUBTITLE_SEARCH_PANEL_RESTING_OFFSET_Y = 0.dp
internal val PLAYER_OVERLAY_PANEL_EXIT_OFFSET = 40.dp
internal val PLAYER_PANEL_DISMISS_DRAG_THRESHOLD = 72.dp
internal const val PLAYER_PANEL_DISMISS_FLING_VELOCITY = 900f
internal const val PLAYER_OVERLAY_ANIMATION_MS = 220
internal const val PLAYER_VIDEO_SCALE_ANIMATION_DURATION_MS = 220L
internal const val DEFAULT_VIDEO_ASPECT_RATIO = 16f / 9f
internal const val PLAYER_OVERLAY_TAP_GUARD_MS = 120L
internal const val PLAYER_OVERLAY_SCRIM_ALPHA = 0.48f
internal val PLAYER_CONTROLS_HORIZONTAL_PADDING = 24.dp
internal val PLAYER_CENTER_PRIMARY_BUTTON_SIZE = 72.dp
internal val PLAYER_TIMELINE_TRACK_HEIGHT = 4.dp
internal val PLAYER_TIMELINE_THUMB_SIZE = 8.dp
internal val PLAYER_TIMELINE_THUMB_RADIUS = 4.dp
internal val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
internal fun AnimatedSeekOverlayArrow(
    direction: Int,
    pulse: Int,
) {
    AnimatedContent(
        targetState = pulse,
        modifier = Modifier.zIndex(-1f),
        transitionSpec = {
            (fadeIn(animationSpec = tween(90)) +
                slideInHorizontally(
                    initialOffsetX = { width -> -direction * width / 2 },
                    animationSpec = tween(180),
                )) togetherWith
                (fadeOut(animationSpec = tween(220)) +
                    slideOutHorizontally(
                        targetOffsetX = { width -> direction * width / 2 },
                        animationSpec = tween(220),
                    )) using SizeTransform(clip = false) { _, _ -> tween(durationMillis = 0) }
        },
        label = "DoubleTapSeekArrow",
    ) {
        SeekOverlayArrow(
            imageVector = if (direction < 0) {
                Icons.AutoMirrored.Outlined.KeyboardArrowLeft
            } else {
                Icons.AutoMirrored.Outlined.KeyboardArrowRight
            },
        )
    }
}

@Composable
internal fun SeekOverlayArrow(imageVector: ImageVector) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier
                .size(28.dp)
                .offset(x = 1.dp, y = 1.dp),
            tint = Color.Black.copy(alpha = 0.7f),
        )
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = Color.White,
        )
    }
}

internal fun buildSeekDeltaLabel(deltaMs: Long): String {
    val sign = if (deltaMs >= 0L) "+" else "-"
    return sign + (kotlin.math.abs(deltaMs) / 1_000L)
}
internal const val DOUBLE_TAP_ACCUMULATION_WINDOW_MS = 700L
internal const val PICTURE_IN_PICTURE_ACTION_TOGGLE_AUDIO_ONLY =
    "org.akkirrai.hibiki.action.TOGGLE_AUDIO_ONLY"
internal const val PICTURE_IN_PICTURE_ACTION_TOGGLE_PLAYBACK =
    "org.akkirrai.hibiki.action.TOGGLE_PLAYBACK"
internal const val PICTURE_IN_PICTURE_ACTION_PREVIOUS_EPISODE =
    "org.akkirrai.hibiki.action.PREVIOUS_EPISODE"
internal const val PICTURE_IN_PICTURE_ACTION_NEXT_EPISODE =
    "org.akkirrai.hibiki.action.NEXT_EPISODE"
internal const val PICTURE_IN_PICTURE_AUDIO_ONLY_REQUEST_CODE = 1001
internal const val PICTURE_IN_PICTURE_PLAYBACK_REQUEST_CODE = 1002
internal const val PICTURE_IN_PICTURE_PREVIOUS_EPISODE_REQUEST_CODE = 1003
internal const val PICTURE_IN_PICTURE_NEXT_EPISODE_REQUEST_CODE = 1004
/**
 * Accumulates consecutive double-tap seek gestures (e.g. tap-tap-tap to seek +30s) and commits
 * the accumulated seek to the player after [DOUBLE_TAP_ACCUMULATION_WINDOW_MS] of inactivity.
 */
internal class PlayerDoubleTapSeekController(
    private val exoPlayer: ExoPlayer,
    private val coroutineScope: CoroutineScope,
    private val seekIncrementMs: Long,
    private val currentPlaybackPositionMs: () -> Long,
    private val currentDurationMs: () -> Long,
    private val onSeekCommitted: (targetPositionMs: Long) -> Unit,
) {
    private var lastDoubleTapAtMs by mutableLongStateOf(0L)
    private var lastDoubleTapDirection by mutableIntStateOf(0)
    private var accumulatedBasePositionMs by mutableLongStateOf(0L)
    private var pendingSeekJob: Job? = null

    var accumulatedSteps by mutableIntStateOf(0)
        private set
    var overlayVisible by mutableStateOf(false)
        private set
    var overlayDeltaMs by mutableLongStateOf(0L)
        private set
    var arrowPulse by mutableIntStateOf(0)
        private set

    fun reset() {
        pendingSeekJob?.cancel()
        pendingSeekJob = null
        lastDoubleTapAtMs = 0L
        lastDoubleTapDirection = 0
        accumulatedSteps = 0
        accumulatedBasePositionMs = 0L
        overlayVisible = false
        overlayDeltaMs = 0L
    }

    fun cancelPending() {
        pendingSeekJob?.cancel()
        pendingSeekJob = null
    }

    fun schedule(direction: Int, eventTimeMs: Long) {
        val isAccumulating =
            direction == lastDoubleTapDirection &&
                accumulatedSteps > 0 &&
                eventTimeMs - lastDoubleTapAtMs <= DOUBLE_TAP_ACCUMULATION_WINDOW_MS
        val nextSteps = if (isAccumulating) accumulatedSteps + 1 else 1
        val basePositionMs = if (isAccumulating) {
            accumulatedBasePositionMs
        } else {
            currentPlaybackPositionMs()
        }
        val safeDurationMs = exoPlayer.duration.takeIf { it > 0 } ?: currentDurationMs()
        val requestedDeltaMs = seekIncrementMs * nextSteps * direction
        val targetPositionMs = when {
            direction < 0 -> (basePositionMs + requestedDeltaMs).coerceAtLeast(0L)
            safeDurationMs > 0L -> (basePositionMs + requestedDeltaMs).coerceAtMost(safeDurationMs)
            else -> basePositionMs + requestedDeltaMs
        }
        val effectiveDeltaMs = targetPositionMs - basePositionMs

        // Do not acknowledge a double tap when the player cannot move any further.
        if (effectiveDeltaMs == 0L) return

        pendingSeekJob?.cancel()
        lastDoubleTapAtMs = eventTimeMs
        lastDoubleTapDirection = direction
        accumulatedSteps = nextSteps
        accumulatedBasePositionMs = basePositionMs
        overlayVisible = true
        overlayDeltaMs = effectiveDeltaMs
        arrowPulse += 1
        pendingSeekJob = coroutineScope.launch {
            delay(DOUBLE_TAP_ACCUMULATION_WINDOW_MS)
            commit()
        }
    }

    private fun commit() {
        val direction = lastDoubleTapDirection
        val steps = accumulatedSteps
        if (direction == 0 || steps <= 0) return

        val deltaMs = seekIncrementMs * steps
        val safeDurationMs = exoPlayer.duration.takeIf { it > 0 } ?: currentDurationMs()
        val targetPositionMs = if (direction < 0) {
            (accumulatedBasePositionMs - deltaMs).coerceAtLeast(0L)
        } else if (safeDurationMs > 0L) {
            (accumulatedBasePositionMs + deltaMs).coerceAtMost(safeDurationMs)
        } else {
            accumulatedBasePositionMs + deltaMs
        }

        exoPlayer.seekTo(targetPositionMs)
        onSeekCommitted(targetPositionMs)
        lastDoubleTapAtMs = 0L
        lastDoubleTapDirection = 0
        accumulatedSteps = 0
        accumulatedBasePositionMs = 0L
        pendingSeekJob = null
        overlayVisible = false
    }
}

@Composable
internal fun PlayerImmersiveModeEffect(
    activity: Activity?,
    view: View,
    restoreWindowUi: MutableState<(() -> Unit)?>,
) {
    DisposableEffect(activity, view) {
        if (activity == null) {
            onDispose {}
        } else {
            val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            val previousOrientation = activity.requestedOrientation
            val previousBehavior = controller.systemBarsBehavior
            val previousCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                activity.window.attributes.layoutInDisplayCutoutMode
            } else {
                null
            }

            fun applyPlayerWindowMode() {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    activity.window.attributes = activity.window.attributes.apply {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
            }

            applyPlayerWindowMode()

            var restored = false
            restoreWindowUi.value = restore@{
                if (restored) {
                    return@restore
                }
                restored = true
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = previousBehavior
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.requestedOrientation = previousOrientation
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && previousCutoutMode != null) {
                    activity.window.attributes = activity.window.attributes.apply {
                        layoutInDisplayCutoutMode = previousCutoutMode
                    }
                }
            }

            view.post {
                if (!restored) {
                    applyPlayerWindowMode()
                }
            }

            onDispose {
                restoreWindowUi.value?.invoke()
                restoreWindowUi.value = null
            }
        }
    }
}
internal fun buildPictureInPictureParams(
    context: Context,
    isAudioOnly: Boolean,
    isPlaying: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
): PictureInPictureParams {
    val actions = buildList {
        add(
            createPictureInPictureAction(
                context = context,
                action = PICTURE_IN_PICTURE_ACTION_TOGGLE_AUDIO_ONLY,
                requestCode = PICTURE_IN_PICTURE_AUDIO_ONLY_REQUEST_CODE,
                iconResId = R.drawable.ic_player_headphones_24,
                titleResId = if (isAudioOnly) {
                    R.string.watch_player_show_video
                } else {
                    R.string.watch_player_audio_only
                },
            )
        )
        add(
            createPictureInPictureAction(
                context = context,
                action = PICTURE_IN_PICTURE_ACTION_TOGGLE_PLAYBACK,
                requestCode = PICTURE_IN_PICTURE_PLAYBACK_REQUEST_CODE,
                iconResId = if (isPlaying) {
                    R.drawable.ic_player_media_pause_24
                } else {
                    R.drawable.ic_player_media_play_arrow_24
                },
                titleResId = if (isPlaying) {
                    R.string.watch_player_pause
                } else {
                    R.string.watch_player_play
                },
            )
        )
        if (hasPreviousEpisode) {
            add(
                createPictureInPictureAction(
                    context = context,
                    action = PICTURE_IN_PICTURE_ACTION_PREVIOUS_EPISODE,
                    requestCode = PICTURE_IN_PICTURE_PREVIOUS_EPISODE_REQUEST_CODE,
                    iconResId = R.drawable.ic_player_media_skip_previous_24,
                    titleResId = R.string.watch_player_previous_episode,
                )
            )
        }
        if (hasNextEpisode) {
            add(
                createPictureInPictureAction(
                    context = context,
                    action = PICTURE_IN_PICTURE_ACTION_NEXT_EPISODE,
                    requestCode = PICTURE_IN_PICTURE_NEXT_EPISODE_REQUEST_CODE,
                    iconResId = R.drawable.ic_player_media_skip_next_24,
                    titleResId = R.string.watch_player_next_episode,
                )
            )
        }
    }
    return PictureInPictureParams.Builder().setActions(actions).build()
}

@Composable
internal fun PlayerPictureInPictureEffects(
    context: Context,
    activity: Activity?,
    exoPlayer: ExoPlayer,
    currentEpisodeId: String,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    isPlaying: Boolean,
    isAudioOnly: MutableState<Boolean>,
    isPictureInPictureActive: MutableState<Boolean>,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
) {
    DisposableEffect(context, currentEpisodeId, hasNextEpisode) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    PICTURE_IN_PICTURE_ACTION_TOGGLE_AUDIO_ONLY -> {
                        exoPlayer.play()
                        isAudioOnly.value = true
                        isPictureInPictureActive.value = false
                        activity?.moveTaskToBack(true)
                    }

                    PICTURE_IN_PICTURE_ACTION_TOGGLE_PLAYBACK -> {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    }

                    PICTURE_IN_PICTURE_ACTION_PREVIOUS_EPISODE -> {
                        if (hasPreviousEpisode) onPreviousEpisode()
                    }

                    PICTURE_IN_PICTURE_ACTION_NEXT_EPISODE -> {
                        if (hasNextEpisode) onNextEpisode()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(PICTURE_IN_PICTURE_ACTION_TOGGLE_AUDIO_ONLY)
                addAction(PICTURE_IN_PICTURE_ACTION_TOGGLE_PLAYBACK)
                addAction(PICTURE_IN_PICTURE_ACTION_PREVIOUS_EPISODE)
                addAction(PICTURE_IN_PICTURE_ACTION_NEXT_EPISODE)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(currentEpisodeId, hasPreviousEpisode, hasNextEpisode, isPlaying) {
        if (isPictureInPictureActive.value) {
            activity?.setPictureInPictureParams(
                buildPictureInPictureParams(
                    context = context,
                    isAudioOnly = isAudioOnly.value,
                    isPlaying = isPlaying,
                    hasPreviousEpisode = hasPreviousEpisode,
                    hasNextEpisode = hasNextEpisode,
                )
            )
        }
    }
}

internal fun createPictureInPictureAction(
    context: Context,
    action: String,
    requestCode: Int,
    iconResId: Int,
    @StringRes titleResId: Int,
): RemoteAction {
    val title = context.getString(titleResId)
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(action).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return RemoteAction(
        Icon.createWithResource(context, iconResId),
        title,
        title,
        pendingIntent,
    )
}
