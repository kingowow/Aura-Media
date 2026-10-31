package org.akkirrai.hibiki.feature.player

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.akkirrai.hibiki.app.di.hibikiDependencies
import org.akkirrai.hibiki.R
import org.akkirrai.hibiki.app.settings.AppPreferences
import org.akkirrai.hibiki.core.model.PlaybackStream
import org.akkirrai.hibiki.core.model.PlaybackSettingsOptions
import org.akkirrai.hibiki.core.model.WatchEpisode
import org.akkirrai.hibiki.core.model.WatchSource
import org.akkirrai.hibiki.core.download.OfflineDownloadRepository
import org.akkirrai.hibiki.core.download.OfflineEpisodeDownloadState
import org.akkirrai.hibiki.core.log.AppLogger
import org.akkirrai.hibiki.core.network.NoInternetConnectionException
import org.akkirrai.hibiki.core.source.AnimeWatchRepository
import org.akkirrai.hibiki.core.source.OfflineTitleMetadataRepository
import org.akkirrai.hibiki.core.source.WatchStateRepository
import org.akkirrai.hibiki.core.source.watchTitleIdFromSourceId
import org.akkirrai.hibiki.core.subtitle.PersianSubtitleSearcher
import org.akkirrai.hibiki.core.subtitle.SubtitleArchiveListing
import org.akkirrai.hibiki.core.subtitle.SubtitleFormat
import org.akkirrai.hibiki.core.subtitle.SubtitleQuery
import org.akkirrai.hibiki.core.subtitle.SubtitleSearchOutcome
import org.akkirrai.hibiki.core.subtitle.SubtitleSearchResult
import org.akkirrai.hibiki.core.subtitle.SubtitleTranslator
import java.io.File

/** Floating-message states for the automatic Persian subtitle search in the player. */
sealed interface PersianSubtitleState {
    data object Idle : PersianSubtitleState

    /**
     * The banner asks the viewer what to do about the Persian subtitle that is not applied yet.
     * It is shown as soon as the episode starts playing - never as a "searching…" spinner.
     */
    data object NotApplied : PersianSubtitleState

    /**
     * Translation in flight. [label] is the target-language name, needed to build the track
     * label of the finished subtitle.
     */
    data class Translating(
        val progressPercent: Int,
        val label: String = "",
    ) : PersianSubtitleState

    /**
     * [isTranslation] marks the entry this screen created itself (as opposed to a subtitle file
     * it merely found): only that one gets the "translated" suffix and the delete action. The
     * suffix lives in resources, so the ViewModel passes the bare target-language label and the
     * player decorates it.
     */
    data class Applied(
        val filePath: String,
        val label: String,
        val isTranslation: Boolean = false,
    ) : PersianSubtitleState

    /**
     * The translation source could not be found: neither the video's own tracks nor the
     * subtitle sites offered a file that could be translated. The banner offers the viewer the
     * subtitle search instead.
     */
    data object TranslateSourceNotFound : PersianSubtitleState

    data class Failed(val message: String?) : PersianSubtitleState
}

/** State of the subtitle-search drawer: a search bar plus the releases it found. */
sealed interface SubtitleSearchState {
    data object Idle : SubtitleSearchState
    data class Searching(val query: String) : SubtitleSearchState
    data class Results(
        val query: String,
        val results: List<SubtitleSearchResult>,
    ) : SubtitleSearchState
    data class LoadingArchive(val result: SubtitleSearchResult) : SubtitleSearchState
    data class Archive(
        val listing: SubtitleArchiveListing,
    ) : SubtitleSearchState
    data class Applying(val result: SubtitleSearchResult) : SubtitleSearchState
    data class Failed(val query: String, val message: String?) : SubtitleSearchState
}

/**
 * What the drawer is looking for. The "for translation" mode is the one the banner's
 * "search subtitle for translation" button opens: it looks for any subtitle (English included)
 * that can be translated into Persian.
 */
enum class SubtitleSearchPurpose {
    /** A Persian subtitle that is applied as it is. */
    Persian,

    /** Any subtitle that will be translated into Persian afterwards. */
    ForTranslation,
}

