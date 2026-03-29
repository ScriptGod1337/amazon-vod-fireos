package com.scriptgod.fireos.avod.player

import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.scriptgod.fireos.avod.api.AmazonApiService
import com.scriptgod.fireos.avod.data.ProgressRepository  // object singleton — injected for explicit dependency
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
class StreamReporter(
    private val apiService: AmazonApiService,
    private val progressRepository: ProgressRepository,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "StreamReporter"
    }

    private var heartbeatJob: Job? = null
    var heartbeatIntervalMs: Long = 60_000
    var pesSessionToken: String = ""
    var streamReportingStarted: Boolean = false
    var watchSessionId: String = java.util.UUID.randomUUID().toString()
    private var lastResumeSaveElapsedMs: Long = 0L

    /** Called by PlayerActivity to resume progress updates on a timer. */
    private var resumeProgressCallback: (() -> Unit)? = null
    private var startResumeProgressUpdatesCallback: (() -> Unit)? = null
    private var stopResumeProgressUpdatesCallback: (() -> Unit)? = null

    fun cancelHeartbeat() {
        heartbeatJob?.cancel()
    }

    fun startStreamReporting(
        positionProvider: () -> Long,
        currentAsinProvider: () -> String,
        onStartResumeProgressUpdates: () -> Unit,
        onStopResumeProgressUpdates: () -> Unit
    ) {
        startResumeProgressUpdatesCallback = onStartResumeProgressUpdates
        stopResumeProgressUpdatesCallback = onStopResumeProgressUpdates
        val positionSecs = positionProvider() / 1000
        val currentAsin = currentAsinProvider()
        scope.launch(Dispatchers.IO) {
            // UpdateStream START
            val interval = apiService.updateStream(currentAsin, "START", positionSecs, watchSessionId)
            heartbeatIntervalMs = interval * 1000L

            // PES V2 StartSession
            val (token, pesInterval) = apiService.pesStartSession(currentAsin, positionSecs)
            pesSessionToken = token
            // Use the shorter of the two intervals
            if (pesInterval > 0) {
                heartbeatIntervalMs = minOf(heartbeatIntervalMs, pesInterval * 1000L)
            }
            Log.w(TAG, "Stream reporting started, heartbeat=${heartbeatIntervalMs}ms")
        }
        startHeartbeat(positionProvider, currentAsinProvider)
        onStartResumeProgressUpdates()
    }

    fun startHeartbeat(
        positionProvider: () -> Long,
        currentAsinProvider: () -> String
    ) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            delay(heartbeatIntervalMs)
            while (true) {
                sendProgressEvent("PLAY", positionProvider, currentAsinProvider)
                delay(heartbeatIntervalMs)
            }
        }
    }

    fun sendProgressEvent(
        event: String,
        positionProvider: () -> Long,
        currentAsinProvider: () -> String,
        persistCallback: ((Boolean) -> Unit)? = null
    ) {
        val positionSecs = positionProvider() / 1000
        persistCallback?.invoke(event != "PLAY")
        val currentAsin = currentAsinProvider()
        scope.launch(Dispatchers.IO) {
            // UpdateStream
            val interval = apiService.updateStream(currentAsin, event, positionSecs, watchSessionId)
            val newIntervalMs = interval * 1000L
            if (newIntervalMs != heartbeatIntervalMs) {
                heartbeatIntervalMs = newIntervalMs
                Log.w(TAG, "Heartbeat interval updated to ${heartbeatIntervalMs}ms")
            }

            // PES V2 UpdateSession
            if (pesSessionToken.isNotEmpty()) {
                val (token, _) = apiService.pesUpdateSession(pesSessionToken, event, positionSecs, currentAsin)
                if (token.isNotEmpty()) pesSessionToken = token
            }
        }
    }

    fun stopStreamReporting(
        positionProvider: () -> Long,
        currentAsinProvider: () -> String,
        onStopResumeProgressUpdates: () -> Unit
    ) {
        heartbeatJob?.cancel()
        onStopResumeProgressUpdates()
        val positionSecs = positionProvider() / 1000
        val currentAsin = currentAsinProvider()
        scope.launch(Dispatchers.IO) {
            apiService.updateStream(currentAsin, "STOP", positionSecs, watchSessionId)
            if (pesSessionToken.isNotEmpty()) {
                apiService.pesStopSession(pesSessionToken, positionSecs, currentAsin)
                pesSessionToken = ""
            }
        }
    }

    fun persistPlaybackProgress(
        force: Boolean,
        positionProvider: () -> Long,
        durationProvider: () -> Long,
        currentAsinProvider: () -> String,
        materialTypeProvider: () -> String,
        seriesAsinProvider: () -> String,
        seasonAsinProvider: () -> String
    ) {
        val posMs = positionProvider()
        if (!force && posMs <= 0L) return
        if (!force && posMs - lastResumeSaveElapsedMs < 25_000L) return
        progressRepository.update(
            currentAsinProvider(),
            posMs,
            durationProvider(),
            materialTypeProvider(),
            seriesAsinProvider(),
            seasonAsinProvider()
        )
        lastResumeSaveElapsedMs = posMs
    }

}
