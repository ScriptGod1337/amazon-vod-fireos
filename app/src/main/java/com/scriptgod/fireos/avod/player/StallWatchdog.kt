package com.scriptgod.fireos.avod.player

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class StallWatchdog(
    private val scope: CoroutineScope,
    private val onStall: (posMs: Long) -> Unit
) {
    companion object {
        private const val TAG = "StallWatchdog"
    }

    private var stallWatchdogJob: Job? = null
    var stallRestartCount = 0

    fun start(playerProvider: () -> ExoPlayer?) {
        if (stallWatchdogJob?.isActive == true) return  // already running; don't restart and reset delay
        stallWatchdogJob = scope.launch {
            var lastPos = -1L
            var frozenSince = 0
            while (true) {
                delay(2_000)
                val p = playerProvider() ?: break
                // Use playWhenReady, not isPlaying: during BUFFERING↔READY oscillation
                // isPlaying is false and would reset frozenSince every tick, preventing detection.
                // playWhenReady stays true whenever the user intends playback to run.
                if (!p.playWhenReady) { frozenSince = 0; lastPos = -1L; continue }
                val pos = p.currentPosition
                val buffered = p.totalBufferedDuration
                if (pos == lastPos && buffered > 30_000) {
                    frozenSince++
                    if (frozenSince >= 2) { // 4 seconds frozen
                        stallRestartCount++
                        if (stallRestartCount > 20) {
                            Log.e(TAG, "RENDERER_DECODER_STALLED: exceeded retry limit, giving up")
                            break
                        }
                        Log.w(TAG, "RENDERER_DECODER_STALLED: pos=${pos}ms buffered=${buffered}ms — invoking onStall (attempt $stallRestartCount/20)")
                        withContext(Dispatchers.Main) {
                            onStall(pos)
                        }
                        frozenSince = 0
                        lastPos = -1L
                        // continue loop — if still stuck after seek, will detect again
                    }
                } else {
                    frozenSince = 0
                }
                lastPos = pos
            }
        }
    }

    fun stop() {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = null
    }
}