class PlayerViewModel(
    sourceId: String,
    episodeId: String,
    initialEpisodeNumber: Double?,
    private val repository: AnimeWatchRepository,
    private val watchStateRepository: WatchStateRepository,
    private val offlineDownloadRepository: OfflineDownloadRepository,
    private val offlineTitleMetadataRepository: OfflineTitleMetadataRepository,
    private val persianSubtitleSearcher: PersianSubtitleSearcher,
    private val subtitleTranslator: SubtitleTranslator,
    private val appPreferences: AppPreferences,
    private val appContext: android.content.Context,
) : ViewModel() {
    private val viewModelCreatedAt = SystemClock.elapsedRealtime()
    private val titleId = watchTitleIdFromSourceId(sourceId)
    private var loadJob: Job? = null
    private var settingsLoadJob: Job? = null
    @Volatile
    private var settingsLoadingKey: String? = null
    private val savedSelection = watchStateRepository.getSelectedSource(titleId)
        .takeIf { it.sourceId == sourceId }
    private val _uiState = MutableStateFlow(
        PlayerUiState(
            currentSourceId = sourceId,
            currentEpisodeId = episodeId,
            currentEpisodeNumber = initialEpisodeNumber,
            selectedPlayerName = watchStateRepository.getSelectedPlayer(titleId, sourceId),
            selectedQualityLabel = savedSelection?.quality,
        )
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _persianSubtitleState = MutableStateFlow<PersianSubtitleState>(PersianSubtitleState.Idle)
    val persianSubtitleState: StateFlow<PersianSubtitleState> = _persianSubtitleState.asStateFlow()
    private val _subtitleSearchState = MutableStateFlow<SubtitleSearchState>(SubtitleSearchState.Idle)
    val subtitleSearchState: StateFlow<SubtitleSearchState> = _subtitleSearchState.asStateFlow()
    /** Whether the drawer looks for a Persian subtitle or for one that will be translated. */
    private var subtitleSearchPurpose: SubtitleSearchPurpose = SubtitleSearchPurpose.Persian
    private var subtitleJob: Job? = null
    private var subtitleSearchJob: Job? = null
    private var subtitleEpisodeKey: String? = null
    private var subtitleAutoStartedEpisodeId: String? = null
    /**
     * What to redo when the viewer presses "retry" in the banner. A failure can come from the
     * translation or from applying a downloaded file, and the two are retried differently.
     * The action is a suspend function: retrying a translation means running it again.
     */
    private var subtitleRetryAction: (suspend () -> Unit)? = null

    /**
     * Runs once per episode, when playback starts: the banner appears and asks whether the
     * Persian subtitle should be translated, searched for, or left alone. The search itself is
     * never started behind the viewer's back any more - the banner is the offer.
     */
    fun autoSearchPersianSubtitle() {
        val episodeId = _uiState.value.currentEpisodeId
        if (episodeId.isEmpty() || subtitleAutoStartedEpisodeId == episodeId) return
        subtitleAutoStartedEpisodeId = episodeId
        if (_persianSubtitleState.value !is PersianSubtitleState.Applied) {
            _persianSubtitleState.value = PersianSubtitleState.NotApplied
        }
    }

    /** Re-opens the banner after the viewer closed it (the Subtitles panel's search button). */
    fun showPersianSubtitleBanner() {
        if (_persianSubtitleState.value is PersianSubtitleState.Translating) return
        _persianSubtitleState.value = PersianSubtitleState.NotApplied
    }

    /**
     * Redoes whatever failed: a failed translation is retried, a failed download opens the
     * subtitle search again (there is nothing to retry there - the file never arrived).
     */
    fun retryPersianSubtitle() {
        val retry = subtitleRetryAction
        if (retry != null) {
            subtitleRetryAction = null
            // The action is a suspend function (it translates a file), so it needs a coroutine.
            viewModelScope.launch(Dispatchers.IO) { retry() }
        } else {
            showPersianSubtitleBanner()
        }
    }

    /**
     * Hides the floating message (auto-dismiss of the "applied" confirmation, or the banner's
     * close button). Cancelling the in-flight job matters: without it a translation would keep
     * running and re-show the banner after the user dismissed it.
     */
    fun dismissPersianSubtitleMessage() {
        when (_persianSubtitleState.value) {
            is PersianSubtitleState.Applied,
            is PersianSubtitleState.NotApplied,
            is PersianSubtitleState.TranslateSourceNotFound,
            is PersianSubtitleState.Failed,
            -> _persianSubtitleState.value = PersianSubtitleState.Idle
            is PersianSubtitleState.Translating,
            -> {
                subtitleJob?.cancel()
                subtitleJob = null
                _persianSubtitleState.value = PersianSubtitleState.Idle
            }
            PersianSubtitleState.Idle -> Unit
        }
    }

    /**
     * Deletes the translated-subtitle file this screen created (the "فارسی (ترجمه)" entry in the
     * subtitle list) and forgets it, so the player returns to the video's own tracks.
     */
    fun deleteTranslatedSubtitle(path: String) {
        val file = File(path)
        if (!file.exists()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { file.delete() }
                .onFailure { AppLogger.w("PlayerViewModel", "[subtitle.delete_failed] ${it.message}") }
        }
        when (_persianSubtitleState.value) {
            is PersianSubtitleState.Translating -> {
                subtitleJob?.cancel()
                subtitleJob = null
                _persianSubtitleState.value = PersianSubtitleState.Idle
            }
            is PersianSubtitleState.Applied ->
                _persianSubtitleState.value = PersianSubtitleState.Idle
            else -> Unit
        }
    }

    /** Builds the search query for the current playback, or null when there is no playback yet. */
    private fun subtitleQueryFor(state: PlayerUiState): SubtitleQuery? {
        val playback = state.playback ?: return null
        val episodeNumber = state.currentEpisodeNumber?.takeIf { it > 0 }?.toInt()
        return SubtitleQuery(
            title = state.animeTitle.ifBlank { playback.animeTitle },
            episodeNumber = episodeNumber,
            isMovie = episodeNumber == null,
        )
    }

    /**
     * The banner's translate button: turns a subtitle into Persian without an API key.
     * It first tries the video's own tracks (English preferred, but any convertible one works);
     * when the video carries no usable track it falls back to searching the subtitle sources for a
     * foreign-language file and translating that instead. When nothing translatable exists, the
     * banner says so and offers the subtitle search instead.
     */
    fun translateFallbackSubtitle() {
        val inFlight = _persianSubtitleState.value
        // Reachable from the banner's buttons and from the player's subtitle menu - anything
        // currently translating is already busy.
        if (inFlight is PersianSubtitleState.Translating) return
        subtitleJob?.cancel()
        val episodeKey = _uiState.value.currentEpisodeId
        subtitleJob = viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val query = subtitleQueryFor(state)

            // 1) Prefer one of the video's own subtitle tracks that converts to SRT.
            var srt: String? = null
            var saveKey: String? = null
            val playback = state.playback
            val streamHeaders = playback?.headers.orEmpty()
            val tracks = playback?.subtitles.orEmpty()
            val ordered = tracks.sortedBy { track ->
                val hint = "${track.label.orEmpty()} ${track.language.orEmpty()}".lowercase()
                if (hint.contains("en") || hint.contains("eng")) 0 else 1
            }
            for (track in ordered) {
                // An empty track header map means "inherit the stream's headers" (the contract in
                // beakokit's SubtitleTrack). Aniyomi sources rely on this: their subtitle URLs are
                // served by the same host as the video and reject requests without the stream's
                // Referer/Origin, which previously made us fall through to an external search that
                // could grab another season's episode.
                val bytes = persianSubtitleSearcher.downloadDirect(track.url, track.headers.ifEmpty { streamHeaders })
                val converted = bytes?.let { SubtitleFormat.toSrt(it, track.url.substringAfterLast('/'), query?.episodeNumber) }
                if (!converted.isNullOrBlank() && SubtitleFormat.hasCues(converted)) {
                    srt = converted
                    saveKey = track.url
                    break
                }
            }

            // 2) The video has subtitle tracks but none could be fetched/converted: translating an
            //    external file could belong to another season or episode, so fail honestly instead
            //    of guessing. Only when the video carries no tracks at all do we search the sources.
            if (srt == null && tracks.isEmpty() && query != null) {
                val outcome = persianSubtitleSearcher.searchAndResolve(query, persianOnly = false)
                if (outcome is SubtitleSearchOutcome.Found) {
                    val text = runCatching { outcome.file.readText() }.getOrNull()
                    if (!text.isNullOrBlank()) {
                        srt = text
                        saveKey = outcome.file.absolutePath
                    }
                }
            }

            val source = srt
            if (source == null || saveKey == null) {
                if (_uiState.value.currentEpisodeId == episodeKey) {
                    _persianSubtitleState.value = PersianSubtitleState.TranslateSourceNotFound
                }
                return@launch
            }
            // A failed translation is worth retrying: the same source is translated again.
            subtitleRetryAction = { translateFallbackSubtitle() }
            _persianSubtitleState.value = PersianSubtitleState.Translating(0)
            // Snapshot the preference outside the loop: the user can open Settings while a
            // translation runs, and the batch must not change language halfway through.
            val target = appPreferences.state.value.subtitleTranslationTarget
            try {
                val engine = appPreferences.state.value.subtitleTranslationEngine
                val apiKey = appPreferences.state.value.subtitleTranslationApiKey
                val apiUrl = appPreferences.state.value.subtitleTranslationApiUrl
                val apiRegion = appPreferences.state.value.subtitleTranslationApiRegion
                val apiModel = appPreferences.state.value.subtitleTranslationApiModel
                val translated = subtitleTranslator.translate(
                    srt = source,
                    target = target,
                    engine = engine,
                    apiKey = apiKey,
                    apiUrl = apiUrl,
                    apiRegion = apiRegion,
                    apiModel = apiModel,
                    onProgress = { percent ->
                        if (_uiState.value.currentEpisodeId == episodeKey) {
                            _persianSubtitleState.value = PersianSubtitleState.Translating(
                                progressPercent = percent,
                                label = target.label,
                            )
                        }
                    },
                )
                val file = persianSubtitleSearcher.saveText(saveKey, translated)
                // Persist the per-line cache so the next play of this episode is instant.
                subtitleTranslator.cache?.flush()
                if (_uiState.value.currentEpisodeId != episodeKey) return@launch
                if (file == null) {
                    _persianSubtitleState.value = PersianSubtitleState.Failed(null)
                } else {
                    _persianSubtitleState.value = PersianSubtitleState.Applied(
                        filePath = file.absolutePath,
                        label = target.label,
                        isTranslation = true,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("PlayerViewModel", "[subtitle.translate.failed] ${e.javaClass.simpleName}: ${e.message}")
                if (_uiState.value.currentEpisodeId == episodeKey) {
                    _persianSubtitleState.value = PersianSubtitleState.Failed(e.message)
                }
            }
        }
    }

    // ---------------------------------------------------------------- subtitle search drawer

    /** The title the drawer's search bar is pre-filled with. */
    fun currentSubtitleSearchHint(): String {
        val state = _uiState.value
        val playback = state.playback
        return state.animeTitle.ifBlank { playback?.animeTitle.orEmpty() }
    }

    /**
     * Searches the subtitle sites for [text] and fills the drawer with the releases it found.
     * The episode number is appended to the query so the sources return the right episode.
     */
    fun searchSubtitles(text: String, purpose: SubtitleSearchPurpose = subtitleSearchPurpose, languageFilter: String = "fa") {
        val query = text.trim()
        if (query.isEmpty()) {
            _subtitleSearchState.value = SubtitleSearchState.Idle
            return
        }
        subtitleSearchPurpose = purpose
        val state = _uiState.value
        val episodeNumber = state.currentEpisodeNumber?.takeIf { it > 0 }?.toInt()
        val isMovie = episodeNumber == null
        subtitleSearchJob?.cancel()
        subtitleSearchJob = viewModelScope.launch(Dispatchers.IO) {
            _subtitleSearchState.value = SubtitleSearchState.Searching(query)
            val results = runCatching {
                persianSubtitleSearcher.searchReleases(
                    text = query,
                    episodeNumber = episodeNumber,
                    isMovie = isMovie,
                    forTranslation = purpose == SubtitleSearchPurpose.ForTranslation,
                    languageFilter = languageFilter,
                ) { partial ->
                    // Stream findings as each source lands, so the viewer can start tapping
                    // results while the slower sources are still working.
                    _subtitleSearchState.value = SubtitleSearchState.Results(query, partial)
                }
            }.getOrElse { error ->
                AppLogger.w("PlayerViewModel", "[subtitle.search.failed] ${error.message}")
                emptyList()
            }
            _subtitleSearchState.value = SubtitleSearchState.Results(query, results)
        }
    }

    /**
     * Downloads a release. When it turns out to be an archive, the drawer switches to the list of
     * files inside it so the viewer can pick the exact track; a single-file release is applied
     * right away. A 3fa gate release is listed with [SubtitleSearchResult.openInBrowser]; the
     * sheet hands it to the Composable layer, which opens the release page in a download-reporting
     * web view.
     */
    fun selectSubtitleResult(result: SubtitleSearchResult) {
        val episodeNumber = _uiState.value.currentEpisodeNumber?.takeIf { it > 0 }?.toInt()
        subtitleSearchJob?.cancel()
        subtitleSearchJob = viewModelScope.launch(Dispatchers.IO) {
            _subtitleSearchState.value = SubtitleSearchState.LoadingArchive(result)
            if (result.openInBrowser) {
                // 3fa: the file is behind an adcd gate that only renders in a real web view.
                // The Composable layer opens the page (WebViewActivity reporting downloads);
                // when the viewer taps the download link, applySubtitleFromDownload is called
                // with that URL and the app fetches + applies the subtitle itself.
                _subtitleSearchState.value = SubtitleSearchState.Idle
                return@launch
            }
            val listing = runCatching {
                persianSubtitleSearcher.inspectRelease(result, episodeNumber)
            }.getOrNull()
            if (listing == null) {
                _subtitleSearchState.value = SubtitleSearchState.Failed(result.title, null)
                return@launch
            }
            if (listing.entries.isEmpty()) {
                // A plain subtitle file: apply it right away. A 3fa gate listing is also empty -
                // it is already handled above, so an empty listing here is a real subtitle.
                val outcome = persianSubtitleSearcher.applyRelease(listing, null, episodeNumber)
                _subtitleSearchState.value = SubtitleSearchState.Idle
                applyOutcome(outcome, translate = subtitleSearchPurpose == SubtitleSearchPurpose.ForTranslation)
            } else {
                _subtitleSearchState.value = SubtitleSearchState.Archive(listing)
            }
        }
    }

    /** Applies the file the viewer picked inside an archive. */
    fun selectSubtitleArchiveEntry(listing: SubtitleArchiveListing, entryName: String) {
        val episodeNumber = _uiState.value.currentEpisodeNumber?.takeIf { it > 0 }?.toInt()
        subtitleSearchJob?.cancel()
        subtitleSearchJob = viewModelScope.launch(Dispatchers.IO) {
            // Direct-file listing (subkade with several files): download the chosen file
            // first and, if it turns out to be an archive, hand the inner files back to
            // the viewer so they pick the exact track instead of auto-applying one.
            if (listing.directDownloadUrls.isNotEmpty()) {
                val url = listing.directDownloadUrls.firstOrNull { it.first == entryName }?.second
                    ?: listing.directDownloadUrls.firstOrNull()?.second
                if (url != null) {
                    _subtitleSearchState.value = SubtitleSearchState.LoadingArchive(listing.result)
                    val downloaded = runCatching {
                        persianSubtitleSearcher.inspectUrl(listing.result, url, listing.downloadReferer)
                    }.getOrNull()
                    if (downloaded == null) {
                        _subtitleSearchState.value = SubtitleSearchState.Failed(listing.result.title, null)
                        _persianSubtitleState.value = PersianSubtitleState.Failed(null)
                        return@launch
                    }
                    if (downloaded.entries.isEmpty()) {
                        val outcome = persianSubtitleSearcher.applyRelease(downloaded, null, episodeNumber)
                        _subtitleSearchState.value = SubtitleSearchState.Idle
                        applyOutcome(outcome, translate = subtitleSearchPurpose == SubtitleSearchPurpose.ForTranslation)
                    } else {
                        _subtitleSearchState.value = SubtitleSearchState.Archive(downloaded)
                    }
                    return@launch
                }
            }
            _subtitleSearchState.value = SubtitleSearchState.Applying(listing.result)
            val outcome = persianSubtitleSearcher.applyRelease(listing, entryName, episodeNumber)
            _subtitleSearchState.value = SubtitleSearchState.Idle
            applyOutcome(outcome, translate = subtitleSearchPurpose == SubtitleSearchPurpose.ForTranslation)
        }
    }

    /**
     * Downloads the subtitle file the viewer tapped inside a download-reporting web view (3fa's
     * ad-gated link) and applies it. The web view hands back the exact URL; the app's own fetcher
     * (correct UA + Referer, zip/gzip/ASS handling) does the rest.
     */
    fun applySubtitleFromDownload(result: SubtitleSearchResult, url: String, referer: String?) {
        val episodeNumber = _uiState.value.currentEpisodeNumber?.takeIf { it > 0 }?.toInt()
        subtitleSearchJob?.cancel()
        subtitleSearchJob = viewModelScope.launch(Dispatchers.IO) {
            _subtitleSearchState.value = SubtitleSearchState.Applying(result)
            val listing = runCatching {
                persianSubtitleSearcher.inspectUrl(result, url, referer ?: result.pageUrl)
            }.getOrNull()
            if (listing == null) {
                _subtitleSearchState.value = SubtitleSearchState.Failed(result.title, null)
                _persianSubtitleState.value = PersianSubtitleState.Failed(
                    appContext.getString(R.string.subtitle_search_gate_hint),
                )
                return@launch
            }
            _subtitleSearchState.value = if (listing.entries.isEmpty()) SubtitleSearchState.Idle
            else SubtitleSearchState.Archive(listing)
            val outcome = persianSubtitleSearcher.applyRelease(listing, null, episodeNumber)
            if (listing.entries.isEmpty()) applyOutcome(outcome, translate = false)
        }
    }

    /**
     * Puts a downloaded subtitle on the screen. With [translate] the file is run through the
     * translator first (the "search a subtitle for translation" flow); otherwise it is applied as
     * it is.
     */
    private suspend fun applyOutcome(outcome: SubtitleSearchOutcome, translate: Boolean) {
        if (outcome !is SubtitleSearchOutcome.Found) {
            _persianSubtitleState.value = when (outcome) {
                SubtitleSearchOutcome.NotFound -> PersianSubtitleState.Failed(null)
                is SubtitleSearchOutcome.Failed -> PersianSubtitleState.Failed(outcome.message)
                is SubtitleSearchOutcome.Found -> PersianSubtitleState.Failed(null)
            }
            return
        }
        if (!translate) {
            _persianSubtitleState.value = PersianSubtitleState.Applied(
                filePath = outcome.file.absolutePath,
                label = outcome.label,
            )
            return
        }
        // The drawer found a subtitle that must be translated into Persian.
        translateFile(outcome.file.absolutePath)
    }

    /**
     * Translates an already-downloaded subtitle file into the target language and applies the
     * result. The progress shows in the banner (a small ring, no wide bar).
     */
    private suspend fun translateFile(path: String) {
        val source = runCatching { File(path).readText() }.getOrNull()
        if (source.isNullOrBlank()) {
            _persianSubtitleState.value = PersianSubtitleState.TranslateSourceNotFound
            return
        }
        // A failed translation is worth retrying: the same file is translated again.
        subtitleRetryAction = { translateFile(path) }
        _persianSubtitleState.value = PersianSubtitleState.Translating(0)
        val target = appPreferences.state.value.subtitleTranslationTarget
        try {
            val engine = appPreferences.state.value.subtitleTranslationEngine
            val apiKey = appPreferences.state.value.subtitleTranslationApiKey
            val apiUrl = appPreferences.state.value.subtitleTranslationApiUrl
            val apiRegion = appPreferences.state.value.subtitleTranslationApiRegion
            val apiModel = appPreferences.state.value.subtitleTranslationApiModel
            val translated = subtitleTranslator.translate(
                srt = source,
                target = target,
                engine = engine,
                apiKey = apiKey,
                apiUrl = apiUrl,
                apiRegion = apiRegion,
                apiModel = apiModel,
                onProgress = { percent ->
                    _persianSubtitleState.value = PersianSubtitleState.Translating(
                        progressPercent = percent,
                        label = target.label,
                    )
                },
            )
            val file = persianSubtitleSearcher.saveText(path, translated)
            subtitleTranslator.cache?.flush()
            if (file == null) {
                _persianSubtitleState.value = PersianSubtitleState.Failed(null)
            } else {
                subtitleRetryAction = null
                _persianSubtitleState.value = PersianSubtitleState.Applied(
                    filePath = file.absolutePath,
                    label = target.label,
                    isTranslation = true,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("PlayerViewModel", "[subtitle.translate.failed] ${e.javaClass.simpleName}: ${e.message}")
            _persianSubtitleState.value = PersianSubtitleState.Failed(e.message)
        }
    }

    /** Closes the drawer (its state is reset so it opens empty next time). */
    fun dismissSubtitleSearch() {
        subtitleSearchJob?.cancel()
        subtitleSearchJob = null
        _subtitleSearchState.value = SubtitleSearchState.Idle
    }

    init {
        AppLogger.d(
            PLAYBACK_LOG_TAG,
            "[viewmodel.created] sourceId=$sourceId episodeId=$episodeId",
        )
        val seekRestoreStartedAt = SystemClock.elapsedRealtime()
        restoreSavedSeek()
        AppLogger.d(
            PLAYBACK_LOG_TAG,
            "[viewmodel.seek_restore] elapsedMs=${SystemClock.elapsedRealtime() - seekRestoreStartedAt}",
        )
        load()
    }

    fun load(
        forceRefresh: Boolean = false,
        excludedStreamUrls: Set<String> = emptySet(),
    ) {
        loadJob?.cancel()
        val loadStartedAt = SystemClock.elapsedRealtime()
        val state = _uiState.value
        // A new episode starts a fresh subtitle-search story: cancel whatever ran for the old one.
        if (subtitleEpisodeKey != state.currentEpisodeId) {
            subtitleEpisodeKey = state.currentEpisodeId
            subtitleAutoStartedEpisodeId = null
            if (_persianSubtitleState.value !is PersianSubtitleState.Idle) {
                subtitleJob?.cancel()
                _persianSubtitleState.value = PersianSubtitleState.Idle
            }
        }
        AppLogger.d(
            PLAYBACK_LOG_TAG,
            "[viewmodel.load.start] sourceId=${state.currentSourceId} episodeId=${state.currentEpisodeId}, " +
                "forceRefresh=$forceRefresh sinceViewModelMs=${loadStartedAt - viewModelCreatedAt}",
        )
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                failedStreamUrls = it.failedStreamUrls + excludedStreamUrls,
                // A caller passing excludedStreamUrls is an automatic recovery attempt (see
                // recoverFromPlaybackError below) - count it against the per-episode cap so a
                // stream that resolves "successfully" but then reliably fails to actually play
                // (e.g. a CORS-blocked relay CDN) can't retry itself forever. Any other caller
                // (selecting a different episode/player/quality) is a fresh, user-initiated
                // attempt and resets the count.
                autoRecoveryCount = if (excludedStreamUrls.isNotEmpty()) it.autoRecoveryCount + 1 else 0,
            )
        }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val offlineEpisodesStartedAt = SystemClock.elapsedRealtime()
            val offlineEpisodes = offlineDownloadRepository.getOfflineEpisodes(state.currentSourceId)
            val offlineEpisodesElapsedMs = SystemClock.elapsedRealtime() - offlineEpisodesStartedAt
            // A selected KAA source can resolve the requested episode directly. Do not make the
            // initial frame wait for the optional playlist; it is fetched on opening settings.
            val cachedEpisodesStartedAt = SystemClock.elapsedRealtime()
            val episodesResult = runCatching {
                offlineEpisodes.takeIf { it.isNotEmpty() }
                    ?: repository.getCachedEpisodes(state.currentSourceId).orEmpty()
            }.throwIfCancelled()
            val cachedEpisodesElapsedMs = SystemClock.elapsedRealtime() - cachedEpisodesStartedAt
            val currentState = _uiState.value
            if (currentState.currentSourceId != state.currentSourceId || currentState.currentEpisodeId != state.currentEpisodeId) {
                return@launch
            }
            val episodes = episodesResult.getOrDefault(currentState.episodes)
            val effectiveEpisode = resolveCurrentEpisode(
                requestedEpisodeId = state.currentEpisodeId,
                requestedEpisodeNumber = state.currentEpisodeNumber,
                episodes = episodes,
                currentEpisodes = currentState.episodes,
            )
            val effectiveEpisodeId = effectiveEpisode?.id ?: state.currentEpisodeId
            val effectiveEpisodeNumber = effectiveEpisode?.number ?: state.currentEpisodeNumber
            val offlinePlaybackStartedAt = SystemClock.elapsedRealtime()
            val offlinePlayback = offlineDownloadRepository.getOfflinePlayback(
                    sourceId = state.currentSourceId,
                    episodeId = effectiveEpisodeId,
                )
            // A completed Media3 download is a promise to play locally. If its index can no
            // longer be reconstructed, do not resolve the episode again over the network: that
            // silently changes a saved episode into live playback. Surface the same repair path
            // as a cache read failure instead.
            val hasBrokenOfflineDownload = offlinePlayback == null &&
                offlineDownloadRepository.getEpisodeStates(
                    sourceId = state.currentSourceId,
                    episodeIds = listOf(effectiveEpisodeId),
                )[effectiveEpisodeId] == OfflineEpisodeDownloadState.Completed
            val offlinePlaybackElapsedMs = SystemClock.elapsedRealtime() - offlinePlaybackStartedAt
            AppLogger.d(
                PLAYBACK_LOG_TAG,
                "[viewmodel.preflight] totalMs=${SystemClock.elapsedRealtime() - loadStartedAt}, " +
                    "offlineEpisodesMs=$offlineEpisodesElapsedMs offlineCount=${offlineEpisodes.size}, " +
                    "cachedEpisodesMs=$cachedEpisodesElapsedMs episodeCount=${episodesResult.getOrDefault(emptyList()).size}, " +
                    "offlinePlaybackMs=$offlinePlaybackElapsedMs hasOffline=${offlinePlayback != null} brokenDownload=$hasBrokenOfflineDownload",
            )
            if (hasBrokenOfflineDownload) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        playback = null,
                        isPlayingOffline = false,
                        errorMessage = null,
                        offlinePlaybackFailed = true,
                        episodes = episodes,
                        currentEpisodeId = effectiveEpisodeId,
                        currentEpisodeNumber = effectiveEpisodeNumber,
                    )
                }
                return@launch
            }
            // Every stream this episode has already failed on, not only the one that failed last:
            // a second attempt that remembers only the newest failure can resolve straight back to
            // the first one, and the retry cap then spends itself alternating between two dead
            // candidates.
            val unplayable = _uiState.value.failedStreamUrls + excludedStreamUrls
            val offlineCandidate = offlinePlayback?.takeIf { it.streamUrl !in unplayable }
            val resolutionStartedAt = SystemClock.elapsedRealtime()
            AppLogger.d(
                PLAYBACK_LOG_TAG,
                "[viewmodel.resolve.start] offline=${offlineCandidate != null} " +
                    "selection=${if (state.selectedPlayerName.isNullOrBlank()) "automatic" else "preferred"} " +
                    "sinceViewModelMs=${resolutionStartedAt - viewModelCreatedAt}",
            )
            val playbackResult = runCatching {
                offlineCandidate
                    ?: if (state.selectedPlayerName.isNullOrBlank() && state.selectedQualityLabel.isNullOrBlank()) {
                        repository.resolveFastestStream(
                            sourceId = state.currentSourceId,
                            episodeId = effectiveEpisodeId,
                            forceRefresh = forceRefresh,
                            excludedStreamUrls = unplayable,
                        ).playback
                    } else {
                        repository.resolveStream(
                            sourceId = state.currentSourceId,
                            episodeId = effectiveEpisodeId,
                            forceRefresh = forceRefresh,
                            excludedStreamUrls = unplayable,
                            preferredPlayerName = state.selectedPlayerName,
                            preferredQuality = state.selectedQualityLabel,
                            // A player the user picked before (or is picking right now) for this
                            // title+voiceover is a decision, not a hint - without this it was only
                            // ever a soft ordering preference, so a quality-label mismatch or a
                            // resolution hiccup could silently hand the episode to a different
                            // player than the one they chose, with no sign anything had switched.
                            requiredPlayerName = state.selectedPlayerName,
                        )
                    }
            }.throwIfCancelled()
            currentCoroutineContext().ensureActive()
            playbackResult.onSuccess {
                AppLogger.d(
                    PLAYBACK_LOG_TAG,
                    "[viewmodel.resolve.ready] elapsedMs=${SystemClock.elapsedRealtime() - resolutionStartedAt}, " +
                        "totalLoadMs=${SystemClock.elapsedRealtime() - loadStartedAt}, " +
                        "offline=${offlineCandidate != null}, qualities=${it.availableQualityLabels.size}, " +
                        "subtitles=${it.subtitles.size}",
                )
            }.onFailure { error ->
                AppLogger.w(
                    PLAYBACK_LOG_TAG,
                    "[viewmodel.resolve.failed] elapsedMs=${SystemClock.elapsedRealtime() - resolutionStartedAt}, " +
                        "totalLoadMs=${SystemClock.elapsedRealtime() - loadStartedAt}, error=${error.javaClass.simpleName}",
                )
            }

            playbackResult
                .onSuccess { resolvedStream ->
                    val stream = if (offlineCandidate != null) {
                        offlineTitleMetadataRepository.get(titleId)?.title
                            ?.takeIf(String::isNotBlank)
                            ?.let { resolvedStream.copy(animeTitle = it) }
                            ?: resolvedStream
                    } else {
                        resolvedStream
                    }
                    val savedSeekMs = findSavedSeekMs(
                        episodeId = effectiveEpisodeId,
                        episodes = episodes,
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            playback = stream,
                            isPlayingOffline = offlineCandidate != null,
                            animeTitle = stream.animeTitle.trim().takeIf(String::isNotBlank)
                                ?: it.animeTitle,
                            errorMessage = null,
                            offlinePlaybackFailed = false,
                            episodes = episodes,
                            currentEpisodeId = effectiveEpisodeId,
                            currentEpisodeNumber = effectiveEpisodeNumber,
                            pendingSeekMs = savedSeekMs ?: it.pendingSeekMs,
                            failedStreamUrls = it.failedStreamUrls - stream.streamUrl,
                            selectedQualityLabel = stream.qualityLabel ?: it.selectedQualityLabel,
                            availableQualityLabels = stream.availableQualityLabels,
                        )
                    }
                }
                .onFailure { throwable ->
                    if (throwable is CancellationException) return@onFailure
                    AppLogger.e(
                        PLAYBACK_LOG_TAG,
                        "[viewmodel.load.fail] sourceId=${state.currentSourceId} episodeId=${state.currentEpisodeId} error=${throwable.javaClass.simpleName}:${throwable.message}",
                        throwable
                    )
                    // The episode is downloaded and it was the local file that just failed, so a
                    // failed fallback resolution says nothing about the connection - the file is on
                    // the device either way. Reporting the network message here is what made a
                    // broken download look like "no internet"; the screen shows a downloaded-file
                    // error and offers a re-download for this case instead.
                    val downloadedFileOffline = offlinePlayback != null &&
                        offlinePlayback.streamUrl in unplayable &&
                        throwable is NoInternetConnectionException
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            playback = null,
                            isPlayingOffline = false,
                            errorMessage = if (downloadedFileOffline) null else throwable.toUiMessage(),
                            offlinePlaybackFailed = downloadedFileOffline,
                            episodes = episodes,
                            currentEpisodeId = effectiveEpisodeId,
                            currentEpisodeNumber = effectiveEpisodeNumber,
                        )
                    }
                }
        }
    }

    fun selectEpisode(
        episodeId: String,
        resumePositionMs: Long = 0L,
        episodeNumberHint: Double? = null,
    ) {
        if (episodeId == _uiState.value.currentEpisodeId && _uiState.value.playback != null) {
            return
        }
        settingsLoadJob?.cancel()
        settingsLoadingKey = null
        _uiState.update { currentState ->
            currentState.copy(
                currentEpisodeId = episodeId,
                currentEpisodeNumber = episodeNumberHint
                    ?: currentState.episodes.firstOrNull { it.id == episodeId }?.number
                    ?: currentState.currentEpisodeNumber,
                playback = null,
                pendingSeekMs = resumePositionMs.coerceAtLeast(0L),
                settingsOptionsKey = null,
                isSettingsLoading = false,
                failedStreamUrls = emptySet(),
                autoRecoveryCount = 0,
            )
        }
        load()
    }

    /**
     * Auto-triggered from [Player.Listener.onPlayerError]. A resolved stream can pass the source's
     * own resolution step and still be unplayable for a reason that has nothing to do with which
     * candidate URL was picked (e.g. a relay CDN that's CORS-blocked no matter which mirror is
     * tried) - without a cap, every such failure looks like "try the next candidate" and the player
     * retries forever, stuck on a loading spinner with no visible error. [MAX_AUTO_RECOVERY_ATTEMPTS]
     * bounds that; past it, a real error is surfaced instead of another silent retry.
     */
    fun recoverFromPlaybackError(streamUrl: String?) {
        val state = _uiState.value
        if (state.isLoading) return
        if (state.isPlayingOffline) {
            // Local playback is deliberately cache-only. Retrying its URL through the resolver
            // would turn a corrupt saved file into unannounced network playback.
            _uiState.update {
                it.copy(
                    playback = null,
                    isPlayingOffline = false,
                    errorMessage = null,
                    offlinePlaybackFailed = true,
                )
            }
            return
        }
        if (state.autoRecoveryCount >= MAX_AUTO_RECOVERY_ATTEMPTS) {
            _uiState.update {
                it.copy(errorMessage = "Не удалось воспроизвести поток после нескольких попыток")
            }
            return
        }
        val excluded = streamUrl?.takeIf(String::isNotBlank)?.let { setOf(it) } ?: emptySet()
        // Deliberately not a forced refresh on the first try. A browser-resolved link offers two
        // candidates - the direct CDN URL and the same stream relayed through the WebView that
        // resolved it, which is the one that gets past bot management on TLS fingerprint - and the
        // point of this retry is to reach the second. Forcing a refresh instead throws away the
        // resolution that produced both, discards the relay session behind the second, and can mint
        // a fresh direct URL that the exclusion above no longer matches, so the player fails on the
        // same kind of stream until the cap is spent. A refresh is what a second failure means: the
        // resolution itself has gone stale.
        load(forceRefresh = state.autoRecoveryCount > 0, excludedStreamUrls = excluded)
    }

    /**
     * Re-downloads the episode whose local file could not be played (see
     * [PlayerUiState.offlinePlaybackFailed]). The download queue resolves a fresh stream for the
     * episode, so this needs a connection - offline it reports that, which is the accurate reason
     * rather than a wrong "no internet" for a file that is already on the device.
     */
    fun redownloadCurrentEpisode() {
        val state = _uiState.value
        if (state.isLoading) return
        val episode = state.episodes.firstOrNull { it.id == state.currentEpisodeId } ?: return
        val source = WatchSource(
            sourceId = state.currentSourceId,
            title = savedSelection?.sourceTitle.orEmpty(),
            episodeCount = state.episodes.size,
            qualityLabel = savedSelection?.quality,
        )
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                offlinePlaybackFailed = false,
                failedStreamUrls = emptySet(),
                autoRecoveryCount = 0,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                offlineDownloadRepository.redownloadEpisode(source = source, episode = episode)
            }.onSuccess {
                AppLogger.d(
                    PLAYBACK_LOG_TAG,
                    "[viewmodel.redownload] replacement scheduled sourceId=${source.sourceId} episodeId=${episode.id}",
                )
                _uiState.update { it.copy(isLoading = false) }
            }.onFailure { throwable ->
                if (throwable is CancellationException) return@onFailure
                AppLogger.e(
                    PLAYBACK_LOG_TAG,
                    "[viewmodel.redownload.fail] sourceId=${source.sourceId} episodeId=${episode.id} " +
                        "error=${throwable.javaClass.simpleName}:${throwable.message}",
                    throwable,
                )
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = throwable.toUiMessage())
                }
            }
        }
    }

    fun loadSettingsOptions() {
        val state = _uiState.value
        val optionsKey = state.settingsOptionsKey()
        if (state.isPlayingOffline) {
            // Player/quality alternatives require a resolver request. A downloaded episode is
            // intentionally self-contained, so its settings stay local (speed and display
            // options) instead of waking the network just because the sheet was opened.
            _uiState.update {
                it.copy(
                    isSettingsLoading = false,
                    settingsOptions = PlaybackSettingsOptions(),
                    settingsOptionsKey = optionsKey,
                )
            }
            return
        }
        if (state.settingsOptionsKey == optionsKey ||
            (settingsLoadJob?.isActive == true && settingsLoadingKey == optionsKey)
        ) {
            return
        }
        settingsLoadJob?.cancel()
        settingsLoadingKey = optionsKey
        _uiState.update { it.copy(isSettingsLoading = true) }
        settingsLoadJob = viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                repository.getPlaybackSettingsOptions(
                    sourceId = state.currentSourceId,
                    episodeId = state.currentEpisodeId,
                )
            }.throwIfCancelled()
            currentCoroutineContext().ensureActive()
            val updatedState = _uiState.value
            if (updatedState.currentSourceId != state.currentSourceId || updatedState.currentEpisodeId != state.currentEpisodeId) {
                if (settingsLoadingKey == optionsKey) {
                    settingsLoadingKey = null
                    _uiState.update { it.copy(isSettingsLoading = false) }
                }
                return@launch
            }
            result
                .onSuccess { options ->
                // Opening the player settings already resolves its own options above. Do not
                // repeat the full episode-list request just to populate the "Episodes" entry:
                // the list from the screen that opened this player is kept by the repository.
                // A cold/deep-linked player still falls back to the source exactly once.
                val episodes = repository.getCachedEpisodes(state.currentSourceId)
                    ?: repository.getEpisodes(state.currentSourceId)
                    settingsLoadingKey = null
                    _uiState.update {
                        it.copy(
                            isSettingsLoading = false,
                            settingsOptions = options,
                            settingsOptionsKey = optionsKey,
                            episodes = episodes.ifEmpty { it.episodes },
                        )
                    }
                }
                .onFailure { throwable ->
                    if (throwable is CancellationException) return@onFailure
                    settingsLoadingKey = null
                    AppLogger.e(
                        PLAYBACK_LOG_TAG,
                        "[viewmodel.settings.fail] sourceId=${state.currentSourceId} episodeId=${state.currentEpisodeId} error=${throwable.javaClass.simpleName}:${throwable.message}",
                        throwable
                    )
                    _uiState.update {
                        it.copy(isSettingsLoading = false)
                    }
                }
        }
    }

    fun selectVoiceover(source: WatchSource, resumePositionMs: Long = 0L) {
        val currentEpisode = _uiState.value.episodes.firstOrNull { it.id == _uiState.value.currentEpisodeId } ?: return
        settingsLoadJob?.cancel()
        settingsLoadingKey = null
        viewModelScope.launch(Dispatchers.IO) {
            val episodes = repository.getEpisodes(source.sourceId)
            val matching = episodes.firstOrNull { it.number == currentEpisode.number } ?: episodes.firstOrNull() ?: return@launch
            val rememberedPlayer = watchStateRepository.getSelectedPlayer(titleId, source.sourceId)
            _uiState.update {
                it.copy(
                    currentSourceId = source.sourceId,
                    currentEpisodeId = matching.id,
                    episodes = episodes,
                    selectedPlayerName = rememberedPlayer,
                    selectedQualityLabel = source.qualityLabel,
                    pendingSeekMs = resumePositionMs.coerceAtLeast(0L),
                    settingsOptionsKey = null,
                    isSettingsLoading = false,
                    failedStreamUrls = emptySet(),
                    autoRecoveryCount = 0,
                )
            }
            watchStateRepository.saveSelectedSource(
                titleId = titleId,
                sourceId = source.sourceId,
                sourceTitle = source.title,
                quality = source.qualityLabel,
                playerName = rememberedPlayer,
                autoSelect = false,
            )
            restoreSavedSeek()
            load()
        }
    }

    fun selectPlayer(playerName: String?, resumePositionMs: Long = 0L) {
        _uiState.update {
            it.copy(
                selectedPlayerName = playerName,
                playback = null,
                pendingSeekMs = resumePositionMs.coerceAtLeast(0L),
                failedStreamUrls = emptySet(),
                autoRecoveryCount = 0,
            )
        }
        persistSelection()
        load()
    }

    fun selectQuality(qualityLabel: String?, resumePositionMs: Long = 0L) {
        _uiState.update {
            it.copy(
                selectedQualityLabel = qualityLabel,
                playback = null,
                pendingSeekMs = resumePositionMs.coerceAtLeast(0L),
                failedStreamUrls = emptySet(),
                autoRecoveryCount = 0,
            )
        }
        persistSelection()
        load()
    }

    fun savePlaybackProgress(
        positionMs: Long,
        durationMs: Long,
        watchedSeconds: List<Long> = emptyList(),
    ) {
        val safePositionMs = positionMs.coerceAtLeast(0L)
        if (safePositionMs <= 0L) {
            return
        }
        // ExoPlayer may not know the duration yet. Keep that as 0 so the
        // repository can still resume from the saved position later.
        val safeDurationMs = durationMs.coerceAtLeast(0L)
        val state = _uiState.value
        val playback = state.playback ?: return
        val episode = state.episodes.firstOrNull { it.id == state.currentEpisodeId } ?: return
        // The player hands over every second it has seen play for this episode, cumulatively, so
        // what is new since the last save is the difference. Seeks never enter that set, which is
        // the entire point: skipping to the end of a film is not an hour and a half of watching.
        if (reportedWatchedSecondsEpisodeId != episode.id) {
            reportedWatchedSecondsEpisodeId = episode.id
            reportedWatchedSeconds = 0
        }
        val newWatchedSeconds = (watchedSeconds.size - reportedWatchedSeconds).coerceAtLeast(0)
        reportedWatchedSeconds = watchedSeconds.size
        watchStateRepository.saveSelectedSource(
            titleId = titleId,
            sourceId = state.currentSourceId,
            sourceTitle = playback.sourceTitle,
            quality = playback.qualityLabel,
            playerName = state.selectedPlayerName,
            autoSelect = false,
        )
        watchStateRepository.saveEpisodeProgress(
            titleId = titleId,
            episodeId = episode.id,
            episodeNumber = episode.number,
            sourceId = state.currentSourceId,
            voiceoverId = state.currentSourceId,
            sourceTitle = playback.sourceTitle,
            quality = playback.qualityLabel,
            positionMs = safePositionMs,
            durationMs = safeDurationMs,
            watchedMsDelta = newWatchedSeconds * 1_000L,
        )
    }

    /** Which episode [reportedWatchedSeconds] belongs to - the player's set restarts per episode. */
    private var reportedWatchedSecondsEpisodeId: String? = null
    private var reportedWatchedSeconds = 0

    private fun persistSelection() {
        val state = _uiState.value
        val previousSelection = watchStateRepository.getSelectedSource(titleId)
        watchStateRepository.saveSelectedPlayer(
            titleId = titleId,
            sourceId = state.currentSourceId,
            playerName = state.selectedPlayerName,
        )
        watchStateRepository.saveSelectedSource(
            titleId = titleId,
            sourceId = state.currentSourceId,
            sourceTitle = state.playback?.sourceTitle ?: previousSelection.sourceTitle,
            quality = state.selectedQualityLabel ?: state.playback?.qualityLabel,
            playerName = state.selectedPlayerName,
            autoSelect = false,
        )
    }

    fun consumePendingSeek() {
        _uiState.update { it.copy(pendingSeekMs = 0L) }
    }

    fun playPreviousEpisode() {
        adjacentEpisode(offset = -1)?.let { selectEpisode(it.id, episodeNumberHint = it.number) }
    }

    fun playNextEpisode() {
        adjacentEpisode(offset = 1)?.let { selectEpisode(it.id, episodeNumberHint = it.number) }
    }

    override fun onCleared() {
        loadJob?.cancel()
        settingsLoadJob?.cancel()
        subtitleJob?.cancel()
        // Unregister the SharedPreferences listener this VM's own AppPreferences registered.
        appPreferences.close()
        super.onCleared()
    }

    private fun restoreSavedSeek() {
        val state = _uiState.value
        val savedSeekMs = findSavedSeekMs(
            episodeId = state.currentEpisodeId,
            episodes = state.episodes,
        ) ?: return
        _uiState.update { it.copy(pendingSeekMs = savedSeekMs) }
    }

    private fun findSavedSeekMs(
        episodeId: String,
        episodes: List<WatchEpisode>,
    ): Long? {
        val state = _uiState.value
        val exactProgress = watchStateRepository.getEpisodeProgress(
            titleId = titleId,
            episodeId = episodeId,
            sourceId = state.currentSourceId,
        )
        val episodeNumber = episodes.firstOrNull { it.id == episodeId }?.number
        val numberProgress = episodeNumber?.let { number ->
            watchStateRepository.getEpisodeProgress(titleId)
                .filter { it.sourceId == state.currentSourceId && it.episodeNumber == number }
                .maxByOrNull { it.updatedAt }
        }
        val progress = exactProgress ?: numberProgress ?: return null
        return resumablePlaybackPositionMs(progress.positionMs, progress.durationMs)
    }

    private fun adjacentEpisode(offset: Int): WatchEpisode? {
        val state = _uiState.value
        val currentIndex = state.episodes.indexOfFirst { it.id == state.currentEpisodeId }
        if (currentIndex != -1) {
            return state.episodes.getOrNull(currentIndex + offset)
        }
        val currentEpisodeNumber = state.currentEpisodeNumber ?: return null
        return if (offset < 0) {
            state.episodes
                .filter { it.number < currentEpisodeNumber }
                .maxByOrNull { it.number }
        } else {
            state.episodes
                .filter { it.number > currentEpisodeNumber }
                .minByOrNull { it.number }
        }
    }

    private fun resolveCurrentEpisode(
        requestedEpisodeId: String,
        requestedEpisodeNumber: Double?,
        episodes: List<WatchEpisode>,
        currentEpisodes: List<WatchEpisode>,
    ): WatchEpisode? {
        episodes.firstOrNull { it.id == requestedEpisodeId }?.let { return it }

        val knownEpisodeNumber = requestedEpisodeNumber
            ?: currentEpisodes.firstOrNull { it.id == requestedEpisodeId }?.number
            ?: watchStateRepository.getEpisodeProgress(
                titleId = titleId,
                episodeId = requestedEpisodeId,
                sourceId = _uiState.value.currentSourceId,
            )?.episodeNumber

        return knownEpisodeNumber?.let { episodeNumber ->
            episodes.firstOrNull { it.number == episodeNumber }
        }
    }

    class Factory(
        private val sourceId: String,
        private val episodeId: String,
        private val initialEpisodeNumber: Double?,
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val dependencies = appContext.applicationContext.hibikiDependencies()
            return PlayerViewModel(
                sourceId = sourceId,
                episodeId = episodeId,
                initialEpisodeNumber = initialEpisodeNumber,
                repository = dependencies.animeWatchRepository(),
                watchStateRepository = dependencies.watchStateRepository(),
                offlineDownloadRepository = dependencies.offlineDownloadRepository(),
                offlineTitleMetadataRepository = dependencies.offlineTitleMetadataRepository(),
                persianSubtitleSearcher = dependencies.persianSubtitleSearcher(),
                subtitleTranslator = dependencies.subtitleTranslator().also { t ->
                    t.cache = org.akkirrai.hibiki.core.subtitle.SubtitleTranslationCache(
                        appContext.applicationContext,
                        kotlin.coroutines.EmptyCoroutineContext,
                    )
                },
                appPreferences = AppPreferences(appContext),
                appContext = appContext,
            ) as T
        }
    }
}

