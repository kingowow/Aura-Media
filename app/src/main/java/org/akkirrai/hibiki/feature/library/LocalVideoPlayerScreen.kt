package org.akkirrai.hibiki.feature.library

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.withTimeoutOrNull
import org.akkirrai.hibiki.R
import org.akkirrai.hibiki.app.settings.LocalAppPreferences
import org.akkirrai.hibiki.app.settings.LocalAppPreferencesState
import org.akkirrai.hibiki.core.design.component.AppFilledIconButton
import org.akkirrai.hibiki.core.design.component.AppFilledIconButtonStyle
import org.akkirrai.hibiki.core.localvideo.LocalVideoHistory
import org.akkirrai.hibiki.core.log.AppLogger
import org.akkirrai.hibiki.core.localvideo.openVideoWithAnotherApp
import org.akkirrai.hibiki.core.model.PlaybackSettingsOptions
import org.akkirrai.hibiki.feature.player.AnimatedSeekOverlayArrow
import org.akkirrai.hibiki.feature.player.AutoHideVisibilityEffect
import org.akkirrai.hibiki.feature.player.DEFAULT_VIDEO_ASPECT_RATIO
import org.akkirrai.hibiki.feature.player.DOUBLE_TAP_TIMEOUT_MS
import org.akkirrai.hibiki.feature.player.PlayerBottomOverlay
import org.akkirrai.hibiki.feature.player.PlayerCenterControls
import org.akkirrai.hibiki.feature.player.PlayerDoubleTapSeekController
import org.akkirrai.hibiki.feature.player.PlayerImmersiveModeEffect
import org.akkirrai.hibiki.feature.player.PlayerLifecycleEffect
import org.akkirrai.hibiki.feature.player.PlayerOverlayPanel
import org.akkirrai.hibiki.feature.player.PlayerPictureInPictureEffects
import org.akkirrai.hibiki.feature.player.PlayerPlaybackPositionPollingEffect
import org.akkirrai.hibiki.feature.player.PlayerSettingsDestination
import org.akkirrai.hibiki.feature.player.SelectableValue
import org.akkirrai.hibiki.feature.player.PlayerSettingsSheet
import org.akkirrai.hibiki.feature.player.PlayerSubtitleOverlay
import org.akkirrai.hibiki.feature.player.PlayerTopOverlay
import org.akkirrai.hibiki.feature.player.PlayerUnlockButton
import org.akkirrai.hibiki.feature.player.PLAYER_SETTINGS_PANEL_RESTING_OFFSET_Y
import org.akkirrai.hibiki.feature.player.PLAYER_SETTINGS_PANEL_WIDTH_FRACTION
import org.akkirrai.hibiki.feature.player.PLAYER_SETTINGS_SHEET_MAX_WIDTH
import org.akkirrai.hibiki.feature.player.SEEK_INCREMENT_MS
import org.akkirrai.hibiki.feature.player.applyVideoScale
import org.akkirrai.hibiki.feature.player.buildPictureInPictureParams
import org.akkirrai.hibiki.feature.player.buildSeekDeltaLabel
import org.akkirrai.hibiki.feature.player.cueLinesAt

/**
 * Plays one `content://` video picked from the device.
 *
 * This screen deliberately reuses the shared chrome from the online player
 * (`feature/player/PlayerChrome.kt`) instead of drawing its own controls, so a local file and a
 * streamed episode look and behave the same: same gradient overlays, same timeline, same
 * double-tap seek, same lock / PiP / scale buttons. What differs is the source (a saved Uri
 * instead of a stream) and the settings sheet, which drops the entries that only make sense for
 * an episode (subtitles, auto-skip, auto-play-next, voiceover / quality / player).
 *
 * The resume point is written back to [LocalVideoHistory] so the library's "continue" entry stays
 * accurate.
 */
