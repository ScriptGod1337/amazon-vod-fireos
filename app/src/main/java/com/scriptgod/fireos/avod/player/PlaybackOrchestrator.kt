package com.scriptgod.fireos.avod.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.scriptgod.fireos.avod.api.AmazonApiService
import com.scriptgod.fireos.avod.auth.AmazonAuthService
import com.scriptgod.fireos.avod.data.ProgressRepository
import com.scriptgod.fireos.avod.drm.AmazonLicenseService
import com.scriptgod.fireos.avod.model.ContentKind
import com.scriptgod.fireos.avod.model.PlaybackInfo
import com.scriptgod.fireos.avod.model.PlaybackQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Owns the full playback session lifecycle: quality resolution, MPD fetch, ExoPlayer setup,
 * DRM, stream reporting, stall recovery, and auto-next-episode.
 *
 * PlayerActivity delegates all stateful playback work here and receives UI-relevant
 * events through [Callbacks].
 */
@UnstableApi
class PlaybackOrchestrator(
    private val context: Context,
    private val authService: AmazonAuthService,
    private val apiService: AmazonApiService,
    private val scope: CoroutineScope,
    /** Checks whether the connected display reports HDR support (uses Activity.windowManager). */
    private val displaySupportsHdr: () -> Boolean,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onPlayerReady(player: ExoPlayer, trackSelector: DefaultTrackSelector)
        fun showBuffering()
        fun hideBuffering()
        fun onError(message: String)
        fun onFinish()
        fun onPlaybackLabelsChanged()
        fun onTracksChanged(tracks: Tracks)
        fun onKeepScreenOn(on: Boolean)
        fun onTitleChanged(title: String)
        fun onQualityResolved(materialType: String, quality: PlaybackQuality)
    }

    companion object {
        private const val TAG = "PlaybackOrchestrator"
        private const val PREF_AUDIO_PASSTHROUGH        = "audio_passthrough"
        private const val PREF_AUDIO_PASSTHROUGH_WARNED = "audio_passthrough_warned"
        private const val PREF_WIDEVINE_L3_WARNED       = "widevine_l3_warned"
        private val WIDEVINE_UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
    }

    // --- Public state accessed by PlayerActivity for display / diagnostics ---
    var player: ExoPlayer? = null
        private set
    var currentPlaybackInfo: PlaybackInfo? = null
        private set
    var currentAsin: String = ""
    var currentSeriesAsin: String = ""
    var currentSeasonAsin: String = ""
    var currentMaterialType: String = "Feature"
        private set
    var currentQuality: PlaybackQuality = PlaybackQuality.HD
        private set
    var currentVideoBitrateKbps: Int = 0
        private set
    var currentAudioBitrateKbps: Int = 0
        private set
    var currentAudioChannelCount: Int = 0
        private set

    // Exposed so SeekPreviewController and TrackMenuController can share them
    val audioTrackResolver: AudioTrackResolver = AudioTrackResolver(emptyList())
    val bifThumbnailProvider: BifThumbnailProvider = BifThumbnailProvider(scope)

    // --- Private playback state ---
    private var correctedMpdContent: String? = null
    private var currentMediaSource: androidx.media3.exoplayer.source.MediaSource? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var currentAudioSampleMimeType: String = ""
    private var currentAudioCodecs: String = ""
    private var currentAudioSinkMode: String = "unknown"
    private var currentAudioSinkCapabilities: String = "unknown"
    private var resumeSeeked: Boolean = false
    private var seekResyncPending: Boolean = false
    private var lastMediaSegmentUrl: String = ""
    private var lastVideoSegmentUrl: String = ""
    private var lastAudioSegmentUrl: String = ""
    private var audioRestartDone: Boolean = false
    private var playbackJob: Job? = null

    private val streamReporter: StreamReporter = StreamReporter(apiService, ProgressRepository, scope)
    // lateinit so the lambda can reference stallWatchdog.stallRestartCount without a forward-ref error
    private lateinit var stallWatchdog: StallWatchdog

    init {
        stallWatchdog = StallWatchdog(scope) { pos ->
            val p = player ?: return@StallWatchdog
            logPlaybackSnapshot(
                "RENDERER_DECODER_STALLED",
                "stallRestartCount=${stallWatchdog.stallRestartCount} " +
                    "lastVideoSegment=$lastVideoSegmentUrl lastAudioSegment=$lastAudioSegmentUrl"
            )
            if (stallWatchdog.stallRestartCount > 20) {
                callbacks.onError("Playback stalled and could not be recovered automatically.")
                return@StallWatchdog
            }
            val skipTo = pos + 10_000L
            Log.w(TAG, "RENDERER_DECODER_STALLED: pos=${pos}ms — seeking to ${skipTo}ms (attempt ${stallWatchdog.stallRestartCount}/20)")
            p.seekTo(skipTo)
        }
    }

    private val resumeProgressHandler = Handler(Looper.getMainLooper())
    private val resumeProgressRunnable = object : Runnable {
        override fun run() {
            persistPlaybackProgress(force = false)
            if (player?.isPlaying == true) resumeProgressHandler.postDelayed(this, 30_000L)
        }
    }

    // Called from authService.onMediaRequestObserved
    fun onMediaUrlObserved(url: String) {
        lastMediaSegmentUrl = url
        val lower = url.lowercase()
        when {
            lower.contains("_video_") -> lastVideoSegmentUrl = url
            lower.contains("_audio_") -> lastAudioSegmentUrl = url
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun loadAndPlay(asin: String, materialType: String, intentResumeMs: Long = 0L) {
        Log.w(TAG, "loadAndPlay asin=$asin type=$materialType season=$currentSeasonAsin")
        callbacks.showBuffering()
        stallWatchdog.stallRestartCount = 0
        audioRestartDone = false
        currentAudioBitrateKbps = 0; currentAudioChannelCount = 0
        currentAudioSampleMimeType = ""; currentAudioCodecs = ""
        currentAudioSinkMode = "unknown"; currentAudioSinkCapabilities = "unknown"
        currentVideoBitrateKbps = 0

        val quality = resolveQuality()
        currentMaterialType = materialType
        currentQuality = quality
        callbacks.onQualityResolved(materialType, quality)
        Log.i(TAG, "Playback quality: ${quality.videoQuality} codec=${quality.codecOverride} hdr=${quality.hdrOverride}")

        playbackJob?.cancel()
        playbackJob = scope.launch {
            try {
                val (info, detailAudioTracks) = withContext(Dispatchers.IO) {
                    apiService.detectTerritory()
                    val playbackInfo = apiService.getPlaybackInfo(asin, materialType, quality, streamReporter.watchSessionId)
                    val detailAudio = apiService.getDetailInfo(asin)?.audioTracks ?: emptyList()
                    playbackInfo to detailAudio
                }
                val mergedTracks = (info.audioTracks + detailAudioTracks)
                    .distinctBy { "${it.displayName}|${it.languageCode}|${it.type}|${it.index}" }
                audioTrackResolver.updateAvailableTracks(mergedTracks)
                PlaybackLogger.logAvailableAudioTracks(TAG, "Merged audio metadata", mergedTracks)
                correctedMpdContent = withContext(Dispatchers.IO) {
                    try {
                        MpdTimingCorrector.correctMpd(info.manifestUrl, authService.buildAuthenticatedClient())
                    } catch (e: Exception) {
                        Log.e(TAG, "MPD correction failed: ${e.message}"); null
                    }
                }
                currentPlaybackInfo = info
                if (info.bifUrl.isNotEmpty()) bifThumbnailProvider.loadIndex(info.bifUrl)
                setupPlayer(info, intentResumeMs = intentResumeMs)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch playback info: ${e.message}", e)
                callbacks.onError("Playback error: ${e.message}")
            }
        }
    }

    fun restartPlayerAtPosition(posMs: Long, reason: String, stereoOnly: Boolean = false) {
        Log.w(TAG, "PLAYER_RESTART: reason=$reason posMs=$posMs stereoOnly=$stereoOnly attempt=${stallWatchdog.stallRestartCount}")
        stallWatchdog.stop()
        stopStreamReporting()
        releasePlayer()
        streamReporter.streamReportingStarted = false
        resumeSeeked = false
        callbacks.showBuffering()

        playbackJob?.cancel()
        playbackJob = scope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    apiService.getPlaybackInfo(currentAsin, currentMaterialType, currentQuality, streamReporter.watchSessionId)
                }
                currentPlaybackInfo = info
                if (info.bifUrl.isNotEmpty()) bifThumbnailProvider.loadIndex(info.bifUrl)
                setupPlayer(info, startPositionOverride = posMs, stereoOnly = stereoOnly)
            } catch (e: Exception) {
                Log.e(TAG, "PLAYER_RESTART: PRS re-fetch failed: ${e.message}", e)
                callbacks.onError("Restart failed: ${e.message}")
            }
        }
    }

    fun persistPlaybackProgress(force: Boolean) {
        val p = player ?: return
        streamReporter.persistPlaybackProgress(
            force = force,
            positionProvider = { p.currentPosition },
            durationProvider = { p.duration },
            currentAsinProvider = { currentAsin },
            materialTypeProvider = { currentMaterialType },
            seriesAsinProvider = { currentSeriesAsin },
            seasonAsinProvider = { currentSeasonAsin }
        )
    }

    fun stopReportingIfActive() {
        if (streamReporter.streamReportingStarted) {
            stopStreamReporting()
            streamReporter.streamReportingStarted = false
        }
    }

    fun release() {
        stopResumeProgressUpdates()
        releasePlayer()
    }

    fun logPlaybackSnapshot(reason: String, extra: String = "") {
        val p = player ?: return
        val passthroughEnabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean(PREF_AUDIO_PASSTHROUGH, false)
        val suffix = if (extra.isBlank()) "" else " $extra"
        Log.i(
            TAG,
            "$reason pos=${p.currentPosition}ms buffered=${p.totalBufferedDuration}ms " +
                "state=${VideoFormatLabeler.playbackStateName(p.playbackState)} isPlaying=${p.isPlaying} " +
                "playWhenReady=${p.playWhenReady} loading=${p.isLoading} " +
                "audio=${selectedAudioTrackSummary()} passthrough=$passthroughEnabled " +
                "sinkMode=$currentAudioSinkMode sinkCaps={$currentAudioSinkCapabilities} " +
                "audioInput=mime=${currentAudioSampleMimeType.ifBlank { "-" }} codecs=${currentAudioCodecs.ifBlank { "-" }}$suffix"
        )
    }

    fun selectedAudioTrackSummary(tracks: Tracks? = player?.currentTracks): String {
        val t = tracks ?: return "none"
        val opt = audioTrackResolver.buildTrackOptions(C.TRACK_TYPE_AUDIO, t).firstOrNull { it.isSelected }
            ?: return "none"
        val fmt = t.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            .firstOrNull { g -> (0 until g.length).any { g.isTrackSelected(it) } }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
        return "menu=${opt.label} ${VideoFormatLabeler.formatSummary(fmt)}"
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun resolveQuality(): PlaybackQuality {
        if (DeviceCapabilities.widevineSecurityLevel() != "L1") {
            Log.w(TAG, "Widevine L3 detected — forcing SD quality (HD requires L1 + HDCP)")
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_WIDEVINE_L3_WARNED, false)) {
                prefs.edit().putBoolean(PREF_WIDEVINE_L3_WARNED, true).apply()
                Toast.makeText(context, "Widevine L3 device — SD quality only (HD requires hardware DRM)", Toast.LENGTH_LONG).show()
            }
            return PlaybackQuality.SD
        }
        val pref = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString(PlaybackQuality.PREF_KEY, null)
        val requested = PlaybackQuality.fromPrefValue(pref)
        if (requested == PlaybackQuality.UHD_HDR) {
            if (!DeviceCapabilities.deviceSupportsH265()) {
                Toast.makeText(context, "H265 not supported — using HD H264", Toast.LENGTH_LONG).show()
                return PlaybackQuality.HD
            }
            if (!displaySupportsHdr()) {
                Toast.makeText(context, "Display does not support HDR — using HD H264", Toast.LENGTH_LONG).show()
                return PlaybackQuality.HD
            }
        }
        if (requested == PlaybackQuality.HD_H265 && !DeviceCapabilities.deviceSupportsH265()) {
            Toast.makeText(context, "H265 not supported — using HD H264", Toast.LENGTH_LONG).show()
            return PlaybackQuality.HD
        }
        return requested
    }

    private fun setupPlayer(
        info: PlaybackInfo,
        startPositionOverride: Long? = null,
        intentResumeMs: Long = 0L,
        stereoOnly: Boolean = false
    ) {
        releasePlayer()
        Log.i(TAG, buildString {
            append("Playback info: manifest=").append(info.manifestUrl)
            append(" license=").append(info.licenseUrl)
            append(" resumeSeeked=").append(resumeSeeked)
            append(" selectedQuality=").append(currentQuality)
            append(" startPositionOverride=").append(startPositionOverride)
            append(" stereoOnly=").append(stereoOnly)
            append(" stallRestartCount=").append(stallWatchdog.stallRestartCount)
            append(" passthroughRequested=")
                .append(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(PREF_AUDIO_PASSTHROUGH, false))
            append(" lastMediaSegment=").append(lastMediaSegmentUrl)
            append(" lastVideoSegment=").append(lastVideoSegmentUrl)
            append(" lastAudioSegment=").append(lastAudioSegmentUrl)
        })

        val drmDiag = java.io.File(context.getExternalFilesDir(null), "drm_diag.txt").also { it.delete() }
        val licenseCallback = AmazonLicenseService(authService, info.licenseUrl, context, drmDiag)
        val drmSessionManager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(false)
            .build(licenseCallback)

        val passthroughEnabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean(PREF_AUDIO_PASSTHROUGH, false)

        // Amazon's audio is CLEAR (not encrypted) despite the CENC declaration in the MPD.
        // AudioCapabilities controls which encodings the sink will try to pass through:
        //   enabled  → real device caps (EAC3/AC3 passthrough if display supports it)
        //   disabled → DEFAULT_AUDIO_CAPABILITIES (stereo PCM only)
        val renderersFactory = object : DefaultRenderersFactory(context) {
            init { setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF); setEnableDecoderFallback(true) }
            override fun buildAudioSink(ctx: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val caps = if (passthroughEnabled) AudioCapabilities.getCapabilities(ctx)
                           else AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
                currentAudioSinkMode = if (passthroughEnabled) "passthrough-capable" else "pcm-only"
                currentAudioSinkCapabilities =
                    "eac3=${caps.supportsEncoding(C.ENCODING_E_AC3)} " +
                    "ac3=${caps.supportsEncoding(C.ENCODING_AC3)} " +
                    "pcmOnly=${caps == AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES}"
                Log.i(TAG, "AUDIO_SINK mode=$currentAudioSinkMode requestedPassthrough=$passthroughEnabled caps={$currentAudioSinkCapabilities}")
                return DefaultAudioSink.Builder(ctx)
                    .setAudioCapabilities(caps)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }

        val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            authService.buildAuthenticatedClient()
        )
        val correctedMpd = correctedMpdContent
        val dataSourceFactory = if (correctedMpd != null) {
            val manifestUri = android.net.Uri.parse(info.manifestUrl)
            val correctedBytes = correctedMpd.toByteArray()
            androidx.media3.datasource.DataSource.Factory {
                object : androidx.media3.datasource.DataSource {
                    private var httpSource: androidx.media3.datasource.DataSource? = null
                    private var byteStream: java.io.ByteArrayInputStream? = null
                    private var isManifest = false
                    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                        if (dataSpec.uri.path == manifestUri.path) {
                            isManifest = true; byteStream = java.io.ByteArrayInputStream(correctedBytes)
                            return correctedBytes.size.toLong()
                        }
                        isManifest = false; httpSource = httpDataSourceFactory.createDataSource()
                        return httpSource!!.open(dataSpec)
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        if (isManifest) byteStream?.read(buffer, offset, length) ?: -1
                        else httpSource?.read(buffer, offset, length) ?: -1
                    override fun addTransferListener(l: androidx.media3.datasource.TransferListener) {}
                    override fun getUri(): android.net.Uri? = if (isManifest) manifestUri else httpSource?.uri
                    override fun close() { byteStream?.close(); byteStream = null; httpSource?.close(); httpSource = null }
                    override fun getResponseHeaders(): Map<String, List<String>> = httpSource?.responseHeaders ?: emptyMap()
                }
            }
        } else httpDataSourceFactory

        val dashSource = DashMediaSource.Factory(dataSourceFactory)
            .setDrmSessionManagerProvider(drmSessionManager.asDrmSessionManagerProvider())
            .createMediaSource(MediaItem.Builder().setUri(info.manifestUrl).build())

        Log.w(TAG, "Subtitle tracks from API: ${info.subtitleTracks.size}")
        val subtitleSources = info.subtitleTracks.map { sub ->
            SingleSampleMediaSource.Factory(dataSourceFactory).createMediaSource(
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                    .setMimeType(MimeTypes.APPLICATION_TTML)
                    .setLanguage(sub.languageCode)
                    .setLabel(AudioTrackLabeler.buildSubtitleLabel(sub.languageCode, sub.type))
                    .setSelectionFlags(if (sub.type == "forced") C.SELECTION_FLAG_FORCED else 0)
                    .build(),
                C.TIME_UNSET
            )
        }
        currentMediaSource = if (subtitleSources.isNotEmpty())
            MergingMediaSource(dashSource, *subtitleSources.toTypedArray()) else dashSource

        val selector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setPreferredAudioRoleFlags(C.ROLE_FLAG_MAIN)
                .setMaxAudioChannelCount(if (stereoOnly) 2 else Int.MAX_VALUE)
                .build()
        }
        trackSelector = selector

        val serverResumeMs = intentResumeMs.takeIf { it > 0L }
            ?: ProgressRepository.get(currentAsin)?.positionMs?.coerceAtLeast(0L)
            ?: 0L
        val resumeMs = when {
            currentMaterialType == "Trailer" -> 0L
            startPositionOverride != null    -> startPositionOverride
            else                             -> serverResumeMs
        }

        val exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(selector)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        exoPlayer.setMediaSource(currentMediaSource!!)
        exoPlayer.addListener(playerListener)
        exoPlayer.addAnalyticsListener(analyticsListener)
        exoPlayer.prepare()
        if (resumeMs > 10_000) { exoPlayer.seekTo(resumeMs); resumeSeeked = true }
        exoPlayer.playWhenReady = true
        player = exoPlayer

        logPlaybackSnapshot("PLAYER_SETUP")
        if (passthroughEnabled) {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_AUDIO_PASSTHROUGH_WARNED, false)) {
                prefs.edit().putBoolean(PREF_AUDIO_PASSTHROUGH_WARNED, true).apply()
                Toast.makeText(context, "Audio passthrough on — volume is controlled by your AV receiver", Toast.LENGTH_LONG).show()
            }
        }
        callbacks.onPlayerReady(exoPlayer, selector)
        callbacks.hideBuffering()
        callbacks.onTracksChanged(exoPlayer.currentTracks)
    }

    private fun releasePlayer() {
        player?.removeListener(playerListener)
        player?.removeAnalyticsListener(analyticsListener)
        player?.release()
        player = null
    }

    private fun startStreamReporting() {
        streamReporter.startStreamReporting(
            positionProvider = { player?.currentPosition ?: 0L },
            currentAsinProvider = { currentAsin },
            onStartResumeProgressUpdates = { startResumeProgressUpdates() },
            onStopResumeProgressUpdates = { stopResumeProgressUpdates() }
        )
    }

    private fun startHeartbeat() {
        streamReporter.startHeartbeat(
            positionProvider = { player?.currentPosition ?: 0L },
            currentAsinProvider = { currentAsin }
        )
    }

    private fun stopStreamReporting() {
        streamReporter.stopStreamReporting(
            positionProvider = { player?.currentPosition ?: 0L },
            currentAsinProvider = { currentAsin },
            onStopResumeProgressUpdates = { stopResumeProgressUpdates() }
        )
    }

    private fun sendProgressEvent(event: String) {
        streamReporter.sendProgressEvent(
            event = event,
            positionProvider = { player?.currentPosition ?: 0L },
            currentAsinProvider = { currentAsin },
            persistCallback = { force -> persistPlaybackProgress(force) }
        )
    }

    private fun startResumeProgressUpdates() {
        resumeProgressHandler.removeCallbacks(resumeProgressRunnable)
        resumeProgressHandler.postDelayed(resumeProgressRunnable, 30_000L)
    }

    private fun stopResumeProgressUpdates() {
        resumeProgressHandler.removeCallbacks(resumeProgressRunnable)
    }

    private fun beginSeekResync() {
        seekResyncPending = true
        callbacks.showBuffering()
        callbacks.onKeepScreenOn(true)
    }

    private fun endSeekResync() {
        if (!seekResyncPending) return
        seekResyncPending = false
        if (player?.playbackState == Player.STATE_READY) callbacks.hideBuffering()
        callbacks.onKeepScreenOn(player?.isPlaying == true)
    }

    private fun onPlaybackCompleted() {
        Log.w(TAG, "onPlaybackCompleted asin=$currentAsin type=$currentMaterialType season=$currentSeasonAsin")
        if (currentMaterialType == "Trailer" || currentSeasonAsin.isEmpty()) {
            Log.w(TAG, "onPlaybackCompleted → finish (no season/trailer)")
            callbacks.onFinish(); return
        }
        scope.launch {
            val next = withContext(Dispatchers.IO) {
                try {
                    apiService.getDetailPage(currentSeasonAsin)
                        .filter { it.kind == ContentKind.EPISODE }
                        .sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }))
                        .let { eps -> eps.getOrNull(eps.indexOfFirst { it.asin == currentAsin } + 1) }
                } catch (e: Exception) { Log.w(TAG, "Next-episode lookup failed", e); null }
            }
            if (next == null) {
                Log.w(TAG, "onPlaybackCompleted → finish (no next episode)")
                callbacks.onFinish(); return@launch
            }
            Log.w(TAG, "onPlaybackCompleted → auto-play next episode ${next.asin}")
            currentAsin = next.asin
            streamReporter.watchSessionId = UUID.randomUUID().toString()
            callbacks.onTitleChanged(next.title)
            loadAndPlay(next.asin, currentMaterialType)
        }
    }

    // -------------------------------------------------------------------------
    // Player.Listener
    // -------------------------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            Log.w(TAG, "Playback state -> ${VideoFormatLabeler.playbackStateName(state)} " +
                "pos=${player?.currentPosition}ms buffered=${player?.totalBufferedDuration}ms " +
                "loading=${player?.isLoading} playWhenReady=${player?.playWhenReady} " +
                "audio=${selectedAudioTrackSummary()}")
            when (state) {
                Player.STATE_BUFFERING -> {
                    Log.w(TAG, "STATE_BUFFERING pos=${player?.currentPosition}ms")
                    callbacks.showBuffering(); callbacks.onKeepScreenOn(true)
                }
                Player.STATE_READY -> {
                    Log.w(TAG, "STATE_READY pos=${player?.currentPosition}ms")
                    if (!seekResyncPending) callbacks.hideBuffering()
                    callbacks.onPlaybackLabelsChanged()
                    if (!streamReporter.streamReportingStarted) {
                        streamReporter.streamReportingStarted = true
                        startStreamReporting()
                    }
                }
                Player.STATE_ENDED -> {
                    persistPlaybackProgress(force = true)
                    stopStreamReporting(); stopResumeProgressUpdates()
                    callbacks.onKeepScreenOn(false)
                    onPlaybackCompleted()
                }
                Player.STATE_IDLE -> callbacks.onKeepScreenOn(false)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.w(TAG, "isPlaying -> $isPlaying state=${VideoFormatLabeler.playbackStateName(player?.playbackState ?: Player.STATE_IDLE)} " +
                "pos=${player?.currentPosition}ms audio=${selectedAudioTrackSummary()}")
            callbacks.onKeepScreenOn(isPlaying || seekResyncPending)
            if (isPlaying) stallWatchdog.start { player }
            else if (player?.playWhenReady == false) stallWatchdog.stop()
            if (!streamReporter.streamReportingStarted) return
            if (isPlaying) { startHeartbeat(); startResumeProgressUpdates() }
            if (!isPlaying && player?.playbackState == Player.STATE_READY) {
                persistPlaybackProgress(force = true)
                sendProgressEvent("PAUSE")
                streamReporter.cancelHeartbeat()
                stopResumeProgressUpdates()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            val reasonName = when (reason) {
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
                Player.DISCONTINUITY_REASON_SEEK            -> "SEEK"
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
                Player.DISCONTINUITY_REASON_SKIP            -> "SKIP"
                Player.DISCONTINUITY_REASON_REMOVE          -> "REMOVE"
                Player.DISCONTINUITY_REASON_INTERNAL        -> "INTERNAL"
                else                                        -> "UNKNOWN($reason)"
            }
            Log.w(TAG, "Discontinuity $reasonName period ${oldPosition.mediaItemIndex}→${newPosition.mediaItemIndex} pos ${oldPosition.positionMs}→${newPosition.positionMs}ms")
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                Log.i(TAG, "Seek discontinuity old=${oldPosition.positionMs} new=${newPosition.positionMs}")
                beginSeekResync(); persistPlaybackProgress(force = true)
            }
        }

        override fun onRenderedFirstFrame() { endSeekResync() }

        override fun onTracksChanged(tracks: Tracks) {
            audioTrackResolver.logCurrentAudioTracks(TAG, tracks)
            callbacks.onTracksChanged(tracks)
            Log.i(TAG, "Selected audio after tracksChanged: ${selectedAudioTrackSummary(tracks)}")
            PlaybackLogger.logVideoTracks(TAG, tracks)
            callbacks.onPlaybackLabelsChanged()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) { callbacks.onPlaybackLabelsChanged() }

        override fun onPlayerError(error: PlaybackException) {
            val httpCause = generateSequence(error.cause) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
            val failingUrl = httpCause?.dataSpec?.uri?.toString()
            if (httpCause != null) Log.e(TAG, "Player error: ${error.errorCodeName} HTTP ${httpCause.responseCode} url=$failingUrl")
            else Log.e(TAG, "Player error: ${error.errorCodeName}", error)
            logPlaybackSnapshot("PLAYER_ERROR", "code=${error.errorCodeName} http=${httpCause?.responseCode} url=$failingUrl")

            val currentPos = player?.currentPosition ?: 0L
            if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED && !audioRestartDone) {
                audioRestartDone = true
                Log.w(TAG, "AUDIOTRACK_DOLBY: restarting with stereo at pos=${currentPos}ms")
                Toast.makeText(context, "Audio init failed — retrying with stereo\u2026", Toast.LENGTH_SHORT).show()
                restartPlayerAtPosition(currentPos, "AUDIOTRACK_DOLBY", stereoOnly = true); return
            }
            val isCorrupt = error.errorCode in listOf(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FAILED
            )
            if (isCorrupt && stallWatchdog.stallRestartCount < 3) {
                stallWatchdog.stallRestartCount++
                Log.w(TAG, "PLAYER_CORRUPT_FRAGMENT: restarting at pos=${currentPos}ms (attempt ${stallWatchdog.stallRestartCount}/3)")
                Toast.makeText(context, "Recovering from media error\u2026", Toast.LENGTH_SHORT).show()
                restartPlayerAtPosition(currentPos, "PLAYER_CORRUPT_FRAGMENT"); return
            }
            persistPlaybackProgress(force = true)
            stopStreamReporting()
            callbacks.onKeepScreenOn(false)
            seekResyncPending = false
            callbacks.onError("Playback error: ${error.errorCodeName}\n${error.message}")
        }
    }

    // -------------------------------------------------------------------------
    // AnalyticsListener
    // -------------------------------------------------------------------------

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            Log.w(TAG, "CODEC_INIT pos=${eventTime.currentPlaybackPositionMs}ms decoder=$decoderName initMs=$initializationDurationMs")
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            Log.w(TAG, "VIDEO_FMT pos=${eventTime.currentPlaybackPositionMs}ms ${format.width}x${format.height} bitrate=${format.bitrate} codecs=${format.codecs} reuse=${decoderReuseEvaluation?.result}")
            currentVideoBitrateKbps = if (format.bitrate > 0) format.bitrate / 1000 else 0
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            currentAudioBitrateKbps  = if (format.bitrate > 0) format.bitrate / 1000 else 0
            currentAudioChannelCount = format.channelCount
            currentAudioSampleMimeType = format.sampleMimeType.orEmpty()
            currentAudioCodecs = format.codecs.orEmpty()
            Log.i(TAG, "AUDIO_FMT pos=${eventTime.currentPlaybackPositionMs}ms " +
                "mime=${currentAudioSampleMimeType.ifBlank { "-" }} codecs=${currentAudioCodecs.ifBlank { "-" }} " +
                "channels=${format.channelCount} bitrate=${format.bitrate} " +
                "sinkMode=$currentAudioSinkMode sinkCaps={$currentAudioSinkCapabilities}")
            callbacks.onPlaybackLabelsChanged()
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            Log.w(TAG, "DROPPED pos=${eventTime.currentPlaybackPositionMs}ms count=$droppedFrames elapsed=${elapsedMs}ms")
        }

        override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
            val pos = eventTime.currentPlaybackPositionMs
            if (pos in 3_350_000..3_460_000) {
                Log.w(TAG, "LOAD pos=${pos}ms type=${mediaLoadData.dataType} seg=${mediaLoadData.mediaStartTimeMs}-${mediaLoadData.mediaEndTimeMs}ms url=${loadEventInfo.uri}")
            }
        }
    }
}

@UnstableApi
private fun DefaultDrmSessionManager.asDrmSessionManagerProvider(): DrmSessionManagerProvider {
    val manager = this
    return DrmSessionManagerProvider { manager }
}