internal fun resumablePlaybackPositionMs(positionMs: Long, durationMs: Long): Long? {
    val position = positionMs.coerceAtLeast(0L).takeIf { it > 0L } ?: return null
    if (durationMs <= 0L) return position
    val duration = durationMs.coerceAtLeast(1L)
    val resetThresholdMs = maxOf(PLAYBACK_END_WINDOW_MS, duration * PLAYBACK_END_PERCENT / 100L)
    return position.takeIf { duration - position > resetThresholdMs }
}

data class PlayerUiState(
    val isLoading: Boolean = true,
    val playback: PlaybackStream? = null,
    /**
     * [playback] came from the local download cache. The screen builds a local-only data source for
     * it so a downloaded episode never falls through to the network (see
     * [org.akkirrai.hibiki.core.download.OfflineMediaCache.buildDownloadedPlaybackDataSourceFactory]).
     */
    val isPlayingOffline: Boolean = false,
    val animeTitle: String = "",
    val episodes: List<WatchEpisode> = emptyList(),
    val currentSourceId: String = "",
    val currentEpisodeId: String = "",
    val currentEpisodeNumber: Double? = null,
    val pendingSeekMs: Long = 0L,
    val errorMessage: String? = null,
    /**
     * The episode's local (downloaded) file failed to play and the only thing the fallback
     * resolution could report was the missing connection. The screen renders a downloaded-file
     * error with a re-download action for this state instead of the network message.
     */
    val offlinePlaybackFailed: Boolean = false,
    val failedStreamUrls: Set<String> = emptySet(),
    val autoRecoveryCount: Int = 0,
    val isSettingsLoading: Boolean = false,
    val settingsOptions: PlaybackSettingsOptions = PlaybackSettingsOptions(),
    val settingsOptionsKey: String? = null,
    val selectedPlayerName: String? = null,
    val selectedQualityLabel: String? = null,
    val availableQualityLabels: List<String> = emptyList(),
)

private fun PlayerUiState.settingsOptionsKey(): String =
    buildString {
        append(currentSourceId)
        append(':')
        append(currentEpisodeId)
    }

private fun <T> Result<T>.throwIfCancelled(): Result<T> {
    val error = exceptionOrNull()
    if (error is CancellationException) throw error
    return this
}

private const val PLAYBACK_LOG_TAG = "HibikiPlayback"
private const val PLAYBACK_END_WINDOW_MS = 30_000L
private const val PLAYBACK_END_PERCENT = 5L
// Enough for the two candidates a browser-resolved link offers - the direct URL and the relayed
// one - plus a move to another player after both fail.
private const val MAX_AUTO_RECOVERY_ATTEMPTS = 3
