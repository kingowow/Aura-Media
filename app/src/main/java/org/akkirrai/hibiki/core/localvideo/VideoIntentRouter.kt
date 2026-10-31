package org.akkirrai.hibiki.core.localvideo

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Single-shot bus that carries a local-video [videoId] from [MainActivity] (which receives the
 * ACTION_VIEW intent) to [HibikiApp] (which owns the nav controller).
 *
 * replay=1 ensures that even when MainActivity fires the event before HibikiApp has started
 * collecting (cold launch via Open-with), the latest videoId is buffered and delivered as soon
 * as the collector attaches. The buffer is intentionally capped at 1 — a second Open-with while
 * the player is already open just replaces the pending event.
 */
object VideoIntentRouter {
    private val _events = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 0)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Called from MainActivity on the main thread. */
    fun emit(videoId: String) {
        _events.tryEmit(videoId)
    }

    /** Reset the replay cache after the event has been consumed, so Back → re-open doesn't
     *  replay the same video unexpectedly. */
    fun reset() {
        _events.resetReplayCache()
    }
}
