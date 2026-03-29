package com.scriptgod.fireos.avod.ui

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.widget.Toast
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import android.os.Looper
import androidx.media3.exoplayer.DefaultRenderersFactory
import com.scriptgod.fireos.avod.player.AudioTrackLabeler
import com.scriptgod.fireos.avod.player.AudioTrackResolver
import com.scriptgod.fireos.avod.player.BifThumbnailProvider
import com.scriptgod.fireos.avod.player.MpdTimingCorrector
import com.scriptgod.fireos.avod.player.DeviceCapabilities
import com.scriptgod.fireos.avod.player.PlaybackLogger
import com.scriptgod.fireos.avod.player.StallWatchdog
import com.scriptgod.fireos.avod.player.StreamReporter
import com.scriptgod.fireos.avod.player.VideoFormatLabeler
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.scriptgod.fireos.avod.R
import com.scriptgod.fireos.avod.api.AmazonApiService
import okhttp3.OkHttpClient
import okhttp3.Request
import com.scriptgod.fireos.avod.auth.AmazonAuthService
import com.scriptgod.fireos.avod.data.ProgressRepository
import com.scriptgod.fireos.avod.drm.AmazonLicenseService
import com.scriptgod.fireos.avod.model.AudioTrack
import com.scriptgod.fireos.avod.model.ContentKind
import com.scriptgod.fireos.avod.model.PlaybackInfo
import com.scriptgod.fireos.avod.model.PlaybackQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Full-screen video player using Media3 ExoPlayer with Widevine DRM.
 * Implements stream reporting (UpdateStream) per decisions.md Decision 10.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlayerActivity"
        const val EXTRA_ASIN = "extra_asin"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CONTENT_TYPE = "extra_content_type"
        const val EXTRA_MATERIAL_TYPE = "extra_material_type"
        const val EXTRA_RESUME_MS = "extra_resume_ms"
        const val EXTRA_SERIES_ASIN = "extra_series_asin"
        const val EXTRA_SEASON_ASIN = "extra_season_asin"

        // Widevine UUID
        private val WIDEVINE_UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

        private const val PREF_AUDIO_PASSTHROUGH        = "audio_passthrough"
        private const val PREF_AUDIO_PASSTHROUGH_WARNED = "audio_passthrough_warned"
        private const val PREF_WIDEVINE_L3_WARNED       = "widevine_l3_warned"
    }

    private lateinit var playerView: PlayerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var trackButtons: LinearLayout
    private lateinit var tvPlaybackTitle: TextView
    private lateinit var tvPlaybackStatus: TextView
    private lateinit var tvVideoFormat: TextView
    private lateinit var tvAudioFormat: TextView
    private lateinit var tvPlaybackHint: TextView
    private lateinit var btnAudio: Button
    private lateinit var btnSubtitle: Button
    private var controllerView: View? = null
    private var currentTrackDialog: android.app.AlertDialog? = null

    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Corrected MPD XML to serve in place of the original manifest URL. */
    private var correctedMpdContent: String? = null
    private var currentMediaSource: androidx.media3.exoplayer.source.MediaSource? = null
    private var trackSelector: DefaultTrackSelector? = null
    private lateinit var authService: AmazonAuthService
    private lateinit var apiService: AmazonApiService

    private val scopeJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + scopeJob)
    private var playbackJob: Job? = null
    private var currentAsin: String = ""
    private var currentSeriesAsin: String = ""
    private var currentSeasonAsin: String = ""
    private var currentMaterialType: String = "Feature"
    private var currentQuality: PlaybackQuality = PlaybackQuality.HD
    private var currentAudioBitrateKbps: Int = 0
    private var currentAudioChannelCount: Int = 0
    private var currentVideoBitrateKbps: Int = 0
    private var resumeSeeked: Boolean = false
    private var seekResyncPending: Boolean = false
    private var currentPlaybackInfo: PlaybackInfo? = null
    private var lastMediaSegmentUrl: String = ""
    private var lastVideoSegmentUrl: String = ""
    private var lastAudioSegmentUrl: String = ""
    /** True once an AUDIOTRACK_DOLBY restart (stereo fallback) has been attempted this session. */
    private var audioRestartDone = false
    private lateinit var cardSeekThumbnail: androidx.cardview.widget.CardView
    private lateinit var ivSeekThumbnail: android.widget.ImageView
    private lateinit var tvSeekTime: android.widget.TextView

    // Extracted helper classes — instantiated in onCreate after scope is ready
    private lateinit var audioTrackResolver: AudioTrackResolver
    private lateinit var bifThumbnailProvider: BifThumbnailProvider
    private lateinit var stallWatchdog: StallWatchdog
    private lateinit var streamReporter: StreamReporter
    private val dpadSeekHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideThumbnailRunnable = Runnable { hideThumbnail() }
    /** Accumulated seek-preview position; -1 means not currently seeking. */
    private var seekPreviewPos: Long = -1L
    private val hideTrackButtonsRunnable = Runnable {
        trackButtons.clearFocus()
        trackButtons.visibility = View.GONE
        currentTrackDialog?.dismiss()
        currentTrackDialog = null
    }
    private val syncTrackButtonsRunnable = object : Runnable {
        override fun run() {
            val controllerVisible = playerView.isControllerFullyVisible
            if (controllerVisible) {
                if (trackButtons.visibility != View.VISIBLE) {
                    trackButtons.alpha = 1f
                    trackButtons.visibility = View.VISIBLE
                }
                trackButtons.postDelayed(this, 120L)
            } else {
                hideTrackButtonsRunnable.run()
            }
        }
    }
    private val resumeProgressRunnable = object : Runnable {
        override fun run() {
            persistPlaybackProgress(force = false)
            if (player?.isPlaying == true) {
                playerView.postDelayed(this, 30_000L)
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide action bar if present
        supportActionBar?.hide()

        // Immersive sticky fullscreen — hide status bar and navigation
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
        trackButtons = findViewById(R.id.track_buttons)
        tvPlaybackTitle = findViewById(R.id.tv_playback_title)
        tvPlaybackStatus = findViewById(R.id.tv_playback_status)
        tvVideoFormat = findViewById(R.id.tv_video_format)
        tvAudioFormat = findViewById(R.id.tv_audio_format)
        tvPlaybackHint = findViewById(R.id.tv_playback_hint)
        btnAudio = findViewById(R.id.btn_audio)
        btnSubtitle = findViewById(R.id.btn_subtitle)
        cardSeekThumbnail = findViewById(R.id.card_seek_thumbnail)
        ivSeekThumbnail = findViewById(R.id.iv_seek_thumbnail)
        tvSeekTime = findViewById(R.id.tv_seek_time)
        tvPlaybackTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: "Now Playing"
        tvPlaybackHint.text = "Press MENU for tracks. Press Back to exit."
        tvVideoFormat.visibility = View.GONE
        playerView.keepScreenOn = false
        playerView.setShowSubtitleButton(false)
        playerView.post {
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)?.visibility = View.GONE
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
            controllerView = playerView.findViewById(androidx.media3.ui.R.id.exo_controller)
        }
        applyDeviceOverlayTuning()

        // Fix D-pad seek increment: default is duration/20 (~6 min on a 2h film).
        // 10 s per key press matches standard TV remote behaviour.
        playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(
            androidx.media3.ui.R.id.exo_progress
        )?.also { timeBar ->
            timeBar.setKeyTimeIncrement(10_000L)
            timeBar.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
                override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) =
                    showThumbnailAt(position)
                override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) =
                    showThumbnailAt(position)
                override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) =
                    hideThumbnail()
            })
        }

        // Sync trackButtons visibility with the PlayerView controller so they
        // always appear and disappear together.
        playerView.setControllerVisibilityListener(
            androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) {
                    trackButtons.removeCallbacks(syncTrackButtonsRunnable)
                    syncTrackButtonsRunnable.run()
                } else {
                    trackButtons.removeCallbacks(syncTrackButtonsRunnable)
                    hideTrackButtonsRunnable.run()
                    hideThumbnail()
                }
            }
        )

        btnAudio.setOnClickListener { showTrackSelectionDialog(C.TRACK_TYPE_AUDIO) }
        btnSubtitle.setOnClickListener { showTrackSelectionDialog(C.TRACK_TYPE_TEXT) }
        btnAudio.nextFocusDownId = R.id.btn_subtitle
        btnSubtitle.nextFocusUpId = R.id.btn_audio

        audioTrackResolver = AudioTrackResolver(emptyList())
        bifThumbnailProvider = BifThumbnailProvider(scope)
        stallWatchdog = StallWatchdog(scope) { pos ->
            val p = player ?: return@StallWatchdog
            logPlaybackSnapshot(
                "RENDERER_DECODER_STALLED",
                "stallRestartCount=${stallWatchdog.stallRestartCount} " +
                    "lastVideoSegment=$lastVideoSegmentUrl lastAudioSegment=$lastAudioSegmentUrl"
            )
            if (stallWatchdog.stallRestartCount > 20) {
                showError("Playback stalled and could not be recovered automatically.")
                return@StallWatchdog
            }
            val skipTo = pos + 10_000L
            Log.w(TAG, "RENDERER_DECODER_STALLED: pos=${pos}ms — seeking to ${skipTo}ms (attempt ${stallWatchdog.stallRestartCount}/20)")
            p.seekTo(skipTo)
        }

        val asin = intent.getStringExtra(EXTRA_ASIN)
            ?: run { showError("No ASIN provided"); return }
        currentAsin = asin
        currentSeriesAsin = intent.getStringExtra(EXTRA_SERIES_ASIN) ?: ""
        currentSeasonAsin = intent.getStringExtra(EXTRA_SEASON_ASIN) ?: ""

        val tokenFile = LoginActivity.findTokenFile(this)
            ?: run { finish(); return }
        authService = AmazonAuthService(tokenFile)
        authService.onMediaRequestObserved = { url ->
            lastMediaSegmentUrl = url
            val lowerUrl = url.lowercase()
            when {
                lowerUrl.contains("_video_") -> lastVideoSegmentUrl = url
                lowerUrl.contains("_audio_") -> lastAudioSegmentUrl = url
            }
        }
        apiService = AmazonApiService(authService)
        ProgressRepository.init(applicationContext)
        streamReporter = StreamReporter(apiService, ProgressRepository, scope)

        // Default to "Feature"; caller may pass "Trailer" via EXTRA_MATERIAL_TYPE.
        // GTI-format ASINs (amzn1.dv.gti.*) reject "Episode" with PRSInvalidRequest.
        // "Feature" works for both movies and episodes with GTI ASINs.
        val materialType = intent.getStringExtra(EXTRA_MATERIAL_TYPE) ?: "Feature"
        loadAndPlay(asin, materialType)
    }

    /** Returns true if the connected display reports HDR support via HDMI EDID. */
    @Suppress("DEPRECATION")
    private fun displaySupportsHdr(): Boolean {
        val caps = windowManager.defaultDisplay.hdrCapabilities ?: return false
        return caps.supportedHdrTypes.isNotEmpty()
    }

    /**
     * Resolves the effective PlaybackQuality for this playback session.
     * UHD_HDR requires both an H265 decoder and an HDR-capable display; falls back to HD.
     * HD_H265 (SDR) only needs an H265 decoder; falls back to HD on devices without HEVC.
     */
    private fun resolveQuality(): PlaybackQuality {
        // L3 check runs before user preference: the license server rejects HD+L3 regardless
        // of what the user selected.  Mirror the official Amazon APK behaviour: detect the
        // CDM security level, fall back to SD when L1 is absent.
        if (DeviceCapabilities.widevineSecurityLevel() != "L1") {
            Log.w(TAG, "Widevine L3 detected — forcing SD quality (HD requires L1 + HDCP)")
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_WIDEVINE_L3_WARNED, false)) {
                prefs.edit().putBoolean(PREF_WIDEVINE_L3_WARNED, true).apply()
                Toast.makeText(
                    this,
                    "Widevine L3 device — SD quality only (HD requires hardware DRM)",
                    Toast.LENGTH_LONG
                ).show()
            }
            return PlaybackQuality.SD
        }

        val pref = getSharedPreferences("settings", MODE_PRIVATE)
            .getString(PlaybackQuality.PREF_KEY, null)
        val requested = PlaybackQuality.fromPrefValue(pref)
        if (requested == PlaybackQuality.UHD_HDR) {
            if (!DeviceCapabilities.deviceSupportsH265()) {
                Toast.makeText(this, "H265 not supported — using HD H264", Toast.LENGTH_LONG).show()
                return PlaybackQuality.HD
            }
            if (!displaySupportsHdr()) {
                Toast.makeText(this, "Display does not support HDR — using HD H264", Toast.LENGTH_LONG).show()
                return PlaybackQuality.HD
            }
        }
        if (requested == PlaybackQuality.HD_H265 && !DeviceCapabilities.deviceSupportsH265()) {
            Toast.makeText(this, "H265 not supported — using HD H264", Toast.LENGTH_LONG).show()
            return PlaybackQuality.HD
        }
        return requested
    }

    private fun loadAndPlay(asin: String, materialType: String = "Feature") {
        Log.w(TAG, "loadAndPlay asin=$asin type=$materialType season=$currentSeasonAsin")
        progressBar.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        tvVideoFormat.text = ""   // clear stale label until new player reports its format
        playerView.useController = true
        stallWatchdog.stallRestartCount = 0
        audioRestartDone = false
        currentAudioBitrateKbps = 0
        currentAudioChannelCount = 0
        currentVideoBitrateKbps = 0

        val quality = resolveQuality()
        currentMaterialType = materialType
        currentQuality = quality
        updatePlaybackStatus()
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
            val mergedAudioTracks = (info.audioTracks + detailAudioTracks)
                .distinctBy { "${it.displayName}|${it.languageCode}|${it.type}|${it.index}" }
            audioTrackResolver.updateAvailableTracks(mergedAudioTracks)
            PlaybackLogger.logAvailableAudioTracks(TAG, "Merged audio metadata", mergedAudioTracks)
            // Correct MPD timing: Amazon's SegmentList uses fixed duration that drifts ~41s
            // over 2h. Replace with SegmentBase+sidx for accurate segment timing.
            correctedMpdContent = withContext(Dispatchers.IO) {
                try {
                    val authClient = authService.buildAuthenticatedClient()
                    MpdTimingCorrector.correctMpd(info.manifestUrl, authClient)
                } catch (e: Exception) {
                    Log.e(TAG, "MPD correction failed, will use original: ${e.message}")
                    null
                }
            }
            currentPlaybackInfo = info
            if (info.bifUrl.isNotEmpty()) bifThumbnailProvider.loadIndex(info.bifUrl)
            setupPlayer(info)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch playback info: ${e.message}", e)
            showError("Playback error: ${e.message}")
        }
        }
    }

    /**
     * Sets up ExoPlayer with DASH + Widevine DRM.
     * Mirrors decisions.md Decision 4 and Phase 4 instructions.
     *
     * @param startPositionOverride When non-null, seek to this position instead of ProgressRepository.
     *   Used by RENDERER_DECODER_STALLED / PLAYER_CORRUPT_FRAGMENT restarts.
     * @param stereoOnly When true, cap audio to 2 channels (AUDIOTRACK_DOLBY restart).
     */
    private fun setupPlayer(
        info: PlaybackInfo,
        startPositionOverride: Long? = null,
        stereoOnly: Boolean = false
    ) {
        if (isDestroyed || isFinishing) return
        player?.removeListener(playerListener)
        player?.removeAnalyticsListener(analyticsListener)
        player?.release()
        player = null
        Log.i(
            TAG,
            buildString {
                append("Playback info: ")
                append("manifest=").append(info.manifestUrl)
                append(" license=").append(info.licenseUrl)
                append(" resumeSeeked=").append(resumeSeeked)
                append(" selectedQuality=").append(currentQuality)
                append(" startPositionOverride=").append(startPositionOverride)
                append(" stereoOnly=").append(stereoOnly)
                append(" stallRestartCount=").append(stallWatchdog.stallRestartCount)
                append(" passthroughRequested=")
                    .append(getSharedPreferences("settings", MODE_PRIVATE).getBoolean(PREF_AUDIO_PASSTHROUGH, false))
                append(" lastMediaSegment=").append(lastMediaSegmentUrl)
                append(" lastVideoSegment=").append(lastVideoSegmentUrl)
                append(" lastAudioSegment=").append(lastAudioSegmentUrl)
            }
        )
        val drmDiag = java.io.File(getExternalFilesDir(null), "drm_diag.txt").also { it.delete() }
        val licenseCallback = AmazonLicenseService(authService, info.licenseUrl, applicationContext, drmDiag)

        val drmSessionManager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(false) // single session reused across periods — matches Amazon default (mediadrm_multiSessionEnabled_2=false)
            .build(licenseCallback)

        val passthroughRequested = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean(PREF_AUDIO_PASSTHROUGH, false)
        val passthroughEnabled = passthroughRequested

        // Fire TV has no OMX.dolby.eac3.decoder.secure (audio DRM uses OMX.google.raw.decoder,
        // which passes EAC3 bytes straight to AudioTrack → Dolby MS12 HAL → stall after ~1.5s).
        // Amazon's audio is actually CLEAR despite the CENC declaration in the MPD
        // (confirmed by supports-secure-with-non-secure-codec=true in media_codecs.xml).
        // Force OMX.dolby.eac3.decoder (non-secure) for EAC3 so the hardware decodes to PCM
        // rather than passing raw EAC3 to the Dolby HAL passthrough pipeline.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            init {
                setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
                setEnableDecoderFallback(true)
            }
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                // AudioCapabilities controls which encodings the sink will pass through to the
                // hardware decoder (EAC3, AC3, etc.). When passthrough is disabled, use the
                // default capabilities (stereo PCM only) so the sink always decodes to PCM.
                val caps = if (passthroughEnabled) AudioCapabilities.getCapabilities(context)
                           else AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
                return DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(caps)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }

        val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            authService.buildAuthenticatedClient()
        )

        // If we have a corrected MPD, wrap the data source to serve it instead of
        // fetching the original manifest URL. This keeps the manifest URI as the real
        // CDN URL so BaseURL resolution works, while serving corrected content.
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
                        // Intercept the manifest URL and serve corrected content
                        if (dataSpec.uri.path == manifestUri.path) {
                            isManifest = true
                            byteStream = java.io.ByteArrayInputStream(correctedBytes)
                            return correctedBytes.size.toLong()
                        }
                        isManifest = false
                        httpSource = httpDataSourceFactory.createDataSource()
                        return httpSource!!.open(dataSpec)
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (isManifest) {
                            return byteStream?.read(buffer, offset, length) ?: -1
                        }
                        return httpSource?.read(buffer, offset, length) ?: -1
                    }

                    override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {}
                    override fun getUri(): android.net.Uri? = if (isManifest) manifestUri else httpSource?.uri
                    override fun close() {
                        byteStream?.close()
                        byteStream = null
                        httpSource?.close()
                        httpSource = null
                    }

                    override fun getResponseHeaders(): Map<String, List<String>> =
                        httpSource?.responseHeaders ?: emptyMap()
                }
            }
        } else {
            httpDataSourceFactory
        }

        val mediaItem = MediaItem.Builder()
            .setUri(info.manifestUrl)
            .build()

        val dashSource = DashMediaSource.Factory(dataSourceFactory)
            .setDrmSessionManagerProvider(drmSessionManager.asDrmSessionManagerProvider())
            .createMediaSource(mediaItem)

        // External subtitle tracks via SingleSampleMediaSource (DashMediaSource ignores SubtitleConfiguration)
        Log.w(TAG, "Subtitle tracks from API: ${info.subtitleTracks.size}")
        val subtitleSources = info.subtitleTracks.map { sub ->
            val subConfig = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                .setMimeType(MimeTypes.APPLICATION_TTML)
                .setLanguage(sub.languageCode)
                .setLabel(AudioTrackLabeler.buildSubtitleLabel(sub.languageCode, sub.type))
                .setSelectionFlags(if (sub.type == "forced") C.SELECTION_FLAG_FORCED else 0)
                .build()
            SingleSampleMediaSource.Factory(dataSourceFactory)
                .createMediaSource(subConfig, C.TIME_UNSET)
        }

        val mediaSource = if (subtitleSources.isNotEmpty()) {
            MergingMediaSource(dashSource, *subtitleSources.toTypedArray())
        } else {
            dashSource
        }
        currentMediaSource = mediaSource

        val selector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setPreferredAudioRoleFlags(C.ROLE_FLAG_MAIN)
                // AUDIOTRACK_DOLBY restart: cap to stereo so Dolby passthrough path is skipped
                .setMaxAudioChannelCount(if (stereoOnly) 2 else Int.MAX_VALUE)
                .build()
        }
        trackSelector = selector

        // Trailers always start from beginning. For real content resume from ProgressRepository.
        // Restart recovery (RENDERER_DECODER_STALLED / PLAYER_CORRUPT_FRAGMENT) supplies an
        // explicit position so the player resumes exactly where it stalled, not from the bookmark.
        val intentResumeMs = intent.getLongExtra(EXTRA_RESUME_MS, 0L).coerceAtLeast(0L)
        val serverResumeMs = intentResumeMs.takeIf { it > 0L }
            ?: ProgressRepository.get(currentAsin)?.positionMs?.coerceAtLeast(0L)
            ?: 0L
        val resumeMs = when {
            currentMaterialType == "Trailer" -> 0L
            startPositionOverride != null -> startPositionOverride
            else -> serverResumeMs
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(selector)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.addListener(playerListener)
                exoPlayer.addAnalyticsListener(analyticsListener)
                exoPlayer.prepare()
                if (resumeMs > 10_000) {
                    exoPlayer.seekTo(resumeMs)
                    resumeSeeked = true
                }
                exoPlayer.playWhenReady = true
                logPlaybackSnapshot("PLAYER_SETUP")
                if (passthroughEnabled) {
                    val settingsPrefs = getSharedPreferences("settings", MODE_PRIVATE)
                    if (!settingsPrefs.getBoolean(PREF_AUDIO_PASSTHROUGH_WARNED, false)) {
                        settingsPrefs.edit().putBoolean(PREF_AUDIO_PASSTHROUGH_WARNED, true).apply()
                        Toast.makeText(
                            this@PlayerActivity,
                            "Audio passthrough on — volume is controlled by your AV receiver",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

        progressBar.visibility = View.GONE
        updateTrackButtonLabels()
    }

    /**
     * Reads the format currently being decoded by the video renderer.
     * player.videoFormat reflects the live ABR tier, not just the initially selected track.
     * Called from onVideoSizeChanged (ABR switch) and onTracksChanged (track swap / fallback).
     */
    private fun updateVideoFormatLabel() {
        val fmt = player?.videoFormat ?: run { tvVideoFormat.text = ""; tvAudioFormat.visibility = View.GONE; return }
        // Primary: ExoPlayer colorInfo (populated when MPD has colorimetry attributes).
        // Fallback: codec string profile — Amazon uses hvc1.2.* / hev1.2.* (HEVC Main 10)
        // exclusively for HDR10 content; Dolby Vision containers start with dvhe/dvav.
        val codecs = fmt.codecs ?: ""
        Log.i(TAG, "Video format: ${fmt.height}p mime=${fmt.sampleMimeType} codecs=$codecs colorTransfer=${fmt.colorInfo?.colorTransfer}")
        val labels = VideoFormatLabeler.computeFormatLabels(fmt, currentVideoBitrateKbps, currentAudioBitrateKbps, currentAudioChannelCount)
        tvVideoFormat.text = labels.videoLabel
        tvVideoFormat.visibility = if (labels.videoLabel.isBlank()) View.GONE else View.VISIBLE
        tvAudioFormat.text = labels.audioLabel
        tvAudioFormat.visibility = if (labels.audioLabel.isBlank()) View.GONE else View.VISIBLE
    }

    private fun updatePlaybackStatus() {
        val materialLabel = if (currentMaterialType == "Trailer") "Trailer" else "Playback"
        val qualityLabel = when (currentQuality) {
            PlaybackQuality.UHD_HDR -> "4K HDR preset"
            PlaybackQuality.HD_H265 -> "HD H265 preset"
            PlaybackQuality.HD      -> "HD H264 preset"
            PlaybackQuality.SD      -> "SD (Widevine L3)"
            else                    -> "HD H264 preset"
        }
        tvPlaybackStatus.text = "$materialLabel  ·  $qualityLabel"
    }

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
            Log.w(TAG, "VIDEO_FMT pos=${eventTime.currentPlaybackPositionMs}ms " +
                "${format.width}x${format.height} bitrate=${format.bitrate} " +
                "codecs=${format.codecs} reuse=${decoderReuseEvaluation?.result}")
            currentVideoBitrateKbps = if (format.bitrate > 0) format.bitrate / 1000 else 0
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            currentAudioBitrateKbps = if (format.bitrate > 0) format.bitrate / 1000 else 0
            currentAudioChannelCount = format.channelCount
            updateVideoFormatLabel()
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            Log.w(TAG, "DROPPED pos=${eventTime.currentPlaybackPositionMs}ms count=$droppedFrames elapsed=${elapsedMs}ms")
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            val pos = eventTime.currentPlaybackPositionMs
            if (pos in 3_350_000..3_460_000) {
                Log.w(TAG, "LOAD pos=${pos}ms type=${mediaLoadData.dataType} " +
                    "seg=${mediaLoadData.mediaStartTimeMs}-${mediaLoadData.mediaEndTimeMs}ms " +
                    "url=${loadEventInfo.uri}")
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            Log.w(
                TAG,
                "Playback state changed -> ${playbackStateName(state)} " +
                    "pos=${player?.currentPosition}ms buffered=${player?.totalBufferedDuration}ms " +
                    "loading=${player?.isLoading} playWhenReady=${player?.playWhenReady} " +
                    "audio=${selectedAudioTrackSummary()}"
            )
            when (state) {
                Player.STATE_BUFFERING -> {
                    Log.w(TAG, "STATE_BUFFERING pos=${player?.currentPosition}ms buffered=${player?.totalBufferedDuration}ms")
                    progressBar.visibility = View.VISIBLE
                    updatePlaybackWakeState(true)
                }
                Player.STATE_READY -> {
                    Log.w(TAG, "STATE_READY pos=${player?.currentPosition}ms")
                    if (!seekResyncPending) {
                        progressBar.visibility = View.GONE
                    }
                    tvError.visibility = View.GONE
                    playerView.useController = true
                    updateVideoFormatLabel()
                    if (!streamReporter.streamReportingStarted) {
                        streamReporter.streamReportingStarted = true
                        startStreamReporting()
                    }
                }
                Player.STATE_ENDED -> {
                    persistPlaybackProgress(force = true)
                    stopStreamReporting()
                    stopResumeProgressUpdates()
                    updatePlaybackWakeState(false)
                    onPlaybackCompleted()
                }
                Player.STATE_IDLE -> updatePlaybackWakeState(false)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.w(
                TAG,
                "isPlaying changed -> $isPlaying state=${playbackStateName(player?.playbackState ?: Player.STATE_IDLE)} " +
                    "pos=${player?.currentPosition}ms buffered=${player?.totalBufferedDuration}ms " +
                    "audio=${selectedAudioTrackSummary()}"
            )
            updatePlaybackWakeState(isPlaying || seekResyncPending)
            // Start watchdog when playing; stop only on intentional pause (playWhenReady=false).
            // Do NOT stop on isPlaying=false alone — that fires on every BUFFERING state change,
            // which would cancel the watchdog coroutine before its delay(2000) ever completes.
            if (isPlaying) stallWatchdog.start { player }
            else if (player?.playWhenReady == false) stallWatchdog.stop()
            if (!streamReporter.streamReportingStarted) return
            if (isPlaying) {
                startHeartbeat()
                startResumeProgressUpdates()
            }
            if (!isPlaying && player?.playbackState == Player.STATE_READY) {
                // Paused — PlayerView keeps its controller visible automatically
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
                Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
                Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
                Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
                Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
                else -> "UNKNOWN($reason)"
            }
            Log.w(TAG, "Discontinuity $reasonName period ${oldPosition.mediaItemIndex}→${newPosition.mediaItemIndex} pos ${oldPosition.positionMs}→${newPosition.positionMs}ms")
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                Log.i(TAG, "Seek discontinuity old=${oldPosition.positionMs} new=${newPosition.positionMs}")
                beginSeekResync()
                persistPlaybackProgress(force = true)
            }
        }

        override fun onRenderedFirstFrame() {
            endSeekResync()
        }

        override fun onTracksChanged(tracks: Tracks) {
            audioTrackResolver.logCurrentAudioTracks(TAG, tracks)
            updateTrackButtonLabels(tracks)
            Log.i(TAG, "Selected audio after tracksChanged: ${selectedAudioTrackSummary(tracks)}")
            PlaybackLogger.logVideoTracks(TAG, tracks)
            updateVideoFormatLabel()
            // Re-hide native track buttons (they re-show on track change)
            playerView.post {
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)?.visibility = View.GONE
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // Fires on every ABR bitrate switch — reflects the renderer's actual live format
            updateVideoFormatLabel()
        }

        override fun onPlayerError(error: PlaybackException) {
            val httpCause = generateSequence(error.cause) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()
            val failingUrl = httpCause?.dataSpec?.uri?.toString()
            if (httpCause != null) {
                Log.e(TAG, "Player error: ${error.errorCodeName} HTTP ${httpCause.responseCode} url=$failingUrl")
            } else {
                Log.e(TAG, "Player error: ${error.errorCodeName}", error)
            }
            logPlaybackSnapshot("PLAYER_ERROR", "code=${error.errorCodeName} http=${httpCause?.responseCode} url=$failingUrl")

            val currentPos = player?.currentPosition ?: 0L

            // AUDIOTRACK_DOLBY_OR_TUNNELED_INITIALIZE_FAILED_RESTART_PLAYER
            // AudioTrack init failed (Dolby passthrough or tunneled mode couldn't open).
            // Restart once with stereo-only audio (mirrors Amazon's AudioRenderer logic).
            if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED && !audioRestartDone) {
                audioRestartDone = true
                Log.w(TAG, "AUDIOTRACK_DOLBY: restarting with stereo at pos=${currentPos}ms")
                Toast.makeText(this@PlayerActivity, "Audio init failed — retrying with stereo\u2026", Toast.LENGTH_SHORT).show()
                restartPlayerAtPosition(currentPos, "AUDIOTRACK_DOLBY", stereoOnly = true)
                return
            }

            // PLAYER_CORRUPT_FRAGMENT
            // Parse/decode error — media data is corrupt or unsupported at this position.
            // Restart at the same position, up to 3 times total (shared counter with stall watchdog).
            val isCorruptFragment = error.errorCode in listOf(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FAILED
            )
            if (isCorruptFragment && stallWatchdog.stallRestartCount < 3) {
                stallWatchdog.stallRestartCount++
                Log.w(TAG, "PLAYER_CORRUPT_FRAGMENT: restarting at pos=${currentPos}ms (attempt ${stallWatchdog.stallRestartCount}/3)")
                Toast.makeText(this@PlayerActivity, "Recovering from media error\u2026", Toast.LENGTH_SHORT).show()
                restartPlayerAtPosition(currentPos, "PLAYER_CORRUPT_FRAGMENT")
                return
            }

            persistPlaybackProgress(force = true)
            stopStreamReporting()
            updatePlaybackWakeState(false)
            seekResyncPending = false

            showError("Playback error: ${error.errorCodeName}\n${error.message}")
        }
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

    /**
     * Full session restart at [posMs] — mirrors Amazon's RestartPlaybackSessionTask.
     * Re-fetches GetPlaybackResources to get a fresh manifest URL (possibly a different CDN node)
     * and fresh license URL, then rebuilds ExoPlayer and seeks to the stalled position.
     *
     * Re-fetching PRS is critical: the stale manifest URL may route to the same CDN node
     * that is serving the broken segment. Amazon's restart always goes through the full
     * session lifecycle, which includes a new PRS call.
     *
     * @param stereoOnly True for AUDIOTRACK_DOLBY restart: caps audio to 2 channels.
     */
    private fun restartPlayerAtPosition(posMs: Long, reason: String, stereoOnly: Boolean = false) {
        Log.w(TAG, "PLAYER_RESTART: reason=$reason posMs=$posMs stereoOnly=$stereoOnly attempt=${stallWatchdog.stallRestartCount}")
        stallWatchdog.stop()
        stopStreamReporting()
        player?.removeListener(playerListener)
        player?.removeAnalyticsListener(analyticsListener)
        player?.release()
        player = null
        streamReporter.streamReportingStarted = false
        resumeSeeked = false
        progressBar.visibility = View.VISIBLE

        // Re-fetch PRS to get a fresh manifest + license URL (Amazon's restart flow)
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
                showError("Restart failed: ${e.message}")
            }
        }
    }

    private fun startResumeProgressUpdates() {
        playerView.removeCallbacks(resumeProgressRunnable)
        playerView.postDelayed(resumeProgressRunnable, 30_000L)
    }

    private fun stopResumeProgressUpdates() {
        playerView.removeCallbacks(resumeProgressRunnable)
    }


    private fun onPlaybackCompleted() {
        Log.w(TAG, "onPlaybackCompleted asin=$currentAsin type=$currentMaterialType season=$currentSeasonAsin")
        // Trailers and non-episode content (no season context): just close.
        if (currentMaterialType == "Trailer" || currentSeasonAsin.isEmpty()) {
            Log.w(TAG, "onPlaybackCompleted → finish (no season/trailer)")
            finish()
            return
        }
        // Episode: find and auto-play the next episode in the same season.
        scope.launch {
            val next = withContext(Dispatchers.IO) {
                try {
                    apiService.getDetailPage(currentSeasonAsin)
                        .filter { it.kind == ContentKind.EPISODE }
                        .sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }))
                        .let { eps ->
                            val idx = eps.indexOfFirst { it.asin == currentAsin }
                            if (idx >= 0) eps.getOrNull(idx + 1) else null
                        }
                } catch (e: Exception) {
                    Log.w(TAG, "Next-episode lookup failed", e)
                    null
                }
            }
            if (next == null) {
                Log.w(TAG, "onPlaybackCompleted → finish (no next episode)")
                finish()   // end of season or lookup error → return to season view
                return@launch
            }
            Log.w(TAG, "onPlaybackCompleted → auto-play next episode ${next.asin}")
            // Update per-episode state before re-using loadAndPlay.
            currentAsin = next.asin
            streamReporter.watchSessionId = UUID.randomUUID().toString()
            // Clear the stale intent resume so loadAndPlay falls back to
            // ProgressRepository (respects prior partial progress on the next ep).
            intent.putExtra(EXTRA_RESUME_MS, 0L)
            tvPlaybackTitle.text = next.title
            loadAndPlay(next.asin, currentMaterialType)
        }
    }

    private fun sendProgressEvent(event: String) {
        streamReporter.sendProgressEvent(
            event = event,
            positionProvider = { player?.currentPosition ?: 0L },
            currentAsinProvider = { currentAsin },
            persistCallback = { force -> persistPlaybackProgress(force) }
        )
    }

    private fun stopStreamReporting() {
        streamReporter.stopStreamReporting(
            positionProvider = { player?.currentPosition ?: 0L },
            currentAsinProvider = { currentAsin },
            onStopResumeProgressUpdates = { stopResumeProgressUpdates() }
        )
    }

    private fun persistPlaybackProgress(force: Boolean) {
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

    private fun showThumbnailAt(posMs: Long) {
        // Always show the time label so the user gets seek feedback even without a BIF track.
        val totalSec = posMs / 1000L
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        tvSeekTime.text = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        cardSeekThumbnail.visibility = View.VISIBLE

        val info = currentPlaybackInfo ?: return
        if (!info.hasThumbnails) return
        if (!bifThumbnailProvider.hasBifEntries()) return

        bifThumbnailProvider.getThumbnailAt(posMs, info.bifUrl) { bmp ->
            if (bmp != null) ivSeekThumbnail.setImageBitmap(bmp)
        }
    }

    private fun hideThumbnail() {
        seekPreviewPos = -1L
        cardSeekThumbnail.visibility = View.GONE
        ivSeekThumbnail.setImageBitmap(null)
    }

    private fun updatePlaybackWakeState(isPlaying: Boolean) {
        playerView.keepScreenOn = isPlaying
        if (isPlaying) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun beginSeekResync() {
        seekResyncPending = true
        progressBar.visibility = View.VISIBLE
        updatePlaybackWakeState(true)
    }

    private fun endSeekResync() {
        if (!seekResyncPending) return
        seekResyncPending = false
        if (player?.playbackState == Player.STATE_READY) {
            progressBar.visibility = View.GONE
        }
        updatePlaybackWakeState(player?.isPlaying == true)
    }

    private fun playbackStateName(state: Int): String = VideoFormatLabeler.playbackStateName(state)

    private fun formatSummary(format: Format?): String = VideoFormatLabeler.formatSummary(format)

    private fun selectedAudioTrackSummary(tracks: Tracks? = player?.currentTracks): String {
        val currentTracks = tracks ?: return "none"
        val selectedOption = audioTrackResolver.buildTrackOptions(C.TRACK_TYPE_AUDIO, currentTracks).firstOrNull { it.isSelected }
        if (selectedOption == null) return "none"

        val selectedFormat = currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .firstOrNull { group -> (0 until group.length).any { group.isTrackSelected(it) } }
            ?.let { group ->
                (0 until group.length)
                    .firstOrNull { group.isTrackSelected(it) }
                    ?.let { group.getTrackFormat(it) }
            }

        return buildString {
            append("menu=${selectedOption.label}")
            append(" ")
            append(formatSummary(selectedFormat))
        }
    }

    private fun logPlaybackSnapshot(reason: String, extra: String = "") {
        val p = player ?: return
        val passthroughEnabled = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean(PREF_AUDIO_PASSTHROUGH, false)
        val suffix = if (extra.isBlank()) "" else " $extra"
        Log.i(
            TAG,
            "$reason pos=${p.currentPosition}ms buffered=${p.totalBufferedDuration}ms " +
                "state=${playbackStateName(p.playbackState)} isPlaying=${p.isPlaying} " +
                "playWhenReady=${p.playWhenReady} loading=${p.isLoading} " +
                "audio=${selectedAudioTrackSummary()} passthrough=$passthroughEnabled$suffix"
        )
    }

    private fun buildTrackOptions(trackType: Int, tracks: Tracks): List<AudioTrackResolver.TrackOption> =
        audioTrackResolver.buildTrackOptions(trackType, tracks)

    private fun updateTrackButtonLabels(tracks: Tracks? = player?.currentTracks) {
        val currentTracks = tracks ?: run {
            btnAudio.text = "Audio"
            btnSubtitle.text = "Subtitles"
            return
        }
        val selectedAudio = buildTrackOptions(C.TRACK_TYPE_AUDIO, currentTracks)
            .firstOrNull { it.isSelected }
        val selectedSubtitle = buildTrackOptions(C.TRACK_TYPE_TEXT, currentTracks)
            .firstOrNull { it.isSelected }

        btnAudio.text = selectedAudio?.let { "Audio: ${it.label}" } ?: "Audio"
        btnSubtitle.text = selectedSubtitle?.let { "Subtitles: ${it.label}" } ?: "Subtitles: Off"
    }

    private fun showTrackSelectionDialog(trackType: Int) {
        val p = player ?: return
        val tracks = p.currentTracks
        val typeName = if (trackType == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles"
        val options = buildTrackOptions(trackType, tracks)
        if (options.isEmpty() && trackType != C.TRACK_TYPE_TEXT) {
            android.widget.Toast.makeText(this, "No $typeName tracks available", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val labels = mutableListOf<String>()
        val trackIndices = mutableListOf<Pair<Int, Int>>() // groupIndex, trackIndex
        var selectedIndex = -1

        // "Off" option for subtitles
        if (trackType == C.TRACK_TYPE_TEXT) {
            labels.add("Off")
            trackIndices.add(Pair(-1, -1))
            val anySelected = options.any { it.isSelected }
            if (!anySelected) selectedIndex = 0
        }

        options.forEach { option ->
            labels.add(option.label)
            trackIndices.add(Pair(option.groupIndex, option.trackIndex))
            if (option.isSelected && selectedIndex == -1) selectedIndex = labels.size - 1
        }

        currentTrackDialog?.dismiss()
        currentTrackDialog = AlertDialog.Builder(this)
            .setTitle(typeName)
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
                val (gi, ti) = trackIndices[which]
                if (gi == -1) {
                    // "Off" — disable all text tracks
                    val builder = p.trackSelectionParameters.buildUpon()
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    p.trackSelectionParameters = builder.build()
                } else {
                    val group = options.first { it.groupIndex == gi && it.trackIndex == ti }.group
                    val builder = p.trackSelectionParameters.buildUpon()
                    builder.setTrackTypeDisabled(trackType, false)
                    builder.setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, listOf(ti))
                    )
                    p.trackSelectionParameters = builder.build()
                }
                dialog.dismiss()
                currentTrackDialog = null
            }
            .setOnDismissListener { currentTrackDialog = null }
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val seekBar = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) &&
            playerView.isControllerFullyVisible &&
            currentFocus == seekBar) {
            // Accumulate seek position ourselves — player.currentPosition is updated
            // asynchronously by ExoPlayer and won't reflect the seek target in time for
            // rapid successive key presses.
            val dur = player?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE
            if (seekPreviewPos < 0) seekPreviewPos = player?.currentPosition ?: 0L
            seekPreviewPos = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                minOf(seekPreviewPos + 10_000L, dur) else maxOf(seekPreviewPos - 10_000L, 0L)
            val previewPos = seekPreviewPos
            val result = super.dispatchKeyEvent(event)
            dpadSeekHandler.removeCallbacks(hideThumbnailRunnable)
            dpadSeekHandler.post { showThumbnailAt(previewPos) }
            dpadSeekHandler.postDelayed(hideThumbnailRunnable, 1500L)
            return result
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // Toggle both the player controller and trackButtons (synced via listener)
            if (playerView.isControllerFullyVisible) {
                trackButtons.clearFocus()
                playerView.hideController()
            } else {
                playerView.showController()
                btnAudio.postDelayed({
                    if (playerView.isControllerFullyVisible && trackButtons.visibility == View.VISIBLE) {
                        btnAudio.requestFocus()
                    }
                }, 120L)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun applyDeviceOverlayTuning() {
        val isAmazonDevice = android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        if (!isAmazonDevice) return

        updateMargins(trackButtons, topDp = 20, endDp = 28)
        updateMargins(tvError, startDp = 36, bottomDp = 44)
        tvError.maxLines = 4
        updateWidth(tvPlaybackTitle, 224)
        updateWidth(tvPlaybackHint, 224)
        updateHeight(btnAudio, 38)
        updateHeight(btnSubtitle, 38)
    }

    private fun updateMargins(view: View, startDp: Int? = null, topDp: Int? = null, endDp: Int? = null, bottomDp: Int? = null) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val density = resources.displayMetrics.density
        startDp?.let { params.marginStart = (it * density).toInt() }
        topDp?.let { params.topMargin = (it * density).toInt() }
        endDp?.let { params.marginEnd = (it * density).toInt() }
        bottomDp?.let { params.bottomMargin = (it * density).toInt() }
        view.layoutParams = params
    }

    private fun updateWidth(view: View, widthDp: Int) {
        val params = view.layoutParams ?: return
        params.width = (widthDp * resources.displayMetrics.density).toInt()
        view.layoutParams = params
    }

    private fun updateHeight(view: View, heightDp: Int) {
        val params = view.layoutParams ?: return
        params.height = (heightDp * resources.displayMetrics.density).toInt()
        view.layoutParams = params
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        playerView.hideController()
        playerView.useController = false
        trackButtons.visibility = View.GONE
        tvError.text = "Playback unavailable\n${VideoFormatLabeler.friendlyError(message)}"
        tvError.visibility = View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        persistPlaybackProgress(force = true)
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        // Re-apply immersive mode
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
        player?.play()
    }

    override fun onStop() {
        super.onStop()
        persistPlaybackProgress(force = true)
        if (streamReporter.streamReportingStarted) {
            stopStreamReporting()
            streamReporter.streamReportingStarted = false
        }
        updatePlaybackWakeState(false)
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        scopeJob.cancel()
        trackButtons.removeCallbacks(syncTrackButtonsRunnable)
        trackButtons.removeCallbacks(hideTrackButtonsRunnable)
        dpadSeekHandler.removeCallbacksAndMessages(null)
        stopResumeProgressUpdates()
        updatePlaybackWakeState(false)
        player?.release()
        player = null
    }
}

// Extension to convert DrmSessionManager to DrmSessionManagerProvider
@UnstableApi
private fun DefaultDrmSessionManager.asDrmSessionManagerProvider(): DrmSessionManagerProvider {
    val manager = this
    return DrmSessionManagerProvider { manager }
}