@Composable
fun LocalVideoPlayerScreen(
    videoId: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val history = remember(context) { LocalVideoHistory(context) }
    val entry = remember(videoId) { history.byId(videoId) }

    if (entry == null) {
        // The history entry vanished (cleared data, other device restore): nothing to play.
        LaunchedEffect(Unit) { onBackClick() }
        Box(modifier = modifier.fillMaxSize().background(Color.Black))
        return
    }

    val uri = remember(entry.id) { Uri.parse(entry.uri) }
    var playbackError by remember(entry.id) { mutableStateOf<PlaybackException?>(null) }
    // Resolved here: onClick lambdas are not composable scopes.
    val openWithFailedMessage = stringResource(R.string.local_video_open_with_failed)
    val openWithLabel = stringResource(R.string.local_video_open_with)

    val appPreferences = LocalAppPreferences.current
    val preferencesState = LocalAppPreferencesState.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val viewConfiguration = LocalViewConfiguration.current
    var holdSpeedOverlayVisible by remember { mutableStateOf(false) }
    var isHoldSpeedActive by remember { mutableStateOf(false) }
    val activity = remember(context, view) { context.findLocalActivity() ?: view.context.findLocalActivity() }
    val pictureInPictureSupported = activity?.packageManager
        ?.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) == true
    val coroutineScope = rememberCoroutineScope()

    // --- controls -----------------------------------------------------------------------
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsLocked by remember { mutableStateOf(false) }
    var unlockButtonVisible by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var settingsDestination by remember { mutableStateOf(PlayerSettingsDestination.Root) }
    var controlsInteractionTick by remember { mutableIntStateOf(0) }
    var unlockButtonInteractionTick by remember { mutableIntStateOf(0) }

    // --- playback -----------------------------------------------------------------------
    val durationMsState = remember { mutableLongStateOf(0L) }
    var durationMs by durationMsState
    val positionMsState = remember { mutableLongStateOf(0L) }
    var positionMs by positionMsState
    val bufferedPositionMsState = remember { mutableLongStateOf(0L) }
    var bufferedPositionMs by bufferedPositionMsState
    val sliderPositionMsState = remember { mutableLongStateOf(0L) }
    var sliderPositionMs by sliderPositionMsState
    var isSeeking by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    val isPlayingState = remember { mutableStateOf(true) }
    var isPlaying by isPlayingState
    var playbackSpeed by remember { mutableFloatStateOf(preferencesState.playbackSpeed) }
    val showRemainingTime = preferencesState.playerShowRemainingTime
    val videoScaleMode = preferencesState.videoScaleMode
    val videoAspectRatioState = remember { mutableFloatStateOf(DEFAULT_VIDEO_ASPECT_RATIO) }
    var videoAspectRatio by videoAspectRatioState
    val isAudioOnlyState = remember { mutableStateOf(false) }
    val isEnteringPictureInPictureState = remember { mutableStateOf(false) }
    var isEnteringPictureInPicture by isEnteringPictureInPictureState
    val isPictureInPictureActiveState = remember { mutableStateOf(false) }
    var isPictureInPictureActive by isPictureInPictureActiveState
    val isClosingState = remember { mutableStateOf(false) }
    val restoreWindowUiState = remember { mutableStateOf<(() -> Unit)?>(null) }

    val exoPlayer = remember(entry.id) {
        // Same decoder-fallback need as the main player: the Unisoc hardware HEVC decoder
        // fails on some streams and without fallback playback dies instead of degrading.
        ExoPlayer.Builder(
            context,
            DefaultRenderersFactory(context).apply { setEnableDecoderFallback(true) },
        ).build().apply {
            val mediaItemBuilder = MediaItem.Builder().setUri(uri)
            // Re-apply the subtitle the user attached to this video last time, if any.
            // Older builds saved the SAF subtitle with only the temporary read grant,
            // which dies with the process; re-attaching then fails silently, so check
            // readability first and drop the stale reference instead.
            entry.subtitleUri.takeIf { it.isNotBlank() }?.let { saved ->
                val savedUri = Uri.parse(saved)
                val readable = runCatching {
                    context.contentResolver.openInputStream(savedUri)?.use { true } ?: false
                }.getOrDefault(false)
                when {
                    readable -> mediaItemBuilder.setSubtitleConfigurations(
                        listOf(localSubtitleConfiguration(context, savedUri)),
                    )
                    else -> {
                        AppLogger.w(
                            "HibikiLocalPlayback",
                            "[local.subtitles.stale_cleared] video=${entry.displayName}",
                        )
                        history.clearSubtitle(entry.id)
                    }
                }
            }
            setMediaItem(mediaItemBuilder.build())
            playWhenReady = true
            playbackParameters = PlaybackParameters(preferencesState.playbackSpeed)
            prepare()
            if (entry.positionMs > 0L) seekTo(entry.positionMs)
        }
    }

    fun openWithAnotherApp() {
        openVideoWithAnotherApp(
            context = context,
            uri = uri,
            errorMessage = openWithFailedMessage,
        )
    }

    fun keepControlsVisible() {
        if (controlsLocked) return
        controlsVisible = true
        controlsInteractionTick += 1
    }

    fun keepUnlockButtonVisible() {
        if (!controlsLocked) return
        unlockButtonVisible = true
        unlockButtonInteractionTick += 1
    }

    fun currentPlaybackPositionMs(): Long {
        val currentPlayerPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        return when {
            currentPlayerPositionMs > 0L -> currentPlayerPositionMs
            positionMs > 0L -> positionMs
            else -> sliderPositionMs.coerceAtLeast(0L)
        }
    }

    fun saveCurrentPlaybackProgress() {
        runCatching {
            history.updateProgress(
                id = entry.id,
                positionMs = currentPlaybackPositionMs(),
                durationMs = exoPlayer.duration.takeIf { it > 0L } ?: durationMs,
            )
        }
    }

    fun seekBy(deltaMs: Long) {
        var target = currentPlaybackPositionMs() + deltaMs
        target = if (durationMs > 0L) target.coerceIn(0L, durationMs) else target.coerceAtLeast(0L)
        exoPlayer.seekTo(target)
        positionMs = target
        sliderPositionMs = target
        keepControlsVisible()
    }

    // --- track selection ---------------------------------------------------------
    // These remember blocks reference keepControlsVisible() so must come AFTER the fun is
    // declared above. Rebuilt whenever ExoPlayer fires onTracksChanged.
    var currentTracks by remember { mutableStateOf<Tracks>(Tracks.EMPTY) }

    // Captions for the shared subtitle overlay: the lines of the active cue plus a short
    // history of cue activations, so the offset setting can shift them earlier/later. Same
    // onCues wiring as the streamed player (PlayerScreen).
    val subtitleLinesState = remember(entry.id) { mutableStateOf(emptyList<String>()) }
    val subtitleCueEventsState = remember(entry.id) {
        mutableStateOf(emptyList<Pair<Long, List<String>>>())
    }
    val currentSubtitleLines = remember(entry.id, preferencesState.subtitleOffsetMs) {
        derivedStateOf {
            val offsetMs = preferencesState.subtitleOffsetMs
            if (offsetMs == 0) {
                subtitleLinesState.value
            } else {
                // Positive offset = show later: the event only wins once position passes it.
                cueLinesAt(subtitleCueEventsState.value, positionMsState.longValue - offsetMs)
            }
        }
    }

    val audioTrackValues: List<SelectableValue> = remember(currentTracks) {
        val audioGroups = currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        audioGroups.flatMapIndexed { groupIdx, group ->
            (0 until group.length).map { trackIdx ->
                val format = group.getTrackFormat(trackIdx)
                val lang = format.language?.uppercase() ?: "Audio ${groupIdx + 1}"
                val label = buildString {
                    append(lang)
                    if (format.label != null) append(" (${format.label})")
                }
                SelectableValue(
                    id = "audio_${groupIdx}_$trackIdx",
                    label = label,
                    selected = group.isTrackSelected(trackIdx),
                    onClick = {
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(
                                TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIdx))
                            )
                            .build()
                        keepControlsVisible()
                    },
                )
            }
        }
    }

    val subtitleTrackValues: List<SelectableValue> = remember(currentTracks) {
        val textGroups = currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val noneSelected = textGroups.none { grp -> (0 until grp.length).any { grp.isTrackSelected(it) } }
        val offEntry = SelectableValue(
            id = "sub_off",
            label = "Off",
            selected = noneSelected,
            onClick = {
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                keepControlsVisible()
            },
        )
        val tracks = textGroups.flatMapIndexed { groupIdx, group ->
            (0 until group.length).map { trackIdx ->
                val format = group.getTrackFormat(trackIdx)
                val lang = format.language?.uppercase() ?: "Sub ${groupIdx + 1}"
                val label = buildString {
                    append(lang)
                    if (format.label != null) append(" (${format.label})")
                }
                SelectableValue(
                    id = "sub_${groupIdx}_$trackIdx",
                    label = label,
                    selected = group.isTrackSelected(trackIdx),
                    onClick = {
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(
                                TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIdx))
                            )
                            .build()
                        keepControlsVisible()
                    },
                )
            }
        }
        listOf(offEntry) + tracks
    }

    // Declared before the picker so the SAF result callback can call it: a local function is
    // not in scope for lambdas created before its declaration.
    fun addExternalSubtitle(subUri: Uri) {
        val subConf = localSubtitleConfiguration(context, subUri)
        val newItem = MediaItem.Builder().setUri(uri).setSubtitleConfigurations(listOf(subConf)).build()
        val pos = exoPlayer.currentPosition
        exoPlayer.setMediaItem(newItem, pos)
        // setMediaItem(..., pos) re-prepares on its own. A stale "subtitles off" state
        // (track type disabled, e.g. from the Off entry in the settings sheet) survives the
        // re-prepare and would keep the new track hidden: re-enable text tracks and clear
        // overrides so the DEFAULT selection flag picks the fresh track.
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        history.setSubtitle(entry.id, subUri.toString())
        AppLogger.d(
            "HibikiLocalPlayback",
            "[local.subtitles.added] label=${subConf.label} mime=${subConf.mimeType} " +
                "pos=${pos}ms uri=${subUri}",
        )
        keepControlsVisible()
    }

    // SAF launcher for sideloading a subtitle file (SRT/ASS/VTT). The subtitle is attached to a
    // rebuilt media item and re-prepared in place; a previously disabled text-track state ("Off"
    // chosen earlier) is cleared so the fresh track actually gets selected and rendered.
    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { subUri ->
        if (subUri == null) return@rememberLauncherForActivityResult
        // SAF grants from the document picker are temporary (they die with the process),
        // but the subtitle is re-attached from history on the next open of this video.
        // Take a persistable read grant, the same way rememberVideoPicker does for the
        // video document itself, or the re-applied subtitle silently stops loading.
        runCatching {
            context.applicationContext.contentResolver.takePersistableUriPermission(
                subUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure {
            AppLogger.w(
                "HibikiLocalPlayback",
                "[local.subtitles.persist_failed] ${it.javaClass.simpleName}: ${it.message}",
            )
        }
        addExternalSubtitle(subUri)
    }

    DisposableEffect(entry.id, exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = error
                // Carries the subtitle re-prepare failures that otherwise vanish silently
                // (unreadable SAF uri after a restart, unparseable file, ...).
                AppLogger.w(
                    "HibikiLocalPlayback",
                    "[local.playback.error] ${error.errorCodeName}: ${error.message}",
                    error,
                )
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
            }

            override fun onPlaybackParametersChanged(params: PlaybackParameters) {
                if (!isHoldSpeedActive) playbackSpeed = params.speed
            }

            // FIT / crop need the real shape of the file, otherwise a 4:3 clip gets letterboxed
            // against the assumed 16:9.
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                currentTracks = tracks
                val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                if (textGroups.isNotEmpty()) {
                    AppLogger.d(
                        "HibikiLocalPlayback",
                        "[local.subtitles.tracks] textGroups=${textGroups.size}",
                    )
                }
                // When the user toggles all text tracks off, drop the stale caption lines so
                // the overlay goes quiet instead of showing the last cue forever.
                val anyTextSelected = textGroups.any { grp ->
                    (0 until grp.length).any { grp.isTrackSelected(it) }
                }
                if (!anyTextSelected) {
                    subtitleLinesState.value = emptyList()
                    subtitleCueEventsState.value = emptyList()
                }
            }

            // Feeds the styled caption overlay (ExoPlayer fires this from its text renderer
            // regardless of the built-in SubtitleView being hidden).
            override fun onCues(cueGroup: CueGroup) {
                val visibleLines = cueGroup.cues.mapNotNull { cue ->
                    cue.text?.toString()?.trim()?.takeIf(String::isNotBlank)
                }.distinct()
                subtitleLinesState.value = visibleLines
                // Capture the moment these cues became active so the offset slider can shift
                // them earlier/later (same history approach as the streamed player).
                subtitleCueEventsState.value =
                    (subtitleCueEventsState.value + (positionMsState.longValue to visibleLines))
                        .takeLast(128)
            }
        }
        exoPlayer.addListener(listener)
        // Snapshot once immediately in case tracks are already known (e.g. resume).
        currentTracks = exoPlayer.currentTracks
        onDispose {
            exoPlayer.removeListener(listener)
            saveCurrentPlaybackProgress()
            exoPlayer.release()
        }
    }

    val doubleTapSeek = remember(exoPlayer, coroutineScope, entry.id) {
        PlayerDoubleTapSeekController(
            exoPlayer = exoPlayer,
            coroutineScope = coroutineScope,
            seekIncrementMs = SEEK_INCREMENT_MS,
            currentPlaybackPositionMs = { currentPlaybackPositionMs() },
            currentDurationMs = { durationMs },
            onSeekCommitted = { targetPositionMs ->
                positionMs = targetPositionMs
                sliderPositionMs = targetPositionMs
            },
        )
    }

    // A single local file stands in for the episode list here: both arrows seek instead of
    // switching episodes, which keeps the control row identical to the streamed player.
    val hasPreviousEpisode = true
    val hasNextEpisode = true

    BackHandler(onBack = {
        saveCurrentPlaybackProgress()
        onBackClick()
    })

    PlayerLifecycleEffect(
        lifecycleOwner = lifecycleOwner,
        exoPlayer = exoPlayer,
        isClosing = isClosingState,
        isEnteringPictureInPicture = isEnteringPictureInPictureState,
        isPictureInPictureActive = isPictureInPictureActiveState,
        isAudioOnly = isAudioOnlyState,
        positionMs = positionMsState,
        sliderPositionMs = sliderPositionMsState,
        currentPlaybackPositionMs = { currentPlaybackPositionMs() },
        saveCurrentPlaybackProgress = { saveCurrentPlaybackProgress() },
        saveCurrentVideoFrame = {},
    )

    PlayerImmersiveModeEffect(
        activity = activity,
        view = view,
        restoreWindowUi = restoreWindowUiState,
    )

    PlayerPictureInPictureEffects(
        context = context,
        activity = activity,
        exoPlayer = exoPlayer,
        currentEpisodeId = entry.id,
        hasPreviousEpisode = hasPreviousEpisode,
        hasNextEpisode = hasNextEpisode,
        isPlaying = isPlaying,
        isAudioOnly = isAudioOnlyState,
        isPictureInPictureActive = isPictureInPictureActiveState,
        onPreviousEpisode = { seekBy(-SEEK_INCREMENT_MS) },
        onNextEpisode = { seekBy(SEEK_INCREMENT_MS) },
    )

    PlayerPlaybackPositionPollingEffect(
        exoPlayer = exoPlayer,
        isSeeking = isSeeking,
        durationMs = durationMsState,
        bufferedPositionMs = bufferedPositionMsState,
        positionMs = positionMsState,
        sliderPositionMs = sliderPositionMsState,
    )

    AutoHideVisibilityEffect(
        enabled = !controlsLocked,
        visible = controlsVisible,
        interactionTick = controlsInteractionTick,
        blocked = settingsVisible || isBuffering || playbackError != null || isSeeking,
        onHide = { controlsVisible = false },
    )

    AutoHideVisibilityEffect(
        enabled = controlsLocked,
        visible = unlockButtonVisible,
        interactionTick = unlockButtonInteractionTick,
        blocked = isSeeking,
        onHide = { unlockButtonVisible = false },
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(settingsVisible, controlsLocked, playbackSpeed, durationMs, entry.id) {
                if (settingsVisible) return@pointerInput
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = true)
                    var upPosition = firstDown.position
                    var upEventTimeMs = firstDown.uptimeMillis
                    var holdSpeedActive = false
                    var holdSpeedEligible = !controlsLocked
                    val holdSpeedDeadlineMs = firstDown.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                    holdSpeedOverlayVisible = false

                    while (true) {
                        val event = if (holdSpeedEligible && !holdSpeedActive) {
                            val remainingTimeoutMs = (holdSpeedDeadlineMs - SystemClock.uptimeMillis())
                                .coerceAtLeast(1L)
                            withTimeoutOrNull(remainingTimeoutMs) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            holdSpeedActive = true
                            isHoldSpeedActive = true
                            holdSpeedOverlayVisible = true
                            exoPlayer.playbackParameters = PlaybackParameters(2f)
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == firstDown.id } ?: break
                        if (!change.pressed) {
                            upPosition = change.position
                            upEventTimeMs = change.uptimeMillis
                            break
                        }
                        val totalDragX = change.position.x - firstDown.position.x
                        val totalDragY = change.position.y - firstDown.position.y
                        val movedTooFarForHold =
                            kotlin.math.abs(totalDragX) >= viewConfiguration.touchSlop ||
                                kotlin.math.abs(totalDragY) >= viewConfiguration.touchSlop
                        if (movedTooFarForHold) holdSpeedEligible = false
                    }

                    if (holdSpeedActive) {
                        holdSpeedOverlayVisible = false
                        isHoldSpeedActive = false
                        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
                        return@awaitEachGesture
                    }

                    // A committed double-tap seek keeps absorbing taps until the queue drains.
                    if (doubleTapSeek.accumulatedSteps > 0) {
                        if (controlsLocked) {
                            keepUnlockButtonVisible()
                        } else if (controlsVisible) {
                            keepControlsVisible()
                        }
                        doubleTapSeek.schedule(
                            direction = if (upPosition.x < size.width / 2f) -1 else 1,
                            eventTimeMs = upEventTimeMs,
                        )
                        return@awaitEachGesture
                    }

                    val secondDown = withTimeoutOrNull(DOUBLE_TAP_TIMEOUT_MS) {
                        awaitFirstDown(requireUnconsumed = true)
                    }
                    if (secondDown != null) {
                        val secondUp = waitForUpOrCancellation() ?: return@awaitEachGesture
                        val tapOffset = secondDown.position
                        if (controlsLocked) {
                            keepUnlockButtonVisible()
                        } else if (controlsVisible) {
                            keepControlsVisible()
                        }
                        doubleTapSeek.schedule(
                            direction = if (tapOffset.x < size.width / 2f) -1 else 1,
                            eventTimeMs = secondUp.uptimeMillis,
                        )
                    } else if (controlsLocked) {
                        unlockButtonVisible = !unlockButtonVisible
                        if (unlockButtonVisible) unlockButtonInteractionTick += 1
                    } else if (controlsVisible) {
                        controlsVisible = false
                    } else {
                        controlsVisible = true
                        controlsInteractionTick += 1
                    }
                }
            },
    ) {
        AndroidView(
            factory = { viewContext ->
                (LayoutInflater.from(viewContext)
                    .inflate(R.layout.view_media3_player, null, false) as PlayerView)
                    .apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        useController = false
                        keepScreenOn = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        player = exoPlayer
                        applyVideoScale(videoScaleMode, videoAspectRatio)
                        // Captions render in the shared PlayerSubtitleOverlay (which honors the
                        // user's styling settings), so keep ExoPlayer's built-in view out of it.
                        subtitleView?.visibility = View.GONE
                    }
            },
            update = { playerView ->
                playerView.player = if (playbackError != null) null else exoPlayer
                playerView.applyVideoScale(videoScaleMode, videoAspectRatio)
                playerView.subtitleView?.visibility = View.GONE
            },
            modifier = Modifier.fillMaxSize(),
        )

        // The styled caption overlay needs to know whether any text track is selected; the
        // same flag feeds the bottom bar's subtitle icon, so compute it once.
        val subtitleTrackSelected = currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .any { grp -> (0 until grp.length).any { grp.isTrackSelected(it) } }

        AnimatedVisibility(
            visible = !isPictureInPictureActive &&
                !controlsLocked &&
                (controlsVisible || playbackError != null),
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(animationSpec = tween(160)),
            exit = fadeOut(animationSpec = tween(180)),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                PlayerTopOverlay(
                    title = entry.displayName,
                    subtitle = "",
                    titleVisible = true,
                    onBackClick = {
                        saveCurrentPlaybackProgress()
                        onBackClick()
                    },
                    onPlaylistClick = {},
                    playlistEnabled = false,
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                // "Open with another app" sits where the playlist button lives on the streamed
                // player, so the top bar reads the same in both.
                AppFilledIconButton(
                    onClick = {
                        keepControlsVisible()
                        openWithAnotherApp()
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    style = AppFilledIconButtonStyle.DarkOverlay,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = openWithLabel,
                        tint = Color.White,
                    )
                }

                AnimatedVisibility(
                    visible = !isSeeking &&
                        !(doubleTapSeek.overlayVisible && doubleTapSeek.accumulatedSteps > 0) &&
                        !isBuffering &&
                        playbackError == null,
                    modifier = Modifier.align(Alignment.Center),
                    enter = fadeIn(animationSpec = tween(120)),
                    exit = fadeOut(animationSpec = tween(90)),
                ) {
                    PlayerCenterControls(
                        isPlaying = isPlaying,
                        hasPreviousEpisode = hasPreviousEpisode,
                        hasNextEpisode = hasNextEpisode,
                        onTogglePlay = {
                            keepControlsVisible()
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        onPreviousEpisode = { seekBy(-SEEK_INCREMENT_MS) },
                        onNextEpisode = { seekBy(SEEK_INCREMENT_MS) },
                    )
                }

                PlayerBottomOverlay(
                    durationMs = durationMs,
                    bufferedPositionMs = bufferedPositionMs,
                    sliderPositionMs = sliderPositionMs,
                    showRemainingTime = showRemainingTime,
                    onTimeLabelClick = {
                        appPreferences.setPlayerShowRemainingTime(!showRemainingTime)
                        keepControlsVisible()
                    },
                    onSliderValueChange = { newValue ->
                        keepControlsVisible()
                        isSeeking = true
                        sliderPositionMs = newValue
                    },
                    onSliderValueChangeFinished = {
                        keepControlsVisible()
                        doubleTapSeek.reset()
                        exoPlayer.seekTo(sliderPositionMs)
                        positionMs = sliderPositionMs
                        isSeeking = false
                    },
                    videoScaleMode = videoScaleMode,
                    onVideoScaleModeClick = {
                        keepControlsVisible()
                        appPreferences.setVideoScaleMode(videoScaleMode.next())
                    },
                    // Reflect the actual subtitle state from ExoPlayer.
                    // True = at least one text track is selected and not disabled.
                    subtitlesEnabled = subtitleTrackSelected,
                    onSubtitlesClick = {
                        // Toggle: if any text track is active, disable all; otherwise re-enable
                        // the default (ExoPlayer will pick the best available).
                        val anyActive = currentTracks.groups
                            .filter { it.type == C.TRACK_TYPE_TEXT }
                            .any { grp -> (0 until grp.length).any { grp.isTrackSelected(it) } }
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, anyActive)
                            .build()
                        keepControlsVisible()
                    },
                    onSubtitlesLongClick = {
                        // Long-press opens the subtitles page in the settings sheet
                        settingsDestination = PlayerSettingsDestination.Subtitles
                        settingsVisible = true
                        keepControlsVisible()
                    },
                    settingsEnabled = true,
                    onSettingsClick = {
                        keepControlsVisible()
                        settingsDestination = PlayerSettingsDestination.Root
                        settingsVisible = true
                    },
                    pictureInPictureEnabled = pictureInPictureSupported,
                    onPictureInPictureClick = {
                        isEnteringPictureInPicture = true
                        controlsVisible = false
                        val entered = runCatching {
                            activity?.enterPictureInPictureMode(
                                buildPictureInPictureParams(
                                    context = context,
                                    isAudioOnly = isAudioOnlyState.value,
                                    isPlaying = isPlaying,
                                    hasPreviousEpisode = hasPreviousEpisode,
                                    hasNextEpisode = hasNextEpisode,
                                ),
                            ) ?: false
                        }.getOrDefault(false)
                        isPictureInPictureActive = entered
                        if (!entered) isEnteringPictureInPicture = false
                    },
                    onLockClick = {
                        controlsLocked = true
                        controlsVisible = false
                        unlockButtonVisible = true
                        unlockButtonInteractionTick += 1
                        settingsVisible = false
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        // Styled captions (size, colors, background, outline, position, offset all come from
        // the shared subtitle settings), replacing the built-in SubtitleView. Lives OUTSIDE the
        // controls' AnimatedVisibility so captions keep showing in the controls-hidden
        // ("video only") mode.
        PlayerSubtitleOverlay(
            lines = currentSubtitleLines.value,
            visible = subtitleTrackSelected && !isAudioOnlyState.value,
            controlsVisible = controlsVisible,
            textColor = Color(preferencesState.subtitleTextColor),
            textScale = preferencesState.subtitleTextScale,
            backgroundColor = Color(preferencesState.subtitleBackgroundColor),
            backgroundOpacity = preferencesState.subtitleBackgroundOpacity,
            positionFraction = preferencesState.subtitlePosition,
            outlineColor = Color(preferencesState.subtitleOutlineColor),
            outlineWidthDp = preferencesState.subtitleOutlineWidthDp.toFloat(),
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        AnimatedVisibility(
            visible = doubleTapSeek.overlayVisible && doubleTapSeek.overlayDeltaMs != 0L,
            modifier = Modifier
                .align(
                    if (doubleTapSeek.overlayDeltaMs < 0L) Alignment.CenterStart
                    else Alignment.CenterEnd,
                )
                .padding(horizontal = 56.dp),
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(180)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (doubleTapSeek.overlayDeltaMs < 0L) {
                    AnimatedSeekOverlayArrow(
                        direction = -1,
                        pulse = doubleTapSeek.arrowPulse,
                    )
                }
                AnimatedContent(
                    targetState = doubleTapSeek.overlayDeltaMs,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(100)) togetherWith
                            fadeOut(animationSpec = tween(100)) using
                            SizeTransform(clip = false) { _, _ -> tween(durationMillis = 0) }
                    },
                    label = "LocalDoubleTapSeekLabel",
                ) { deltaMs ->
                    Text(
                        text = buildSeekDeltaLabel(deltaMs),
                        color = Color.White,
                        style = androidx.compose.material3.MaterialTheme.typography.headlineMedium.copy(
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.7f),
                                offset = Offset(2f, 2f),
                                blurRadius = 10f,
                            ),
                        ),
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (doubleTapSeek.overlayDeltaMs > 0L) {
                    AnimatedSeekOverlayArrow(
                        direction = 1,
                        pulse = doubleTapSeek.arrowPulse,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsLocked && unlockButtonVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            enter = fadeIn(animationSpec = tween(160)),
            exit = fadeOut(animationSpec = tween(120)),
        ) {
            PlayerUnlockButton(
                onClick = {
                    controlsLocked = false
                    unlockButtonVisible = false
                    keepControlsVisible()
                },
            )
        }

        if (isBuffering && playbackError == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = Color.White,
                    strokeWidth = 3.dp,
                )
            }
        }

        playbackError?.let {
            PlaybackErrorOverlay(
                onOpenWithAnotherApp = { openWithAnotherApp() },
                onBackClick = onBackClick,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = holdSpeedOverlayVisible,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp)
                .zIndex(3f),
            enter = fadeIn(animationSpec = tween(140)) + scaleIn(initialScale = 0.92f, animationSpec = tween(140)),
            exit = fadeOut(animationSpec = tween(160)) + scaleOut(targetScale = 0.96f, animationSpec = tween(160)),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.72f),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "2×",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Icon(
                        imageVector = Icons.Filled.FastForward,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 3.dp)
                            .size(19.dp),
                        tint = Color.White,
                    )
                }
            }
        }

        if (settingsVisible) {
            PlayerOverlayPanel(
                onDismissRequest = {
                    settingsVisible = false
                    settingsDestination = PlayerSettingsDestination.Root
                    keepControlsVisible()
                },
                widthFraction = PLAYER_SETTINGS_PANEL_WIDTH_FRACTION,
                maxWidth = PLAYER_SETTINGS_SHEET_MAX_WIDTH,
                restingOffsetY = PLAYER_SETTINGS_PANEL_RESTING_OFFSET_Y,
                swipeToDismissEnabled = false,
            ) { dismissPanel ->
                PlayerSettingsSheet(
                    destination = settingsDestination,
                    selectedSpeed = playbackSpeed,
                    selectedSourceId = entry.id,
                    selectedPlayerName = null,
                    selectedQualityLabel = null,
                    availableQualityLabels = emptyList(),
                    // Built from ExoPlayer's currentTracks; empty until the file is prepared.
                    subtitleValues = subtitleTrackValues,
                    audioTrackValues = audioTrackValues,
                    autoSkipSegments = preferencesState.autoSkipSegments,
                    autoPlayNextEpisode = preferencesState.autoPlayNextEpisode,
                    options = PlaybackSettingsOptions(),
                    onNavigate = {
                        settingsDestination = it
                        keepControlsVisible()
                    },
                    onBack = {
                        settingsDestination = PlayerSettingsDestination.Root
                        keepControlsVisible()
                    },
                    onSelectSpeed = { speed ->
                        keepControlsVisible()
                        playbackSpeed = speed
                        appPreferences.setPlaybackSpeed(speed)
                        exoPlayer.playbackParameters = PlaybackParameters(speed)
                    },
                    onSelectVoiceover = { keepControlsVisible() },
                    onSelectPlayer = { keepControlsVisible() },
                    onSelectQuality = { keepControlsVisible() },
                    onAddCustomSubtitle = {
                        // SAF picker — "text/x-subrip" is the canonical SRT MIME; add wildcards
                        // so Files apps that send "application/octet-stream" still appear.
                        subtitlePickerLauncher.launch(arrayOf(
                            "text/x-subrip", "application/x-subrip",
                            "text/vtt", "text/x-ssa", "application/x-ass",
                            "text/*", "*/*",
                        ))
                        keepControlsVisible()
                    },
                    onTranslateSubtitle = { keepControlsVisible() },
                    onSearchSubtitle = { keepControlsVisible() },
                    onAutoSkipSegmentsChange = { keepControlsVisible() },
                    onAutoPlayNextEpisodeChange = { keepControlsVisible() },
                    // Audio track selector is shown when the file has > 1 audio track.
                    // Episode-level options (auto-play-next, auto-skip) are hidden.
                    // Subtitles page is ALWAYS shown: it hosts "Add subtitle file", which must
                    // be reachable for MP4/MKV files that carry no embedded subtitle tracks.
                    showSubtitles = true,
                    showEpisodeOptions = false,
                )
            }
        }
    }
}

