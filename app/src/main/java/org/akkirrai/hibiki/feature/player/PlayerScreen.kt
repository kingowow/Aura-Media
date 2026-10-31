package org.akkirrai.hibiki.feature.player

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
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import org.akkirrai.hibiki.core.subtitle.SubtitleSearchResult
import org.akkirrai.hibiki.core.web.WebViewActivity
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset

@Composable
fun PlayerScreen(
    sourceId: String,
    episodeId: String,
    episodeNumberHint: Double? = null,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModel.Factory(
            sourceId = sourceId,
            episodeId = episodeId,
            initialEpisodeNumber = episodeNumberHint,
            appContext = LocalContext.current,
        )
    ),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(sourceId, episodeId) {
        AppLogger.d(
            PLAYBACK_LOG_TAG,
            "[player.screen.composed] sourceId=$sourceId episodeId=$episodeId",
        )
    }
    val context = LocalContext.current
    val appPreferences = LocalAppPreferences.current
    val preferencesState = LocalAppPreferencesState.current
    val persianSubtitleState by viewModel.persianSubtitleState.collectAsState()
    val subtitleSearchState by viewModel.subtitleSearchState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val viewConfiguration = LocalViewConfiguration.current
    val activity = remember(context, view) {
        context.findActivity() ?: view.context.findActivity()
    }
    val pictureInPictureSupported = activity?.packageManager
        ?.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) == true
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsLocked by remember { mutableStateOf(false) }

    // 3fa (openInBrowser) results open their release page in a download-reporting web view;
    // when the viewer taps the file link the web view hands the URL back and the app fetches
    // + applies the subtitle itself.
    var pending3faResult by remember { mutableStateOf<SubtitleSearchResult?>(null) }
    val subtitleWebViewLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val target = pending3faResult
        pending3faResult = null
        if (target != null && result.resultCode == android.app.Activity.RESULT_OK) {
            val url = WebViewActivity.downloadUrl(result.data)
            if (url != null) {
                viewModel.applySubtitleFromDownload(target, url, target.pageUrl)
            }
        }
    }
    var unlockButtonVisible by remember { mutableStateOf(false) }
    var playlistVisible by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var settingsDestination by remember { mutableStateOf(PlayerSettingsDestination.Root) }
    // The subtitle-search drawer: opened from the banner or from the Subtitles panel.
    var subtitleSearchVisible by remember { mutableStateOf(false) }
    // Whether the drawer looks for a Persian subtitle or for one that will be translated.
    var subtitleSearchPurpose by remember { mutableStateOf(SubtitleSearchPurpose.Persian) }

    // Close the subtitle-search drawer automatically once a subtitle has been applied.
    LaunchedEffect(persianSubtitleState) {
        if (persianSubtitleState is PersianSubtitleState.Applied) {
            subtitleSearchVisible = false
            viewModel.dismissSubtitleSearch()
        }
    }
    var customSubtitle by remember(sourceId, episodeId) { mutableStateOf<LocalSubtitle?>(null) }
    // Path of the subtitle the player itself created by translating; only that entry gets the
    // trash button (a subtitle the user picked from storage must not be deleted by the app).
    var translatedSubtitlePath by remember(sourceId, episodeId) { mutableStateOf<String?>(null) }
    // The track the viewer picked last: switching subtitles off and on again returns to it, and the
    // next episode starts on the same language instead of the first track in the list.
    var lastSubtitleUrl by remember(state.currentSourceId, state.currentEpisodeId, state.playback?.streamUrl) {
        mutableStateOf<String?>(null)
    }
    var preferredSubtitleLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSubtitleUrl by remember(state.currentSourceId, state.currentEpisodeId, state.playback?.streamUrl) {
        val tracks = state.playback?.subtitles.orEmpty()
        mutableStateOf(
            (tracks.firstOrNull { preferredSubtitleLabel != null && it.label == preferredSubtitleLabel } ?: tracks.firstOrNull())?.url,
        )
    }
    LaunchedEffect(selectedSubtitleUrl) {
        val url = selectedSubtitleUrl ?: return@LaunchedEffect
        lastSubtitleUrl = url
        state.playback?.subtitles?.firstOrNull { it.url == url }?.let { preferredSubtitleLabel = it.label }
    }
    var preparedPlaybackKey by remember { mutableStateOf<String?>(null) }
    var controlsInteractionTick by remember { mutableIntStateOf(0) }
    var unlockButtonInteractionTick by remember { mutableIntStateOf(0) }
    val isPlayingState = remember { mutableStateOf(true) }
    var isPlaying by isPlayingState
    val isBufferingState = remember { mutableStateOf(false) }
    var isBuffering by isBufferingState
    var playbackSpeed by remember { mutableFloatStateOf(preferencesState.playbackSpeed) }
    val showRemainingTime = preferencesState.playerShowRemainingTime
    val durationMsState = remember { mutableLongStateOf(0L) }
    var durationMs by durationMsState
    val positionMsState = remember { mutableLongStateOf(0L) }
    var positionMs by positionMsState
    val bufferedPositionMsState = remember { mutableLongStateOf(0L) }
    var bufferedPositionMs by bufferedPositionMsState
    val sliderPositionMsState = remember { mutableLongStateOf(0L) }
    var sliderPositionMs by sliderPositionMsState
    val pendingSeekMsState = remember { mutableLongStateOf(0L) }
    var pendingSeekMs by pendingSeekMsState
    val isEnteringPictureInPictureState = remember { mutableStateOf(false) }
    var isEnteringPictureInPicture by isEnteringPictureInPictureState
    val isPictureInPictureActiveState = remember { mutableStateOf(false) }
    var isPictureInPictureActive by isPictureInPictureActiveState
    val isAudioOnlyState = remember { mutableStateOf(false) }
    var isAudioOnly by isAudioOnlyState
    val videoScaleMode = preferencesState.videoScaleMode
    val videoAspectRatioState = remember { mutableFloatStateOf(DEFAULT_VIDEO_ASPECT_RATIO) }
    var videoAspectRatio by videoAspectRatioState
    var isSeeking by remember { mutableStateOf(false) }
    val isClosingState = remember { mutableStateOf(false) }
    val playerPrepareStartedAt = remember { mutableLongStateOf(0L) }
    var isClosing by isClosingState
    var attachedPlayerView by remember { mutableStateOf<PlayerView?>(null) }
    val restoreWindowUiState = remember { mutableStateOf<(() -> Unit)?>(null) }
    var restoreWindowUi by restoreWindowUiState
    val autoSkipSegments = preferencesState.autoSkipSegments
    val autoPlayNextEpisode = preferencesState.autoPlayNextEpisode
    val subtitleTracksAvailable = state.playback?.subtitles?.isNotEmpty() == true || customSubtitle != null
    var subtitlesEnabled by remember(state.currentSourceId, state.currentEpisodeId, state.playback?.streamUrl) {
        mutableStateOf(subtitleTracksAvailable)
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val subtitle = LocalSubtitle(
                uri = it,
                label = context.subtitleDisplayName(it),
                mimeType = subtitleMimeType(context, it),
            )
            customSubtitle = subtitle
            selectedSubtitleUrl = subtitle.uri.toString()
            subtitlesEnabled = true
            settingsVisible = true
            settingsDestination = PlayerSettingsDestination.Subtitles
        }
    }
    val subtitleValues = buildList {
        add(
            SelectableValue(
                id = SUBTITLES_OFF_ID,
                label = stringResource(R.string.watch_player_subtitles_off),
                selected = selectedSubtitleUrl == null,
                onClick = {
                    selectedSubtitleUrl = null
                    subtitlesEnabled = false
                },
            )
        )
        state.playback?.subtitles?.forEachIndexed { index, subtitle ->
            add(
                SelectableValue(
                    id = subtitle.url,
                    label = subtitle.label?.takeIf(String::isNotBlank)
                        ?: subtitle.language?.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.watch_player_subtitles_track, index + 1),
                    selected = selectedSubtitleUrl == subtitle.url,
                    onClick = {
                        selectedSubtitleUrl = subtitle.url
                        subtitlesEnabled = true
                    },
                )
            )
        }
        customSubtitle?.let { subtitle ->
            val isTranslated = translatedSubtitlePath != null && subtitle.uri.path == translatedSubtitlePath
            add(
                SelectableValue(
                    id = subtitle.uri.toString(),
                    label = subtitle.label,
                    description = stringResource(R.string.watch_player_subtitles_custom),
                    selected = selectedSubtitleUrl == subtitle.uri.toString(),
                    onClick = {
                        selectedSubtitleUrl = subtitle.uri.toString()
                        subtitlesEnabled = true
                    },
                    onDelete = if (isTranslated) {
                        {
                            val removedUrl = subtitle.uri.toString()
                            viewModel.deleteTranslatedSubtitle(translatedSubtitlePath.orEmpty())
                            translatedSubtitlePath = null
                            customSubtitle = null
                            if (selectedSubtitleUrl == removedUrl) {
                                selectedSubtitleUrl = null
                                subtitlesEnabled = false
                            }
                        }
                    } else {
                        null
                    },
                )
            )
        }
    }
    val subtitleLinesState = remember { mutableStateOf(emptyList<String>()) }
    // Cue timeline for the subtitle time-offset preference: (timestampMs, lines), where an empty
    // entry is the event that clears the screen. Only consulted when the offset is non-zero.
    val subtitleCueEventsState = remember { mutableStateOf(emptyList<Pair<Long, List<String>>>()) }
    // Applies the Persian subtitle found by the floating message to this episode's player.
    // The "(translated)" suffix is added here rather than in the ViewModel so it follows the app
    // language; resolved above the effect because stringResource is not composable inside a
    // LaunchedEffect body.
    val subtitleApplyRequest: String? = when (val current = persianSubtitleState) {
        is PersianSubtitleState.Applied -> current.filePath
        else -> null
    }
    val subtitleApplyLabel = when (val current = persianSubtitleState) {
        is PersianSubtitleState.Applied ->
            if (current.isTranslation) {
                stringResource(R.string.subtitle_translated_track_label, current.label)
            } else {
                current.label
            }
        is PersianSubtitleState.Translating ->
            stringResource(R.string.subtitle_translated_track_label, current.label)
        else -> ""
    }
    LaunchedEffect(subtitleApplyRequest) {
        val filePath = subtitleApplyRequest ?: return@LaunchedEffect
        val subtitleUri = Uri.fromFile(java.io.File(filePath))
        translatedSubtitlePath = filePath
        customSubtitle = LocalSubtitle(
            uri = subtitleUri,
            label = subtitleApplyLabel,
            mimeType = MimeTypes.APPLICATION_SUBRIP,
        )
        selectedSubtitleUrl = subtitleUri.toString()
        subtitlesEnabled = true
    }
    LaunchedEffect(preferencesState.playbackSpeed) {
        playbackSpeed = preferencesState.playbackSpeed
    }
    val handledEndedEpisodeIdState = remember { mutableStateOf<String?>(null) }
    var handledEndedEpisodeId by handledEndedEpisodeIdState
    // Which of the two timers applies is decided by autoSkipSegments, exactly as on desktop: with
    // auto-skip on it is "how long before the player skips for you", with it off it is "how long
    // the skip button stays on screen before it goes away by itself".
    val skipSegmentTimeoutSeconds = if (autoSkipSegments) {
        preferencesState.autoSkipDelaySeconds
    } else {
        preferencesState.skipButtonTimeoutSeconds
    }
    var skipCountdownSeconds by remember { mutableIntStateOf(skipSegmentTimeoutSeconds) }
    var hiddenSkipSegmentKey by remember { mutableStateOf<String?>(null) }
    var holdSpeedOverlayVisible by remember { mutableStateOf(false) }
    val watchedSeconds = remember(state.currentSourceId, state.currentEpisodeId) { mutableSetOf<Long>() }
    val lastTrackedPlaybackPositionMsState = remember(state.currentSourceId, state.currentEpisodeId) { mutableLongStateOf(-1L) }
    var lastTrackedPlaybackPositionMs by lastTrackedPlaybackPositionMsState
    val coroutineScope = rememberCoroutineScope()
    val resumeFrameRepository = remember(context) { ResumeFrameRepository(context) }
    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context, LegacyTextRenderersFactory(context))
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        MIN_BUFFER_MS,
                        MAX_BUFFER_MS,
                        BUFFER_FOR_PLAYBACK_MS,
                        BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                    )
                    .build()
            )
            .build()
            .apply {
                playWhenReady = true
            }
    }
    val mediaSession = remember(exoPlayer) {
        MediaSession.Builder(context, exoPlayer).build()
    }
    val hasNextEpisode = state.episodes.indexOfFirst { it.id == state.currentEpisodeId }
        .let { it != -1 && it < state.episodes.lastIndex }
    val hasPreviousEpisode = state.episodes.indexOfFirst { it.id == state.currentEpisodeId } > 0
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

    fun applyPlaybackSpeed(speed: Float) {
        exoPlayer.playbackParameters = PlaybackParameters(speed)
    }

    fun isInGestureArea(y: Float, height: Int): Boolean {
        // Interactive controls consume the pointer themselves. Any remaining free video area
        // should keep supporting player gestures even while controls are visible.
        return true
    }

    fun watchedSecondsSnapshot(): List<Long> = watchedSeconds.sorted()

    fun saveCurrentPlaybackProgress() {
        val safeDurationMs = exoPlayer.duration.takeIf { it > 0 } ?: durationMs
        val currentPlayerPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        val safePositionMs = when {
            currentPlayerPositionMs > 0L -> currentPlayerPositionMs
            positionMs > 0L -> positionMs
            else -> sliderPositionMs.coerceAtLeast(0L)
        }
        viewModel.savePlaybackProgress(
            positionMs = safePositionMs,
            durationMs = safeDurationMs,
            watchedSeconds = watchedSecondsSnapshot(),
        )
    }

    fun captureCurrentVideoFrame() =
        (attachedPlayerView?.videoSurfaceView as? TextureView)
            ?.takeIf(TextureView::isAvailable)
            ?.bitmap

    fun saveCurrentVideoFrame() {
        val frame = captureCurrentVideoFrame() ?: return
        val titleId = watchTitleIdFromSourceId(state.currentSourceId)
        coroutineScope.launch(Dispatchers.IO) {
            resumeFrameRepository.saveFrame(titleId, frame)
        }
    }

    fun currentPlaybackPositionMs(): Long {
        val currentPlayerPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        return when {
            currentPlayerPositionMs > 0L -> currentPlayerPositionMs
            positionMs > 0L -> positionMs
            else -> sliderPositionMs.coerceAtLeast(0L)
        }
    }

    val doubleTapSeek = remember(exoPlayer) {
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

    fun skipToSegmentEnd(segment: PlaybackSegment) {
        doubleTapSeek.reset()
        keepControlsVisible()
        exoPlayer.seekTo(segment.endMs)
        positionMs = segment.endMs
        sliderPositionMs = segment.endMs
    }

    fun runPlaybackSwitch(
        preservePosition: Boolean = false,
        action: (resumePositionMs: Long) -> Unit,
    ) {
        keepControlsVisible()
        doubleTapSeek.reset()
        val resumePositionMs = if (preservePosition) currentPlaybackPositionMs() else 0L
        exoPlayer.pause()
        saveCurrentPlaybackProgress()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        action(resumePositionMs)
    }

    PlayerPictureInPictureEffects(
        context = context,
        activity = activity,
        exoPlayer = exoPlayer,
        currentEpisodeId = state.currentEpisodeId,
        hasPreviousEpisode = hasPreviousEpisode,
        hasNextEpisode = hasNextEpisode,
        isPlaying = isPlaying,
        isAudioOnly = isAudioOnlyState,
        isPictureInPictureActive = isPictureInPictureActiveState,
        onPreviousEpisode = { runPlaybackSwitch { viewModel.playPreviousEpisode() } },
        onNextEpisode = { runPlaybackSwitch { viewModel.playNextEpisode() } },
    )

    val handleBackClick = remember(exoPlayer, onBackClick, state.currentSourceId) {
        {
            if (isClosing) return@remember
            isClosing = true
            doubleTapSeek.reset()
            controlsVisible = false
            playlistVisible = false
            settingsVisible = false
            viewModel.savePlaybackProgress(
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L,
                watchedSeconds = watchedSecondsSnapshot(),
            )
            exoPlayer.playWhenReady = false
            val frame = captureCurrentVideoFrame()
            val titleId = watchTitleIdFromSourceId(state.currentSourceId)
            coroutineScope.launch {
                if (frame != null) {
                    withContext(Dispatchers.IO) {
                        resumeFrameRepository.saveFrame(titleId, frame)
                    }
                }
                attachedPlayerView?.player = null
                exoPlayer.clearVideoSurface()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                restoreWindowUi?.invoke()
                onBackClick()
            }
        }
    }

    BackHandler(onBack = handleBackClick)

    PlayerPlaybackListenerEffect(
        exoPlayer = exoPlayer,
        mediaSession = mediaSession,
        viewModel = viewModel,
        state = { state },
        autoPlayNextEpisode = autoPlayNextEpisode,
        videoAspectRatio = videoAspectRatioState,
        isPlaying = isPlayingState,
        isBuffering = isBufferingState,
        durationMs = durationMsState,
        pendingSeekMs = pendingSeekMsState,
        positionMs = positionMsState,
        sliderPositionMs = sliderPositionMsState,
        handledEndedEpisodeId = handledEndedEpisodeIdState,
        playerPrepareStartedAt = playerPrepareStartedAt,
        watchedSecondsSnapshot = { watchedSecondsSnapshot() },
        subtitleLines = subtitleLinesState,
        subtitleCueEvents = subtitleCueEventsState,
        onDisposed = { doubleTapSeek.cancelPending() },
    )

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
        saveCurrentVideoFrame = { saveCurrentVideoFrame() },
    )

    PlayerImmersiveModeEffect(
        activity = activity,
        view = view,
        restoreWindowUi = restoreWindowUiState,
    )

    PlayerMediaPreparationEffect(
        exoPlayer = exoPlayer,
        context = context,
        state = state,
        selectedSubtitleUrl = selectedSubtitleUrl,
        customSubtitle = customSubtitle,
        previousPlaybackKey = preparedPlaybackKey,
        onPlaybackPrepared = { preparedPlaybackKey = it },
        playbackSpeed = playbackSpeed,
        onPrepare = { playerPrepareStartedAt.longValue = SystemClock.elapsedRealtime() },
        keepControlsVisible = { keepControlsVisible() },
        onStartPositionApplied = {
            pendingSeekMs = 0L
            viewModel.consumePendingSeek()
        },
    )

    PlayerSubtitleSelectionEffect(
        exoPlayer = exoPlayer,
        playback = state.playback,
        selectedSubtitleUrl = selectedSubtitleUrl,
        customSubtitle = customSubtitle,
    )

    LaunchedEffect(state.pendingSeekMs, state.currentEpisodeId, state.currentSourceId) {
        pendingSeekMs = state.pendingSeekMs.coerceAtLeast(0L)
        handledEndedEpisodeId = null
    }

    LaunchedEffect(exoPlayer, playbackSpeed) {
        applyPlaybackSpeed(playbackSpeed)
    }

    PlayerPlaybackPositionPollingEffect(
        exoPlayer = exoPlayer,
        isSeeking = isSeeking,
        durationMs = durationMsState,
        bufferedPositionMs = bufferedPositionMsState,
        positionMs = positionMsState,
        sliderPositionMs = sliderPositionMsState,
    )

    PlayerWatchedSecondsTrackingEffect(
        exoPlayer = exoPlayer,
        currentSourceId = state.currentSourceId,
        currentEpisodeId = state.currentEpisodeId,
        isSeeking = isSeeking,
        lastTrackedPlaybackPositionMs = lastTrackedPlaybackPositionMsState,
        watchedSeconds = watchedSeconds,
    )

    PlayerProgressPersistenceEffect(
        exoPlayer = exoPlayer,
        currentSourceId = state.currentSourceId,
        currentEpisodeId = state.currentEpisodeId,
        onSaveProgress = { saveCurrentPlaybackProgress() },
    )

    AutoHideVisibilityEffect(
        enabled = !controlsLocked,
        visible = controlsVisible,
        interactionTick = controlsInteractionTick,
        blocked = playlistVisible || settingsVisible || state.isLoading || state.errorMessage != null || isSeeking,
        onHide = { controlsVisible = false },
    )

    AutoHideVisibilityEffect(
        enabled = controlsLocked,
        visible = unlockButtonVisible,
        interactionTick = unlockButtonInteractionTick,
        blocked = isSeeking,
        onHide = { unlockButtonVisible = false },
    )

    val rawActiveSkipSegment = state.playback?.segments
        ?.firstOrNull { segment -> positionMs >= segment.startMs && positionMs < segment.endMs }
        ?.takeIf {
            !controlsLocked &&
                !playlistVisible &&
                !settingsVisible &&
                state.errorMessage == null &&
                !state.isLoading
        }
    val activeSkipSegmentKey = rawActiveSkipSegment?.let { segment ->
        buildSkipSegmentKey(
            episodeId = state.currentEpisodeId,
            segment = segment,
        )
    }
    val activeSkipSegment = rawActiveSkipSegment
        ?.takeIf { activeSkipSegmentKey != null && hiddenSkipSegmentKey != activeSkipSegmentKey }

    LaunchedEffect(activeSkipSegmentKey, autoSkipSegments, skipSegmentTimeoutSeconds) {
        val key = activeSkipSegmentKey ?: run {
            skipCountdownSeconds = skipSegmentTimeoutSeconds
            return@LaunchedEffect
        }
        val segment = rawActiveSkipSegment ?: return@LaunchedEffect
        if (hiddenSkipSegmentKey == key) return@LaunchedEffect

        skipCountdownSeconds = skipSegmentTimeoutSeconds
        repeat(skipSegmentTimeoutSeconds) {
            delay(1_000L)
            if (hiddenSkipSegmentKey == key) return@LaunchedEffect
            skipCountdownSeconds = (skipCountdownSeconds - 1).coerceAtLeast(0)
        }

        if (autoSkipSegments && hiddenSkipSegmentKey != key) {
            skipToSegmentEnd(segment)
        } else if (!autoSkipSegments && hiddenSkipSegmentKey != key) {
            hiddenSkipSegmentKey = key
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(settingsVisible, playlistVisible, controlsLocked, playbackSpeed, durationMs) {
                if (settingsVisible || playlistVisible) return@pointerInput
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
                            holdSpeedOverlayVisible = true
                            applyPlaybackSpeed(2f)
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
                        applyPlaybackSpeed(playbackSpeed)
                        return@awaitEachGesture
                    }

                    if (doubleTapSeek.accumulatedSteps > 0) {
                        val tapOffset = upPosition
                        if (isInGestureArea(tapOffset.y, size.height)) {
                            if (controlsLocked) {
                                keepUnlockButtonVisible()
                            } else if (controlsVisible) {
                                keepControlsVisible()
                            }
                            doubleTapSeek.schedule(
                                direction = if (tapOffset.x < size.width / 2f) -1 else 1,
                                eventTimeMs = upEventTimeMs,
                            )
                        }
                        return@awaitEachGesture
                    }

                    val secondDown = withTimeoutOrNull(DOUBLE_TAP_TIMEOUT_MS) {
                        awaitFirstDown(requireUnconsumed = true)
                    }
                    if (secondDown != null) {
                        val secondUp = waitForUpOrCancellation()
                        if (secondUp == null) return@awaitEachGesture
                        val tapOffset = secondDown.position
                        if (!isInGestureArea(tapOffset.y, size.height)) {
                            if (controlsLocked) {
                                keepUnlockButtonVisible()
                            } else if (controlsVisible) {
                                controlsVisible = false
                            } else {
                                controlsVisible = true
                                controlsInteractionTick += 1
                            }
                            return@awaitEachGesture
                        }
                        if (controlsLocked) {
                            keepUnlockButtonVisible()
                        } else if (controlsVisible) {
                            keepControlsVisible()
                        }
                        doubleTapSeek.schedule(
                            direction = if (tapOffset.x < size.width / 2f) -1 else 1,
                            eventTimeMs = secondUp.uptimeMillis,
                        )
                    } else {
                        if (controlsLocked) {
                            unlockButtonVisible = !unlockButtonVisible
                            if (unlockButtonVisible) {
                                unlockButtonInteractionTick += 1
                            }
                            return@awaitEachGesture
                        }
                        if (controlsVisible) {
                            controlsVisible = false
                        } else {
                            controlsVisible = true
                            controlsInteractionTick += 1
                        }
                    }
                }
            }
    ) {
        if (!isClosing) {
            AndroidView(
                factory = { viewContext ->
                    (LayoutInflater.from(viewContext)
                        .inflate(R.layout.view_media3_player, null, false) as PlayerView)
                        .apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            player = exoPlayer
                            subtitleView?.visibility = View.GONE
                            applyVideoScale(videoScaleMode, videoAspectRatio)
                            attachedPlayerView = this
                        }
                },
                update = { playerView ->
                    attachedPlayerView = playerView
                    playerView.player = if (isAudioOnly) null else exoPlayer
                    playerView.subtitleView?.visibility = View.GONE
                    playerView.applyVideoScale(videoScaleMode, videoAspectRatio)
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        val currentSubtitleLines = remember(preferencesState.subtitleOffsetMs) {
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
        PlayerSubtitleOverlay(
            lines = currentSubtitleLines.value,
            visible = subtitlesEnabled && !isAudioOnly,
            controlsVisible = controlsVisible,
            textColor = Color(preferencesState.subtitleTextColor),
            textScale = preferencesState.subtitleTextScale,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        PersianSubtitleSearchBanner(
            state = persianSubtitleState,
            onRetry = viewModel::retryPersianSubtitle,
            onTranslate = {
                viewModel.translateFallbackSubtitle()
            },
            onSearch = {
                subtitleSearchPurpose = SubtitleSearchPurpose.Persian
                subtitleSearchVisible = true
            },
            onSearchForTranslation = {
                subtitleSearchPurpose = SubtitleSearchPurpose.ForTranslation
                subtitleSearchVisible = true
            },
            onDismiss = { viewModel.dismissPersianSubtitleMessage() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 52.dp)
                .zIndex(3f),
        )

        AnimatedVisibility(
            visible = holdSpeedOverlayVisible,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp)
                .zIndex(1f),
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                    label = "DoubleTapSeekLabel",
                ) { deltaMs ->
                    Text(
                        text = buildSeekDeltaLabel(deltaMs),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium.copy(
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

        activeSkipSegment?.let { skipSegment ->
            AnimatedVisibility(
                visible = !isPictureInPictureActive,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 24.dp,
                        bottom = if (controlsVisible) 140.dp else 32.dp,
                    ),
                enter = fadeIn(animationSpec = tween(140)),
                exit = fadeOut(animationSpec = tween(140)),
            ) {
                PlayerSkipSegmentOverlay(
                    countdownSeconds = skipCountdownSeconds,
                    countdownStartSeconds = skipSegmentTimeoutSeconds,
                    autoSkipEnabled = autoSkipSegments,
                    onSkipClick = {
                        skipToSegmentEnd(skipSegment)
                    },
                    onWatchClick = {
                        activeSkipSegmentKey?.let { hiddenSkipSegmentKey = it }
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = !isPictureInPictureActive &&
                !controlsLocked &&
                (controlsVisible || state.isLoading || state.errorMessage != null),
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(animationSpec = tween(160)),
            exit = fadeOut(animationSpec = tween(180)),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                PlayerTopOverlay(
                    title = state.animeTitle,
                    subtitle = currentEpisodeSubtitle(state),
                    titleVisible = !holdSpeedOverlayVisible,
                    onBackClick = handleBackClick,
                    onPlaylistClick = {
                        keepControlsVisible()
                        playlistVisible = true
                    },
                    playlistEnabled = state.episodes.isNotEmpty(),
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                AnimatedVisibility(
                    visible =
                        !isSeeking &&
                            !(doubleTapSeek.overlayVisible && doubleTapSeek.accumulatedSteps > 0) &&
                            !state.isLoading &&
                            !isBuffering &&
                            state.errorMessage == null,
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
                        onPreviousEpisode = {
                            runPlaybackSwitch { viewModel.playPreviousEpisode() }
                        },
                        onNextEpisode = {
                            runPlaybackSwitch { viewModel.playNextEpisode() }
                        },
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
                    subtitlesEnabled = subtitlesEnabled,
                    onSubtitlesClick = {
                        keepControlsVisible()
                        val nextSubtitleUrl = if (subtitlesEnabled) {
                            null
                        } else {
                            selectedSubtitleUrl
                                ?: lastSubtitleUrl
                                ?: state.playback?.subtitles?.firstOrNull()?.url
                                ?: customSubtitle?.uri?.toString()
                        }
                        selectedSubtitleUrl = nextSubtitleUrl
                        subtitlesEnabled = nextSubtitleUrl != null
                    },
                    onSubtitlesLongClick = {
                        keepControlsVisible()
                        settingsVisible = true
                        settingsDestination = PlayerSettingsDestination.Subtitles
                    },
                    settingsEnabled = true,
                    onSettingsClick = {
                        keepControlsVisible()
                        settingsDestination = PlayerSettingsDestination.Root
                        settingsVisible = true
                        viewModel.loadSettingsOptions()
                    },
                    pictureInPictureEnabled = pictureInPictureSupported && state.playback != null,
                    onPictureInPictureClick = {
                        isEnteringPictureInPicture = true
                        controlsVisible = false
                        val entered = runCatching {
                            activity?.enterPictureInPictureMode(
                                buildPictureInPictureParams(
                                    context = context,
                                    isAudioOnly = isAudioOnly,
                                    isPlaying = isPlaying,
                                    hasPreviousEpisode = hasPreviousEpisode,
                                    hasNextEpisode = hasNextEpisode,
                                )
                            ) ?: false
                        }.getOrDefault(false)
                        isPictureInPictureActive = entered
                        if (!entered) {
                            isEnteringPictureInPicture = false
                        }
                    },
                    onLockClick = {
                        controlsLocked = true
                        controlsVisible = false
                        unlockButtonVisible = true
                        unlockButtonInteractionTick += 1
                        playlistVisible = false
                        settingsVisible = false
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
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
                }
            )
        }

        if (state.isLoading || isBuffering) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = Color.White,
                    strokeWidth = 3.dp,
                )
            }
        }

        // A downloaded episode whose local file will not open gets its own message and action: the
        // network is not the problem there, and "Retry" would resolve the same broken download.
        // Re-downloading the episode is what can actually fix it.
        if (state.errorMessage != null || state.offlinePlaybackFailed) {
            val downloadedFileError = state.offlinePlaybackFailed
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .widthIn(max = 420.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.84f),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.watch_player_error_title),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = state.errorMessage
                                ?: stringResource(R.string.watch_player_downloaded_error_message),
                            color = Color.White.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(
                                if (downloadedFileError) {
                                    R.string.watch_player_redownload
                                } else {
                                    R.string.watch_player_retry
                                }
                            ),
                            modifier = Modifier
                                .alpha(0.92f)
                                .clickable {
                                    if (downloadedFileError) {
                                        viewModel.redownloadCurrentEpisode()
                                    } else {
                                        viewModel.load(forceRefresh = true)
                                    }
                                },
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        if (playlistVisible) {
            PlayerOverlayPanel(
                onDismissRequest = {
                    playlistVisible = false
                    keepControlsVisible()
                },
                widthFraction = PLAYER_PLAYLIST_PANEL_WIDTH_FRACTION,
                maxWidth = PLAYER_PLAYLIST_PANEL_MAX_WIDTH,
                swipeToDismissEnabled = false,
            ) { dismissPanel ->
                PlaylistBottomSheet(
                    currentEpisodeId = state.currentEpisodeId,
                    episodes = state.episodes,
                    onEpisodeClick = { episodeId ->
                        dismissPanel()
                        runPlaybackSwitch { viewModel.selectEpisode(episodeId) }
                    }
                )
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
                    selectedSourceId = state.currentSourceId,
                    selectedPlayerName = state.selectedPlayerName,
                    selectedQualityLabel = state.selectedQualityLabel ?: state.playback?.qualityLabel,
                    availableQualityLabels = state.availableQualityLabels,
                    subtitleValues = subtitleValues,
                    autoSkipSegments = autoSkipSegments,
                    autoPlayNextEpisode = autoPlayNextEpisode,
                    options = state.settingsOptions,
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
                        applyPlaybackSpeed(speed)
                    },
                    onSelectVoiceover = { source ->
                        runPlaybackSwitch(preservePosition = true) { resumePositionMs ->
                            viewModel.selectVoiceover(source, resumePositionMs)
                        }
                    },
                    onSelectPlayer = { playerName ->
                        runPlaybackSwitch(preservePosition = true) { resumePositionMs ->
                            viewModel.selectPlayer(playerName, resumePositionMs)
                        }
                    },
                    onSelectQuality = { quality ->
                        runPlaybackSwitch(preservePosition = true) { resumePositionMs ->
                            viewModel.selectQuality(quality, resumePositionMs)
                        }
                    },
                    onAddCustomSubtitle = {
                        subtitlePicker.launch(SUBTITLE_MIME_TYPES)
                    },
                    onSearchSubtitle = {
                        // Close the panel so the drawer has the whole screen.
                        dismissPanel()
                        keepControlsVisible()
                        subtitleSearchPurpose = SubtitleSearchPurpose.Persian
                        subtitleSearchVisible = true
                    },
                    onTranslateSubtitle = {
                        // Close the panel so the floating progress banner stays visible.
                        dismissPanel()
                        keepControlsVisible()
                        viewModel.translateFallbackSubtitle()
                    },
                    onAutoSkipSegmentsChange = { enabled ->
                        keepControlsVisible()
                        appPreferences.setAutoSkipSegments(enabled)
                    },
                    onAutoPlayNextEpisodeChange = { enabled ->
                        keepControlsVisible()
                        appPreferences.setAutoPlayNextEpisode(enabled)
                    },
                )
            }
        }

        if (subtitleSearchVisible) {
            PlayerOverlayPanel(
                onDismissRequest = {
                    subtitleSearchVisible = false
                    viewModel.dismissSubtitleSearch()
                    keepControlsVisible()
                },
                widthFraction = PLAYER_SUBTITLE_SEARCH_PANEL_WIDTH_FRACTION,
                maxWidth = PLAYER_SUBTITLE_SEARCH_PANEL_MAX_WIDTH,
                restingOffsetY = PLAYER_SUBTITLE_SEARCH_PANEL_RESTING_OFFSET_Y,
                swipeToDismissEnabled = false,
            ) {
                SubtitleSearchSheet(
                    state = subtitleSearchState,
                    initialQuery = viewModel.currentSubtitleSearchHint(),
                    purpose = subtitleSearchPurpose,
                    onSearch = { text, lang -> viewModel.searchSubtitles(text, subtitleSearchPurpose, lang) },
                    onSelectResult = { result ->
                        if (result.openInBrowser) {
                            // 3fa: open the release page in the download-reporting web view; the
                            // launcher above applies the tapped file when it comes back.
                            pending3faResult = result
                            subtitleWebViewLauncher.launch(
                                WebViewActivity.newIntentForDownload(context, result.pageUrl, result.pageUrl),
                            )
                        } else {
                            viewModel.selectSubtitleResult(result)
                        }
                    },
                    onSelectEntry = { listing, entry ->
                        viewModel.selectSubtitleArchiveEntry(listing, entry)
                    },
                    onBack = {
                        // Back from the archive list to the release list.
                        viewModel.dismissSubtitleSearch()
                        viewModel.searchSubtitles(
                            viewModel.currentSubtitleSearchHint(),
                            subtitleSearchPurpose,
                        )
                    },
                )
            }
        }
    }
}


@Composable
private fun PersianSubtitleSearchBanner(
    state: PersianSubtitleState,
    onRetry: () -> Unit,
    onTranslate: () -> Unit,
    onSearch: () -> Unit,
    onSearchForTranslation: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A successful apply gets out of the way on its own; actionable states stay until the user
    // presses a button or dismisses.
    if (state is PersianSubtitleState.Applied) {
        LaunchedEffect(state) {
            kotlinx.coroutines.delay(3000)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = state !is PersianSubtitleState.Idle,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(160)),
        exit = fadeOut(animationSpec = tween(140)),
    ) {
        // A compact card: the wide banner and the full-width progress bar made the player look
        // like it was busy even when it was only waiting for the viewer. The card now hugs its
        // content and the translation progress is a small ring in the corner.
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            shadowElevation = 10.dp,
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (state) {
                        is PersianSubtitleState.Translating -> {
                            // A small ring instead of a bar across the whole card.
                            Box(
                                modifier = Modifier.size(18.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    progress = { state.progressPercent / 100f },
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text(
                                text = stringResource(
                                    R.string.subtitle_translate_translating,
                                    state.progressPercent,
                                ),
                                modifier = Modifier
                                    .padding(start = 10.dp)
                                    .weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        is PersianSubtitleState.Applied -> {
                            Text(
                                text = stringResource(R.string.subtitle_search_applied),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        is PersianSubtitleState.TranslateSourceNotFound -> {
                            Text(
                                text = stringResource(R.string.subtitle_translate_source_not_found),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        is PersianSubtitleState.Failed -> {
                            Text(
                                text = stringResource(R.string.subtitle_search_error),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        is PersianSubtitleState.NotApplied -> {
                            Text(
                                text = stringResource(R.string.subtitle_not_applied),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        PersianSubtitleState.Idle -> Unit
                    }
                    // The close button: a real "b" (done) instead of a multiplication sign.
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.subtitle_search_dismiss),
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
                // The action row: translate / search / retry. Only the actions that make sense for
                // the current state are shown, so the card never offers a button that cannot work.
                if (state !is PersianSubtitleState.Applied && state !is PersianSubtitleState.Translating) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state is PersianSubtitleState.NotApplied) {
                            BannerActionButton(
                                text = stringResource(R.string.subtitle_search_translate),
                                icon = Icons.Outlined.Translate,
                                onClick = onTranslate,
                            )
                        }
                        BannerActionButton(
                            text = stringResource(R.string.subtitle_search_open),
                            icon = Icons.Outlined.Search,
                            onClick = onSearch,
                        )
                        if (state is PersianSubtitleState.TranslateSourceNotFound) {
                            // Nothing to translate was found: offer a search for a subtitle that
                            // can be translated.
                            BannerActionButton(
                                text = stringResource(R.string.subtitle_search_for_translation),
                                icon = Icons.Outlined.Translate,
                                onClick = onSearchForTranslation,
                            )
                        }
                        if (state is PersianSubtitleState.Failed) {
                            BannerActionButton(
                                text = stringResource(R.string.subtitle_action_retry),
                                icon = Icons.Outlined.Refresh,
                                onClick = onRetry,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A small pill button used by the banner's action row. */
@Composable
private fun BannerActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}


@Composable
private fun PlaylistBottomSheet(
    currentEpisodeId: String,
    episodes: List<WatchEpisode>,
    onEpisodeClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = PLAYER_PLAYLIST_SHEET_MAX_HEIGHT),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = UiDimens.ScreenPadding,
                top = 4.dp,
                end = UiDimens.ScreenPadding,
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(episodes, key = WatchEpisode::id) { episode ->
                EpisodeSheetRow(
                    episode = episode,
                    selected = episode.id == currentEpisodeId,
                    onClick = { onEpisodeClick(episode.id) }
                )
            }
        }
    }
}

@Composable
private fun EpisodeSheetRow(
    episode: WatchEpisode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val titleColor = if (selected) {
        Color.White
    } else {
        Color.White.copy(alpha = 0.92f)
    }
    val subtitleColor = if (selected) {
        Color.White
    } else {
        Color.White.copy(alpha = 0.72f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .clip(RoundedCornerShape(18.dp)),
        color = if (selected) {
            Color.White.copy(alpha = 0.10f)
        } else {
            Color.White.copy(alpha = 0.03f)
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(
                text = buildEpisodeTitle(episode),
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            episode.title?.takeIf(String::isNotBlank)?.let { subtitle ->
                Text(
                    text = subtitle,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun buildEpisodeTitle(episode: WatchEpisode): String {
    val number = if (episode.number % 1.0 == 0.0) {
        episode.number.toInt().toString()
    } else {
        episode.number.toString()
    }
    return stringResource(R.string.watch_episode_number, number)
}

@Composable
private fun currentEpisodeSubtitle(state: PlayerUiState): String {
    val playbackTitle = state.playback?.episodeTitle.orEmpty().trim()
    if (playbackTitle.isNotBlank()) {
        return localizedEpisodeTitle(playbackTitle)
    }
    val currentEpisode = state.episodes.firstOrNull { it.id == state.currentEpisodeId }
        ?: return ""
    return buildEpisodeTitle(currentEpisode)
}

@Composable
private fun localizedEpisodeTitle(title: String): String {
    val fallbackEpisodeNumber = Regex("""^Episode\s+(.+)$""", RegexOption.IGNORE_CASE)
        .matchEntire(title.trim())
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf(String::isNotBlank)
    return if (fallbackEpisodeNumber != null) {
        stringResource(R.string.watch_episode_number, fallbackEpisodeNumber)
    } else {
        title
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun buildSkipSegmentKey(
    episodeId: String,
    segment: PlaybackSegment,
): String = "$episodeId:${segment.type}:${segment.startMs}:${segment.endMs}"

internal const val SEEK_INCREMENT_MS = 10_000L
internal const val DOUBLE_TAP_TIMEOUT_MS = 260L
private const val MIN_BUFFER_MS = 30_000
private const val MAX_BUFFER_MS = 60_000
private const val BUFFER_FOR_PLAYBACK_MS = 1_500
private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000

private const val PLAYBACK_LOG_TAG = "HibikiPlayback"
private const val WATCHED_SECONDS_TRACKING_MAX_DELTA_MS = 2_500L
private const val PLAYBACK_PROGRESS_SAVE_INTERVAL_MS = 30_000L


@Composable
internal fun PlayerLifecycleEffect(
    lifecycleOwner: LifecycleOwner,
    exoPlayer: ExoPlayer,
    isClosing: MutableState<Boolean>,
    isEnteringPictureInPicture: MutableState<Boolean>,
    isPictureInPictureActive: MutableState<Boolean>,
    isAudioOnly: MutableState<Boolean>,
    positionMs: MutableLongState,
    sliderPositionMs: MutableLongState,
    currentPlaybackPositionMs: () -> Long,
    saveCurrentPlaybackProgress: () -> Unit,
    saveCurrentVideoFrame: () -> Unit,
) {
    var lifecycleResumePositionMs by remember { mutableLongStateOf(0L) }
    var resumePlaybackAfterLifecyclePause by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    saveCurrentPlaybackProgress()
                    if (!isClosing.value) saveCurrentVideoFrame()
                    if (isEnteringPictureInPicture.value || isPictureInPictureActive.value || isAudioOnly.value) {
                        return@LifecycleEventObserver
                    }
                    lifecycleResumePositionMs = currentPlaybackPositionMs()
                    resumePlaybackAfterLifecyclePause = exoPlayer.isPlaying
                    exoPlayer.pause()
                }

                Lifecycle.Event.ON_STOP -> saveCurrentPlaybackProgress()

                Lifecycle.Event.ON_RESUME -> {
                    isEnteringPictureInPicture.value = false
                    isPictureInPictureActive.value = false
                    isAudioOnly.value = false
                    val resumePositionMs = lifecycleResumePositionMs
                    if (resumePositionMs > 0L) {
                        exoPlayer.seekTo(resumePositionMs)
                        positionMs.longValue = resumePositionMs
                        sliderPositionMs.longValue = resumePositionMs
                        lifecycleResumePositionMs = 0L
                    }
                    if (resumePlaybackAfterLifecyclePause) {
                        exoPlayer.play()
                    }
                    resumePlaybackAfterLifecyclePause = false
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Composable
private fun PlayerMediaPreparationEffect(
    exoPlayer: ExoPlayer,
    context: Context,
    state: PlayerUiState,
    selectedSubtitleUrl: String?,
    customSubtitle: LocalSubtitle?,
    previousPlaybackKey: String?,
    onPlaybackPrepared: (String) -> Unit,
    playbackSpeed: Float,
    onPrepare: () -> Unit,
    keepControlsVisible: () -> Unit,
    onStartPositionApplied: () -> Unit,
) {
    // A track's format is learned once per URL for the lifetime of this screen: sniffing costs
    // a network round-trip, and a progressive translation re-applies its track often.
    val sniffedSubtitleMimeTypes = remember { mutableStateOf(emptyMap<String, String>()) }
    // A track choice is applied independently below. Recreating the source for every subtitle
    // toggle made the video buffer and resume from the start.
    LaunchedEffect(state.playback, customSubtitle) {
        val playback = state.playback
        if (playback == null) {
            exoPlayer.pause()
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            return@LaunchedEffect
        }
        AppLogger.d(
            PLAYBACK_LOG_TAG,
            buildString {
                append("[player.prepare] sourceId=")
                append(state.currentSourceId)
                append(" episodeId=")
                append(state.currentEpisodeId)
                append(" type=")
                append(playback.streamType)
                append(" streamHost=")
                append(playback.streamUrl.safeHost())
                append(" headerNames=")
                append(playback.headers.safeHeaderNames())
                append(" audioHost=")
                append(playback.audioStreamUrl.safeHost())
            },
        )
        keepControlsVisible()
        val playbackKey = "${state.currentSourceId}|${state.currentEpisodeId}|${playback.streamUrl}"
        val resumePositionMs = exoPlayer.currentPosition.takeIf {
            previousPlaybackKey == playbackKey && it > 0L
        } ?: 0L
        val resumeWhenReady = exoPlayer.playWhenReady
        // Extensions report a track's URL but not its format, and ASS, SRT and WebVTT need different parsers.
        val subtitleMimeTypes = if (state.isPlayingOffline) {
            emptyMap()
        } else {
            val sniffTargets = playback.subtitles.filter {
                subtitleMimeTypeFromUrl(it.url) == null && sniffedSubtitleMimeTypes.value[it.url] == null
            }
            if (sniffTargets.isNotEmpty()) {
                sniffedSubtitleMimeTypes.value += sniffSubtitleMimeTypes(sniffTargets, playback.headers)
            }
            playback.subtitles.mapNotNull { subtitle ->
                sniffedSubtitleMimeTypes.value[subtitle.url]?.let { subtitle.url to it }
            }.toMap()
        }
        AppLogger.d(PLAYBACK_LOG_TAG, "[player.subtitles.formats] tracks=${playback.subtitles.size} sniffed=${subtitleMimeTypes.values.groupingBy { it }.eachCount()}")
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        // The saved position is where loading starts, not a jump made once playback is running: a jump into the
        // middle of some streams (HLS through an extension's local proxy) never resumes.
        val savedStartMs = state.pendingSeekMs.coerceAtLeast(0L)
        val startPositionMs = if (resumePositionMs > 0L) resumePositionMs else savedStartMs
        exoPlayer.setMediaSource(
            playback.toMediaSource(
                context = context,
                offline = state.isPlayingOffline,
                selectedSubtitleUrl = selectedSubtitleUrl,
                customSubtitle = customSubtitle,
                subtitleMimeTypes = subtitleMimeTypes,
            ),
            if (startPositionMs > 0L) startPositionMs else C.TIME_UNSET,
        )
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, selectedSubtitleUrl == null)
            .build()
        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
        onPrepare()
        exoPlayer.prepare()
        if (resumePositionMs == 0L && savedStartMs > 0L) onStartPositionApplied()
        exoPlayer.playWhenReady = resumeWhenReady || resumePositionMs == 0L
        onPlaybackPrepared(playbackKey)
    }
}

/** Media3 1.8 keeps WebVTT decoding behind a compatibility switch. */
private class LegacyTextRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        out += TextRenderer(output, outputLooper).apply {
            experimentalSetLegacyDecodingEnabled(true)
        }
    }
}

/**
 * Data source for one playback source. A downloaded episode is read from the download cache only;
 * streamed playback keeps the streaming-cache-then-download-cache-then-network chain.
 */
private fun playbackDataSourceFactory(
    context: Context,
    offline: Boolean,
    headers: Map<String, String>,
    resourceHeadersByUrl: Map<String, Map<String, String>> = emptyMap(),
): DataSource.Factory {
    val factory = if (offline) {
        OfflineMediaCache.buildDownloadedPlaybackDataSourceFactory(context)
    } else {
        OfflineMediaCache.buildPlaybackDataSourceFactory(context, headers, resourceHeadersByUrl)
    }
    // Segments with a damaged PAT are repaired on the way in (see TsPatRepairDataSource).
    return TsPatRepairDataSourceFactory(factory)
}

private fun PlaybackStream.toMediaSource(
    context: Context,
    offline: Boolean = false,
    selectedSubtitleUrl: String? = null,
    customSubtitle: LocalSubtitle? = null,
    subtitleMimeTypes: Map<String, String> = emptyMap(),
): MediaSource {
    // Download requests contain the primary stream only. Never attach separately resolved audio
    // or remote subtitle URLs while offline: they are not guaranteed to be in the download cache
    // and a missing optional track must not make the saved video unplayable. Subtitles saved to
    // disk at download time (file: URIs) are safe to keep.
    val playback = if (offline) copy(
        audioStreamUrl = null,
        audioHeaders = emptyMap(),
        subtitles = subtitles.filter { it.url.startsWith("file:") },
    ) else {
        this
    }
    val subtitleTracks = playback.subtitles.map { subtitle ->
        MediaSubtitle(
            url = subtitle.url,
            label = subtitle.label,
            language = subtitle.language,
            headers = subtitle.headers,
            mimeType = subtitleMimeTypes[subtitle.url] ?: subtitleMimeTypeFromUrl(subtitle.url) ?: TEXT_VTT,
            isLocal = subtitle.url.startsWith("file:"),
        )
    } + listOfNotNull(customSubtitle?.let { subtitle ->
        MediaSubtitle(
            url = subtitle.uri.toString(),
            label = subtitle.label,
            language = null,
            headers = emptyMap(),
            mimeType = subtitle.mimeType,
            isLocal = true,
        )
    })
    val dataSourceFactory = playbackDataSourceFactory(
        context = context,
        offline = offline,
        headers = playback.headers,
        resourceHeadersByUrl = subtitleTracks.filterNot(MediaSubtitle::isLocal)
            .associate { subtitle -> subtitle.url to subtitle.headers },
    )
    val mediaItem = MediaItem.Builder()
        .setUri(playback.streamUrl.toUri())
        .setMimeType(
            when (playback.streamType) {
                PlaybackStreamType.HLS -> MimeTypes.APPLICATION_M3U8
                PlaybackStreamType.MP4 -> MimeTypes.VIDEO_MP4
                PlaybackStreamType.DASH -> MimeTypes.APPLICATION_MPD
            }
        )
        .build()

    val videoSource = when (playback.streamType) {
        PlaybackStreamType.HLS -> HlsMediaSource.Factory(dataSourceFactory)
            .setAllowChunklessPreparation(true)
            .createMediaSource(mediaItem)
        PlaybackStreamType.DASH -> DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        PlaybackStreamType.MP4 -> ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
    }
    val sources = buildList<MediaSource> {
        add(videoSource)
        playback.audioStreamUrl?.let { audioUrl ->
            add(
                HlsMediaSource.Factory(
                    playbackDataSourceFactory(context, offline, playback.audioHeaders.ifEmpty { playback.headers }),
                ).setAllowChunklessPreparation(true).createMediaSource(
                    MediaItem.Builder().setUri(audioUrl.toUri()).setMimeType(MimeTypes.APPLICATION_M3U8).build(),
                ),
            )
        }
        subtitleTracks.forEach { subtitle ->
            val configuration = MediaItem.SubtitleConfiguration.Builder(subtitle.url.toUri())
                .setMimeType(subtitle.mimeType)
                .setLabel(subtitle.label)
                .setLanguage(subtitle.language)
                .setSelectionFlags(
                    if (subtitle.url == selectedSubtitleUrl) {
                        C.SELECTION_FLAG_DEFAULT
                    } else {
                        0
                    },
                )
                .build()
            add(
                SingleSampleMediaSource.Factory(
                    if (subtitle.isLocal) {
                        DefaultDataSource.Factory(context)
                    } else {
                        playbackDataSourceFactory(context, offline, subtitle.headers.ifEmpty { playback.headers })
                    },
                ).createMediaSource(configuration, C.TIME_UNSET),
            )
        }
    }
    return if (sources.size == 1) videoSource else MergingMediaSource(*sources.toTypedArray())
}

private fun subtitleMimeTypeFromUrl(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".vtt") -> TEXT_VTT
        path.endsWith(".ass") || path.endsWith(".ssa") -> TEXT_SSA
        path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
        else -> null
    }
}

/**
 * Reads the start of each remote track whose URL does not name its format and tells the format
 * from the content. Only *successful* detections are returned: a failed or inconclusive sniff
 * (timeouts happen) must stay uncached and be retried on the next prepare, otherwise one flaky
 * attempt would pin the wrong parser onto that track for the rest of the session. Callers fall
 * back to the extension / VTT default for whatever is missing.
 */
private suspend fun sniffSubtitleMimeTypes(
    subtitles: List<PlaybackSubtitle>,
    streamHeaders: Map<String, String>,
): Map<String, String> =
    coroutineScope {
        subtitles
            .filter { subtitleMimeTypeFromUrl(it.url) == null }
            .map { subtitle ->
                async(Dispatchers.IO) {
                    sniffSubtitleMimeType(subtitle.url, subtitle.headers.ifEmpty { streamHeaders })
                        ?.let { subtitle.url to it }
                }
            }
            .awaitAll()
            .filterNotNull()
            .toMap()
    }

private val SRT_START = Regex("""^\d+\s*\r?\n\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}""")

private fun sniffSubtitleMimeType(url: String, headers: Map<String, String>): String? = runCatching {
    val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 4_000
        readTimeout = 4_000
        headers.forEach { (name, value) -> setRequestProperty(name, value) }
        setRequestProperty("Range", "bytes=0-1023")
    }
    try {
        val status = connection.responseCode
        val head = connection.inputStream.use { input ->
            val buffer = ByteArray(1024)
            val read = input.read(buffer)
            if (read > 0) String(buffer, 0, read, Charsets.UTF_8) else ""
        }.trimStart { it.isWhitespace() || it == '﻿' }
        AppLogger.d(
            PLAYBACK_LOG_TAG,
            "[player.subtitles.sniff] host=${url.safeHost()} status=$status bytes=${head.length} " +
                "start=" + head.take(24).map { if (it.isWhitespace()) ' ' else it }.joinToString(""),
        )
        when {
            head.startsWith("WEBVTT") -> TEXT_VTT
            head.contains("[Script Info]", ignoreCase = true) || head.contains("[V4+ Styles]", ignoreCase = true) -> TEXT_SSA
            SRT_START.containsMatchIn(head) -> MimeTypes.APPLICATION_SUBRIP
            else -> null
        }
    } finally {
        connection.disconnect()
    }
}.onFailure { AppLogger.d(PLAYBACK_LOG_TAG, "[player.subtitles.sniff] host=${url.safeHost()} failed=${it.javaClass.simpleName}: ${it.message}") }
    .getOrNull()

private data class LocalSubtitle(
    val uri: Uri,
    val label: String,
    val mimeType: String,
)

private data class MediaSubtitle(
    val url: String,
    val label: String?,
    val language: String?,
    val headers: Map<String, String>,
    val mimeType: String,
    val isLocal: Boolean,
)

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

private fun subtitleMimeType(context: Context, uri: Uri): String {
    val name = context.subtitleDisplayName(uri).lowercase()
    return when {
        name.endsWith(".ass") || name.endsWith(".ssa") -> TEXT_SSA
        name.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
        else -> TEXT_VTT
    }
}

@Composable
private fun PlayerSubtitleSelectionEffect(
    exoPlayer: ExoPlayer,
    playback: PlaybackStream?,
    selectedSubtitleUrl: String?,
    customSubtitle: LocalSubtitle?,
) {
    LaunchedEffect(exoPlayer, playback?.streamUrl, selectedSubtitleUrl, customSubtitle) {
        val selectedLabel = when (selectedSubtitleUrl) {
            customSubtitle?.uri?.toString() -> customSubtitle?.label
            else -> playback?.subtitles
                ?.firstOrNull { it.url == selectedSubtitleUrl }
                ?.label
        }
        val parameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, selectedSubtitleUrl == null)
        if (selectedSubtitleUrl != null) {
            exoPlayer.currentTracks.groups
                .firstNotNullOfOrNull { group ->
                    if (group.type != C.TRACK_TYPE_TEXT) return@firstNotNullOfOrNull null
                    (0 until group.length).firstOrNull { index ->
                        group.getTrackFormat(index).label == selectedLabel
                    }?.let { index -> TrackSelectionOverride(group.mediaTrackGroup, listOf(index)) }
                }
                ?.let(parameters::setOverrideForType)
        }
        exoPlayer.trackSelectionParameters = parameters.build()
    }
}

private const val SUBTITLES_OFF_ID = "subtitles-off"
// DocumentsUI commonly classifies .ass files as application/octet-stream, so a MIME-only filter
// makes valid subtitle files unselectable. The extension is resolved in [subtitleMimeType] after
// selection instead.
private val SUBTITLE_MIME_TYPES = arrayOf("*/*")

private fun String?.safeHost(): String {
    if (this.isNullOrBlank()) return "unknown"
    return runCatching { URI(this).host }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: "unknown"
}

private fun Map<String, String>.safeHeaderNames(): String {
    if (isEmpty()) return "[]"
    return keys
        .filter(String::isNotBlank)
        .sorted()
        .joinToString(prefix = "[", postfix = "]")
}


@Composable
private fun PlayerPlaybackListenerEffect(
    exoPlayer: ExoPlayer,
    mediaSession: MediaSession,
    viewModel: PlayerViewModel,
    state: () -> PlayerUiState,
    autoPlayNextEpisode: Boolean,
    videoAspectRatio: MutableFloatState,
    isPlaying: MutableState<Boolean>,
    isBuffering: MutableState<Boolean>,
    durationMs: MutableLongState,
    pendingSeekMs: MutableLongState,
    positionMs: MutableLongState,
    sliderPositionMs: MutableLongState,
    handledEndedEpisodeId: MutableState<String?>,
    playerPrepareStartedAt: MutableLongState,
    watchedSecondsSnapshot: () -> List<Long>,
    subtitleLines: MutableState<List<String>>,
    subtitleCueEvents: MutableState<List<Pair<Long, List<String>>>>,
    onDisposed: () -> Unit,
) {
    DisposableEffect(exoPlayer) {
        var loggedFirstCue = false
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                val preparedAt = playerPrepareStartedAt.longValue
                if (preparedAt <= 0L) return
                AppLogger.d(
                    PLAYBACK_LOG_TAG,
                    "[player.first_frame] sourceId=${state().currentSourceId}, elapsedMs=${SystemClock.elapsedRealtime() - preparedAt}",
                )
                // A new prepare resets this value; suppress duplicate renderer callbacks for the
                // same media item so the log remains one timing point per playback start.
                playerPrepareStartedAt.longValue = 0L
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio.floatValue =
                        videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying.value = playing
                if (playing) {
                    viewModel.autoSearchPersianSubtitle()
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                AppLogger.d(
                    PLAYBACK_LOG_TAG,
                    "[player.subtitles.tracks] count=${text.size} " + text.joinToString(prefix = "[", postfix = "]") { group ->
                        val format = group.getTrackFormat(0)
                        "${format.sampleMimeType}/${format.label}/supported=${group.isSupported}/selected=${group.isSelected}"
                    },
                )
            }

            override fun onCues(cueGroup: CueGroup) {
                if (cueGroup.cues.isNotEmpty() && !loggedFirstCue) {
                    loggedFirstCue = true
                    AppLogger.d(PLAYBACK_LOG_TAG, "[player.subtitles.first_cue] cues=${cueGroup.cues.size}")
                }
                val visibleLines = cueGroup.cues.mapNotNull { cue ->
                    cue.text?.toString()?.trim()?.takeIf(String::isNotBlank)
                }.distinct()
                subtitleLines.value = visibleLines
                // Capture the moment these cues became active so the offset slider can shift
                // them earlier/later. (CueGroup's own timestamp field is not reliably readable
                // across media3 versions, so we use the player position the ticker maintains.)
                subtitleCueEvents.value =
                    (subtitleCueEvents.value + (positionMs.longValue to visibleLines)).takeLast(128)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering.value = playbackState == Player.STATE_BUFFERING
                durationMs.longValue = exoPlayer.duration.takeIf { it > 0 } ?: 0L
                if (playbackState == Player.STATE_READY && pendingSeekMs.longValue > 0L) {
                    exoPlayer.seekTo(pendingSeekMs.longValue)
                    positionMs.longValue = pendingSeekMs.longValue
                    sliderPositionMs.longValue = pendingSeekMs.longValue
                    pendingSeekMs.longValue = 0L
                    viewModel.consumePendingSeek()
                }
                // Playing again after the jump (the READY that follows it) means it worked.
                if (playbackState == Player.STATE_ENDED && autoPlayNextEpisode) {
                    val currentState = state()
                    val currentEpisodeId = currentState.currentEpisodeId
                    val currentIndex = currentState.episodes.indexOfFirst { it.id == currentEpisodeId }
                    val hasNextEpisode = currentIndex != -1 && currentIndex < currentState.episodes.lastIndex
                    if (hasNextEpisode && handledEndedEpisodeId.value != currentEpisodeId) {
                        handledEndedEpisodeId.value = currentEpisodeId
                        viewModel.savePlaybackProgress(
                            positionMs = exoPlayer.duration.takeIf { it > 0 } ?: exoPlayer.currentPosition.coerceAtLeast(0L),
                            durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L,
                            watchedSeconds = watchedSecondsSnapshot(),
                        )
                        viewModel.playNextEpisode()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val currentState = state()
                AppLogger.e(
                    PLAYBACK_LOG_TAG,
                    buildString {
                        append("[player.error] sourceId=")
                        append(currentState.currentSourceId)
                        append(" episodeId=")
                        append(currentState.currentEpisodeId)
                        append(" type=")
                        append(currentState.playback?.streamType)
                        append(" stream=")
                        append(currentState.playback?.streamUrl.shortUrl())
                        append(" code=")
                        append(error.errorCodeName)
                        append(" message=")
                        append(error.message)
                    },
                    error
                )
                // ExoPlayer's own message is just "Source error", and the fields above can be lost
                // to log redaction (the message embeds a source id containing '|', and a redacted
                // stream URL swallows the rest of the line). The underlying cause is therefore also
                // written as its own short line, so a downloaded file that will not open can be
                // told apart from a genuinely unreachable network.
                AppLogger.e(
                    PLAYBACK_LOG_TAG,
                    "[player.error.cause] code=" + error.errorCodeName + " chain=" +
                        generateSequence(error.cause) { cause -> cause.cause }
                            .take(5)
                            .joinToString(" <- ") { cause ->
                                cause.javaClass.simpleName + ":" + cause.message
                            },
                    error,
                )
                viewModel.recoverFromPlaybackError(currentState.playback?.streamUrl)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            onDisposed()
            viewModel.savePlaybackProgress(
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L,
                watchedSeconds = watchedSecondsSnapshot(),
            )
            exoPlayer.removeListener(listener)
            subtitleLines.value = emptyList()
            mediaSession.release()
            exoPlayer.release()
        }
    }
}

private fun String?.shortUrl(): String {
    if (this.isNullOrBlank()) return "null"
    return substringBefore('?').substringAfterLast('/')
}

@Composable
internal fun PlayerPlaybackPositionPollingEffect(
    exoPlayer: ExoPlayer,
    isSeeking: Boolean,
    durationMs: MutableLongState,
    bufferedPositionMs: MutableLongState,
    positionMs: MutableLongState,
    sliderPositionMs: MutableLongState,
) {
    LaunchedEffect(exoPlayer, isSeeking) {
        while (true) {
            durationMs.longValue = exoPlayer.duration.takeIf { it > 0 } ?: 0L
            bufferedPositionMs.longValue = exoPlayer.bufferedPosition.takeIf { it > 0 } ?: 0L
            if (!isSeeking) {
                positionMs.longValue = exoPlayer.currentPosition.coerceAtLeast(0L)
                sliderPositionMs.longValue = positionMs.longValue
            }
            delay(250)
        }
    }
}

@Composable
private fun PlayerWatchedSecondsTrackingEffect(
    exoPlayer: ExoPlayer,
    currentSourceId: String,
    currentEpisodeId: String,
    isSeeking: Boolean,
    lastTrackedPlaybackPositionMs: MutableLongState,
    watchedSeconds: MutableSet<Long>,
) {
    LaunchedEffect(exoPlayer, currentSourceId, currentEpisodeId) {
        lastTrackedPlaybackPositionMs.longValue = -1L
        while (true) {
            val currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            val currentDurationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L
            val trackingAllowed = exoPlayer.isPlaying &&
                currentDurationMs > 0L &&
                !isSeeking

            if (trackingAllowed) {
                val previousPositionMs = lastTrackedPlaybackPositionMs.longValue
                if (previousPositionMs >= 0L) {
                    val deltaMs = currentPositionMs - previousPositionMs
                    if (deltaMs in 1L..WATCHED_SECONDS_TRACKING_MAX_DELTA_MS) {
                        val startSecond = previousPositionMs / 1_000L
                        val endSecond = currentPositionMs / 1_000L
                        for (second in startSecond..endSecond) {
                            if (second * 1_000L < currentDurationMs) {
                                watchedSeconds += second
                            }
                        }
                    }
                }
                lastTrackedPlaybackPositionMs.longValue = currentPositionMs
            } else {
                lastTrackedPlaybackPositionMs.longValue = -1L
            }
            delay(1_000L)
        }
    }
}

@Composable
private fun PlayerProgressPersistenceEffect(
    exoPlayer: ExoPlayer,
    currentSourceId: String,
    currentEpisodeId: String,
    onSaveProgress: () -> Unit,
) {
    LaunchedEffect(exoPlayer, currentSourceId, currentEpisodeId) {
        while (true) {
            delay(PLAYBACK_PROGRESS_SAVE_INTERVAL_MS)
            if (exoPlayer.isPlaying) {
                onSaveProgress()
            }
        }
    }
}

