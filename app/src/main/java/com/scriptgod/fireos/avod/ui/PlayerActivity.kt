package com.scriptgod.fireos.avod.ui

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.scriptgod.fireos.avod.R
import com.scriptgod.fireos.avod.api.AmazonApiService
import com.scriptgod.fireos.avod.auth.AmazonAuthService
import com.scriptgod.fireos.avod.data.ProgressRepository
import com.scriptgod.fireos.avod.model.PlaybackQuality
import com.scriptgod.fireos.avod.player.BifThumbnailProvider
import com.scriptgod.fireos.avod.player.PlaybackOrchestrator
import com.scriptgod.fireos.avod.player.SeekPreviewController
import com.scriptgod.fireos.avod.player.TrackMenuController
import com.scriptgod.fireos.avod.player.VideoFormatLabeler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlayerActivity"
        const val EXTRA_ASIN          = "extra_asin"
        const val EXTRA_TITLE         = "extra_title"
        const val EXTRA_CONTENT_TYPE  = "extra_content_type"
        const val EXTRA_MATERIAL_TYPE = "extra_material_type"
        const val EXTRA_RESUME_MS     = "extra_resume_ms"
        const val EXTRA_SERIES_ASIN   = "extra_series_asin"
        const val EXTRA_SEASON_ASIN   = "extra_season_asin"
    }

    // --- Views ---
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

    // --- Controllers ---
    private lateinit var orchestrator: PlaybackOrchestrator
    private lateinit var seekPreview: SeekPreviewController
    private lateinit var trackMenu: TrackMenuController

    // --- Scope ---
    private val scopeJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + scopeJob)

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

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
        playerView       = findViewById(R.id.player_view)
        progressBar      = findViewById(R.id.progress_bar)
        tvError          = findViewById(R.id.tv_error)
        trackButtons     = findViewById(R.id.track_buttons)
        tvPlaybackTitle  = findViewById(R.id.tv_playback_title)
        tvPlaybackStatus = findViewById(R.id.tv_playback_status)
        tvVideoFormat    = findViewById(R.id.tv_video_format)
        tvAudioFormat    = findViewById(R.id.tv_audio_format)
        tvPlaybackHint   = findViewById(R.id.tv_playback_hint)
        btnAudio         = findViewById(R.id.btn_audio)
        btnSubtitle      = findViewById(R.id.btn_subtitle)

        tvPlaybackTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: "Now Playing"
        tvPlaybackHint.text  = "Press MENU for tracks. Press Back to exit."
        tvVideoFormat.visibility = View.GONE
        playerView.keepScreenOn = false
        playerView.setShowSubtitleButton(false)
        playerView.post {
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)?.visibility = View.GONE
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
            controllerView = playerView.findViewById(androidx.media3.ui.R.id.exo_controller)
        }
        applyDeviceOverlayTuning()

        val asin = intent.getStringExtra(EXTRA_ASIN)
            ?: run { showError("No ASIN provided"); return }

        val tokenFile = LoginActivity.findTokenFile(this)
            ?: run { finish(); return }
        val authService = AmazonAuthService(tokenFile)
        val apiService  = AmazonApiService(authService)
        ProgressRepository.init(applicationContext)

        orchestrator = PlaybackOrchestrator(
            context          = this,
            authService      = authService,
            apiService       = apiService,
            scope            = scope,
            displaySupportsHdr = ::displaySupportsHdr,
            callbacks        = orchestratorCallbacks
        )
        orchestrator.currentAsin       = asin
        orchestrator.currentSeriesAsin = intent.getStringExtra(EXTRA_SERIES_ASIN) ?: ""
        orchestrator.currentSeasonAsin = intent.getStringExtra(EXTRA_SEASON_ASIN) ?: ""

        authService.onMediaRequestObserved = { url -> orchestrator.onMediaUrlObserved(url) }

        seekPreview = SeekPreviewController(
            cardSeekThumbnail   = findViewById(R.id.card_seek_thumbnail),
            ivSeekThumbnail     = findViewById(R.id.iv_seek_thumbnail),
            tvSeekTime          = findViewById(R.id.tv_seek_time),
            bifThumbnailProvider = orchestrator.bifThumbnailProvider,
            playbackInfoProvider = { orchestrator.currentPlaybackInfo }
        )

        trackMenu = TrackMenuController(
            context          = this,
            trackButtons     = trackButtons,
            btnAudio         = btnAudio,
            btnSubtitle      = btnSubtitle,
            audioTrackResolver = orchestrator.audioTrackResolver,
            playerProvider   = { orchestrator.player }
        )

        // Fix D-pad seek increment (default is duration/20 ≈ 6 min on a 2 h film)
        playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
            ?.also { timeBar ->
                timeBar.setKeyTimeIncrement(10_000L)
                timeBar.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
                    override fun onScrubStart(tb: androidx.media3.ui.TimeBar, position: Long) = seekPreview.showThumbnailAt(position)
                    override fun onScrubMove(tb: androidx.media3.ui.TimeBar, position: Long)  = seekPreview.showThumbnailAt(position)
                    override fun onScrubStop(tb: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) = seekPreview.hideThumbnail()
                })
            }

        playerView.setControllerVisibilityListener(
            androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
                val visible = visibility == View.VISIBLE
                trackMenu.onControllerVisibilityChanged(visible)
                if (!visible) seekPreview.hideThumbnail()
            }
        )

        btnAudio.setOnClickListener    { trackMenu.showTrackSelectionDialog(androidx.media3.common.C.TRACK_TYPE_AUDIO) }
        btnSubtitle.setOnClickListener { trackMenu.showTrackSelectionDialog(androidx.media3.common.C.TRACK_TYPE_TEXT) }
        btnAudio.nextFocusDownId    = R.id.btn_subtitle
        btnSubtitle.nextFocusUpId   = R.id.btn_audio

        val materialType = intent.getStringExtra(EXTRA_MATERIAL_TYPE) ?: "Feature"
        val intentResumeMs = intent.getLongExtra(EXTRA_RESUME_MS, 0L).coerceAtLeast(0L)
        orchestrator.loadAndPlay(asin, materialType, intentResumeMs)
    }

    override fun onPause() {
        super.onPause()
        orchestrator.persistPlaybackProgress(force = true)
        orchestrator.player?.pause()
    }

    override fun onResume() {
        super.onResume()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
        orchestrator.player?.play()
    }

    override fun onStop() {
        super.onStop()
        orchestrator.persistPlaybackProgress(force = true)
        orchestrator.stopReportingIfActive()
        updatePlaybackWakeState(false)
        orchestrator.player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        scopeJob.cancel()
        trackMenu.release()
        seekPreview.cancelPendingHide()
        orchestrator.release()
        updatePlaybackWakeState(false)
    }

    // -------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val seekBar = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) &&
            playerView.isControllerFullyVisible &&
            currentFocus == seekBar
        ) {
            seekPreview.handleDpadSeek(event.keyCode, orchestrator.player)
            return super.dispatchKeyEvent(event)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (playerView.isControllerFullyVisible) {
                trackButtons.clearFocus(); playerView.hideController()
            } else {
                playerView.showController()
                btnAudio.postDelayed({
                    if (playerView.isControllerFullyVisible && trackButtons.visibility == View.VISIBLE)
                        btnAudio.requestFocus()
                }, 120L)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // -------------------------------------------------------------------------
    // OrchestratorCallbacks
    // -------------------------------------------------------------------------

    private val orchestratorCallbacks = object : PlaybackOrchestrator.Callbacks {
        override fun onPlayerReady(player: ExoPlayer, trackSelector: DefaultTrackSelector) {
            playerView.player = player
            // Re-hide native track buttons that reappear when ExoPlayer is attached
            playerView.post {
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)?.visibility = View.GONE
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
            }
        }
        override fun showBuffering() { progressBar.visibility = View.VISIBLE }
        override fun hideBuffering() { progressBar.visibility = View.GONE }
        override fun onError(message: String) = showError(message)
        override fun onFinish()  = finish()
        override fun onPlaybackLabelsChanged() = updateVideoFormatLabel()
        override fun onTracksChanged(tracks: Tracks) {
            trackMenu.updateTrackButtonLabels(tracks)
            playerView.post {
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)?.visibility = View.GONE
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
            }
        }
        override fun onKeepScreenOn(on: Boolean) = updatePlaybackWakeState(on)
        override fun onTitleChanged(title: String) { tvPlaybackTitle.text = title }
        override fun onQualityResolved(materialType: String, quality: PlaybackQuality) =
            updatePlaybackStatus(materialType, quality)
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun updateVideoFormatLabel() {
        val fmt = orchestrator.player?.videoFormat
            ?: run { tvVideoFormat.text = ""; tvAudioFormat.visibility = View.GONE; return }
        val codecs = fmt.codecs ?: ""
        Log.i(TAG, "Video format: ${fmt.height}p mime=${fmt.sampleMimeType} codecs=$codecs colorTransfer=${fmt.colorInfo?.colorTransfer}")
        val labels = VideoFormatLabeler.computeFormatLabels(
            fmt, orchestrator.currentVideoBitrateKbps,
            orchestrator.currentAudioBitrateKbps, orchestrator.currentAudioChannelCount
        )
        tvVideoFormat.text = labels.videoLabel
        tvVideoFormat.visibility = if (labels.videoLabel.isBlank()) View.GONE else View.VISIBLE
        tvAudioFormat.text = labels.audioLabel
        tvAudioFormat.visibility = if (labels.audioLabel.isBlank()) View.GONE else View.VISIBLE
    }

    private fun updatePlaybackStatus(materialType: String, quality: PlaybackQuality) {
        val materialLabel = if (materialType == "Trailer") "Trailer" else "Playback"
        val qualityLabel = when (quality) {
            PlaybackQuality.UHD_HDR -> "4K HDR preset"
            PlaybackQuality.HD_H265 -> "HD H265 preset"
            PlaybackQuality.HD      -> "HD H264 preset"
            PlaybackQuality.SD      -> "SD (Widevine L3)"
            else                    -> "HD H264 preset"
        }
        tvPlaybackStatus.text = "$materialLabel  ·  $qualityLabel"
    }

    private fun updatePlaybackWakeState(on: Boolean) {
        playerView.keepScreenOn = on
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        playerView.hideController()
        playerView.useController = false
        trackButtons.visibility = View.GONE
        tvError.text = "Playback unavailable\n${VideoFormatLabeler.friendlyError(message)}"
        tvError.visibility = View.VISIBLE
    }

    /** Returns true if the connected display reports HDR support via HDMI EDID. */
    @Suppress("DEPRECATION")
    private fun displaySupportsHdr(): Boolean {
        val caps = windowManager.defaultDisplay.hdrCapabilities ?: return false
        return caps.supportedHdrTypes.isNotEmpty()
    }

    private fun applyDeviceOverlayTuning() {
        if (!android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) return
        updateMargins(trackButtons, topDp = 20, endDp = 28)
        updateMargins(tvError, startDp = 36, bottomDp = 44)
        tvError.maxLines = 4
        updateWidth(tvPlaybackTitle, 224); updateWidth(tvPlaybackHint, 224)
        updateHeight(btnAudio, 38); updateHeight(btnSubtitle, 38)
    }

    private fun updateMargins(view: View, startDp: Int? = null, topDp: Int? = null, endDp: Int? = null, bottomDp: Int? = null) {
        val p = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val d = resources.displayMetrics.density
        startDp?.let  { p.marginStart  = (it * d).toInt() }
        topDp?.let    { p.topMargin    = (it * d).toInt() }
        endDp?.let    { p.marginEnd    = (it * d).toInt() }
        bottomDp?.let { p.bottomMargin = (it * d).toInt() }
        view.layoutParams = p
    }

    private fun updateWidth(view: View, widthDp: Int) {
        val p = view.layoutParams ?: return
        p.width = (widthDp * resources.displayMetrics.density).toInt()
        view.layoutParams = p
    }

    private fun updateHeight(view: View, heightDp: Int) {
        val p = view.layoutParams ?: return
        p.height = (heightDp * resources.displayMetrics.density).toInt()
        view.layoutParams = p
    }
}