@Composable
private fun PlaybackErrorOverlay(
    onOpenWithAnotherApp: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.local_video_playback_failed),
            color = Color.White,
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            modifier = Modifier.widthIn(max = 420.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBackClick) {
                Text(stringResource(R.string.action_cancel))
            }
            TextButton(onClick = onOpenWithAnotherApp) {
                Text(stringResource(R.string.local_video_open_with))
            }
        }
    }
}

private tailrec fun Context.findLocalActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findLocalActivity()
    else -> null
}

/**
 * Builds the [MediaItem.SubtitleConfiguration] for a SAF subtitle file, resolving the MIME type
 * the same way the main player does: the real display name first (the URI's last segment is
 * often a bare document id, not the file name), and the file content as a fallback when the
 * extension is missing or unknown. The chosen decoder must match the bytes, otherwise ExoPlayer
 * parses nothing and the "added" subtitle never appears.
 */
private fun localSubtitleConfiguration(context: Context, subUri: Uri): MediaItem.SubtitleConfiguration {
    val name = context.subtitleDisplayName(subUri)
    val headBytes = runCatching {
        context.contentResolver.openInputStream(subUri)?.use { input ->
            input.readNBytes(2048)
        }
    }.getOrNull()
    return MediaItem.SubtitleConfiguration.Builder(subUri)
        .setMimeType(resolveLocalSubtitleMime(name, headBytes))
        .setLabel(name)
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .build()
}

private fun Context.subtitleDisplayName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            cursor.getString(index)?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        ?: "Subtitle"
}

/** Extension wins when it names a supported format; otherwise sniff the head, defaulting to SRT. */
private fun resolveLocalSubtitleMime(name: String, headBytes: ByteArray?): String {
    val lower = name.lowercase()
    when {
        lower.endsWith(".ass") || lower.endsWith(".ssa") -> return MimeTypes.TEXT_SSA
        lower.endsWith(".vtt") -> return MimeTypes.TEXT_VTT
        lower.endsWith(".srt") -> return MimeTypes.APPLICATION_SUBRIP
    }
    val head = headBytes?.let { bytes ->
        when {
            // BOM-aware, mirrors SubtitleFormat.decodeText: some subtitle files ship UTF-16.
            bytes.size > 1 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, Charsets.UTF_16LE)
            bytes.size > 1 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
        }
    }?.trimStart { it.isWhitespace() || it == '﻿' } ?: return MimeTypes.APPLICATION_SUBRIP
    return when {
        head.startsWith("WEBVTT") -> MimeTypes.TEXT_VTT
        head.contains("[Script Info]", ignoreCase = true) ||
            head.contains("[V4+ Styles]", ignoreCase = true) -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}
