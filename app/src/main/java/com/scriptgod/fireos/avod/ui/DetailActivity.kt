package com.scriptgod.fireos.avod.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.scriptgod.fireos.avod.R
import com.scriptgod.fireos.avod.api.AmazonApiService
import com.scriptgod.fireos.avod.auth.AmazonAuthService
import com.scriptgod.fireos.avod.data.ProgressRepository
import com.scriptgod.fireos.avod.model.ContentItem
import com.scriptgod.fireos.avod.model.DetailInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DetailActivity"
        const val EXTRA_ASIN = "extra_asin"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CONTENT_TYPE = "extra_content_type"
        const val EXTRA_IMAGE_URL = "extra_image_url"
        const val EXTRA_WATCHLIST_ASINS = "extra_watchlist_asins"
        const val EXTRA_IS_PRIME = "extra_is_prime"
    }

    private lateinit var layoutContent: View
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var ivHero: ImageView
    private lateinit var ivPoster: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvDetailEyebrow: TextView
    private lateinit var tvDetailSupport: TextView
    private lateinit var tvMetadata: TextView
    private lateinit var tvImdb: TextView
    private lateinit var tvSynopsis: TextView
    private lateinit var tvSynopsisLabel: TextView
    private lateinit var tvDirectorsLabel: TextView
    private lateinit var tvDirectors: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnTrailer: Button
    private lateinit var btnBrowse: Button
    private lateinit var btnSeasons: Button
    private lateinit var btnWatchlist: Button
    private lateinit var tvPrimeBadge: TextView
    private lateinit var pbWatchProgress: ProgressBar
    private lateinit var tvWatchProgress: TextView

    private lateinit var apiService: AmazonApiService
    private var watchlistAsins: MutableSet<String> = mutableSetOf()
    private var currentAsin: String = ""
    private var currentContentType: String = ""
    private var fallbackImageUrl: String = ""
    private var detailInfo: DetailInfo? = null
    private var isItemPrime: Boolean = false
    private var watchlistUpdateInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        layoutContent = findViewById(R.id.layout_content)
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
        ivHero = findViewById(R.id.iv_hero)
        ivPoster = findViewById(R.id.iv_poster)
        tvTitle = findViewById(R.id.tv_title)
        tvDetailEyebrow = findViewById(R.id.tv_detail_eyebrow)
        tvDetailSupport = findViewById(R.id.tv_detail_support)
        tvMetadata = findViewById(R.id.tv_metadata)
        tvImdb = findViewById(R.id.tv_imdb)
        tvSynopsis = findViewById(R.id.tv_synopsis)
        tvSynopsisLabel = findViewById(R.id.tv_synopsis_label)
        tvDirectorsLabel = findViewById(R.id.tv_directors_label)
        tvDirectors = findViewById(R.id.tv_directors)
        btnPlay = findViewById(R.id.btn_play)
        btnTrailer = findViewById(R.id.btn_trailer)
        btnBrowse = findViewById(R.id.btn_browse)
        btnSeasons = findViewById(R.id.btn_seasons)
        btnWatchlist = findViewById(R.id.btn_watchlist)
        tvPrimeBadge = findViewById(R.id.tv_prime_badge)
        pbWatchProgress = findViewById(R.id.pb_watch_progress)
        tvWatchProgress = findViewById(R.id.tv_watch_progress)

        val tokenFile = LoginActivity.findTokenFile(this) ?: run { finish(); return }
        apiService = AmazonApiService(AmazonAuthService(tokenFile))
        ProgressRepository.init(applicationContext)

        currentAsin = intent.getStringExtra(EXTRA_ASIN)
            ?.takeIf { it.isNotBlank() }
            ?: run { finish(); return }
        currentContentType = intent.getStringExtra(EXTRA_CONTENT_TYPE) ?: ""
        fallbackImageUrl = intent.getStringExtra(EXTRA_IMAGE_URL) ?: ""
        watchlistAsins = (intent.getStringArrayListExtra(EXTRA_WATCHLIST_ASINS) ?: ArrayList()).toMutableSet()
        isItemPrime = intent.getBooleanExtra(EXTRA_IS_PRIME, false)

        tvTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: ""

        loadDetail()
    }

    override fun onResume() {
        super.onResume()
        val info = detailInfo ?: return
        // Re-read progress after returning from PlayerActivity — repository is already updated
        // in-memory by PlayerActivity so no network call is needed.
        val isSeries = AmazonApiService.isSeriesContentType(info.contentType)
        val isSeason = info.contentType.uppercase().contains("SEASON")
        if (isSeries && !isSeason) {
            updateSeriesResumeCta(info)
        } else if (btnPlay.isVisible) {
            val posMs = ProgressRepository.get(info.asin)?.positionMs ?: 0L
            btnPlay.text = if (posMs > 10_000L) "▶  Resume" else "▶  Play"
        }
        bindProgress(info)
    }

    private fun loadDetail() {
        progressBar.visibility = View.VISIBLE
        layoutContent.visibility = View.GONE
        tvError.visibility = View.GONE

        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                try {
                    apiService.detectTerritory()
                    apiService.getDetailInfo(currentAsin)
                } catch (e: Exception) {
                    Log.w(TAG, "loadDetail failed for $currentAsin: ${e.message}", e)
                    null
                }
            }

            progressBar.visibility = View.GONE

            if (info == null) {
                tvError.text = "Could not load details — check your connection and try again"
                tvError.visibility = View.VISIBLE
                return@launch
            }

            detailInfo = info
            bindDetail(info)
        }
    }

    private fun bindDetail(info: DetailInfo) {
        // Hero image
        val heroUrl = info.heroImageUrl.ifEmpty { fallbackImageUrl }
        if (heroUrl.isNotEmpty()) {
            ivHero.load(heroUrl) { crossfade(true) }
        }

        // Poster image
        val posterUrl = info.posterImageUrl.ifEmpty { fallbackImageUrl }
        if (posterUrl.isNotEmpty()) {
            ivPoster.load(posterUrl) { crossfade(true) }
        } else {
            ivPoster.setBackgroundColor(0xFF222222.toInt())
        }

        // Title
        tvTitle.text = if (info.showTitle.isNotEmpty()) "${info.showTitle}: ${info.title}"
                       else info.title
        tvDetailEyebrow.text = detailEyebrow(info.contentType)
        tvDetailSupport.text = UiMetadataFormatter.detailSupportLine(info)

        if (info.isPrime) {
            tvPrimeBadge.text = "\u2713 Included with Prime"
            tvPrimeBadge.setTextColor(Color.parseColor("#2DC8E0"))
        } else {
            tvPrimeBadge.text = "\u2715 Not included with Prime"
            tvPrimeBadge.setTextColor(Color.parseColor("#8FA8B4"))
        }
        tvPrimeBadge.visibility = View.VISIBLE

        // Metadata row: year · runtime · age rating · quality
        val meta = buildString {
            if (info.year > 0) append(info.year)
            if (info.runtimeSeconds > 0) {
                if (isNotEmpty()) append("  ·  ")
                val h = info.runtimeSeconds / 3600
                val m = (info.runtimeSeconds % 3600) / 60
                append(if (h > 0) "${h}h ${m}min" else "${m}min")
            }
            if (info.ageRating.isNotEmpty()) {
                if (isNotEmpty()) append("  ·  ")
                append(info.ageRating)
            }
            val qualityTags = buildList {
                if (info.isUhd) add("4K")
                if (info.isHdr) add("HDR")
                if (info.isDolby51) add("5.1")
            }
            if (qualityTags.isNotEmpty()) {
                if (isNotEmpty()) append("  ·  ")
                append(qualityTags.joinToString(" "))
            }
        }
        tvMetadata.text = meta
        tvMetadata.visibility = if (meta.isNotBlank()) View.VISIBLE else View.GONE

        if (info.imdbRating > 0f) {
            tvImdb.text = "\u2605 IMDb %.1f / 10".format(info.imdbRating)
            tvImdb.setTextColor(imdbColorFor(info.imdbRating))
            tvImdb.visibility = View.VISIBLE
        } else {
            tvImdb.visibility = View.GONE
        }

        // Synopsis
        if (info.synopsis.isNotEmpty()) {
            tvSynopsis.text = info.synopsis
            tvSynopsis.visibility = View.VISIBLE
            tvSynopsisLabel.visibility = View.VISIBLE
        } else {
            tvSynopsis.visibility = View.GONE
            tvSynopsisLabel.visibility = View.GONE
        }

        // Directors
        if (info.directors.isNotEmpty()) {
            tvDirectors.text = "Director: " + info.directors.joinToString(", ")
            tvDirectors.visibility = View.VISIBLE
            tvDirectorsLabel.visibility = View.VISIBLE
        } else {
            tvDirectors.visibility = View.GONE
            tvDirectorsLabel.visibility = View.GONE
        }

        // Watchlist button
        updateWatchlistButton(info.isInWatchlist || watchlistAsins.contains(currentAsin))
        btnWatchlist.visibility = View.VISIBLE
        btnWatchlist.setOnClickListener { onWatchlistClicked() }

        // Play / Browse buttons based on content type
        val isSeries = AmazonApiService.isSeriesContentType(info.contentType)
        val isSeason = info.contentType.uppercase().contains("SEASON")
        if (isSeries) {
            if (isSeason) {
                // Season detail → Browse Episodes + All Seasons (via parent show ASIN)
                btnBrowse.text = "Browse Episodes"
                btnBrowse.visibility = View.VISIBLE
                btnBrowse.setOnClickListener { onBrowseClicked(info) }
                if (info.showAsin.isNotEmpty()) {
                    btnSeasons.visibility = View.VISIBLE
                    btnSeasons.setOnClickListener { onAllSeasonsClicked(info) }
                }
            } else {
                // Series overview → Resume Episode (if in progress) + Browse Seasons
                updateSeriesResumeCta(info)
                btnBrowse.text = "Browse Seasons"
                btnBrowse.visibility = View.VISIBLE
                btnBrowse.setOnClickListener { onBrowseClicked(info) }
            }
        } else {
            // Movie / Feature → play
            val resumeMs = ProgressRepository.get(info.asin)?.positionMs ?: 0L
            btnPlay.text = if (resumeMs > 10_000L) "▶  Resume" else "▶  Play"
            btnPlay.visibility = View.VISIBLE
            btnPlay.setOnClickListener { onPlayClicked(info) }
        }

        bindProgress(info)

        // Trailer button — shown for movies and seasons (not for series overview pages)
        if (info.isTrailerAvailable && (!isSeries || isSeason)) {
            btnTrailer.visibility = View.VISIBLE
            btnTrailer.setOnClickListener { onTrailerClicked(info) }
        }

        layoutContent.visibility = View.VISIBLE
        UiMotion.revealFresh(
            findViewById(R.id.detail_hero_section),
            findViewById(R.id.detail_body_section)
        )

        // Focus the primary action button
        layoutContent.post {
            when {
                btnPlay.visibility == View.VISIBLE -> btnPlay.requestFocus()
                btnBrowse.visibility == View.VISIBLE -> btnBrowse.requestFocus()
                else -> btnWatchlist.requestFocus()
            }
        }
    }

    private fun bindProgress(info: DetailInfo) {
        val runtimeMs = info.runtimeSeconds * 1000L
        val posMs = ProgressRepository.get(info.asin)?.positionMs ?: 0L

        // No runtime or no position — nothing to show.
        if (runtimeMs <= 0L || posMs == 0L) {
            pbWatchProgress.isVisible = false
            tvWatchProgress.isVisible = false
            return
        }

        // -1L sentinel means fully watched (stored by ProgressRepository.update when pos ≥ 90%).
        if (posMs == -1L) {
            pbWatchProgress.isVisible = false
            tvWatchProgress.text = "Finished recently"
            tvWatchProgress.isVisible = true
            return
        }

        // Bar fraction on 0–1000 scale (XML already declares max="1000").
        val fraction = (posMs * 1000L / runtimeMs).toInt().coerceIn(0, 1000)

        // Text — delegate to the canonical formatter so card subtitles and detail page agree.
        val text = UiMetadataFormatter.progressText(posMs, runtimeMs)
        if (text == "Finished recently" || text == null) {
            pbWatchProgress.isVisible = false
            tvWatchProgress.text = text ?: ""
            tvWatchProgress.isVisible = text != null
            return
        }

        pbWatchProgress.progress = fraction   // max already set to 1000 in XML
        pbWatchProgress.isVisible = true
        tvWatchProgress.text = text
        tvWatchProgress.isVisible = true
    }

    private fun updateWatchlistButton(isIn: Boolean) {
        btnWatchlist.text = if (isIn) "In Watchlist" else "Add to Watchlist"
        btnWatchlist.isSelected = isIn
    }

    private fun detailEyebrow(contentType: String): String {
        val upper = contentType.uppercase()
        return when {
            upper.contains("SEASON") -> "SEASON"
            upper.contains("EPISODE") -> "EPISODE"
            AmazonApiService.isSeriesContentType(contentType) -> "SERIES"
            else -> "MOVIE"
        }
    }

    private fun imdbColorFor(rating: Float): Int {
        return when {
            rating >= 8.5f -> Color.parseColor("#4FD1C5")
            rating >= 7.0f -> Color.parseColor("#9FD36A")
            rating >= 6.0f -> Color.parseColor("#F3C85F")
            rating >= 5.0f -> Color.parseColor("#F29D52")
            else -> Color.parseColor("#E06B6B")
        }
    }

    private fun onPlayClicked(info: DetailInfo) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_ASIN, info.asin)
            putExtra(PlayerActivity.EXTRA_TITLE, info.title)
            putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, info.contentType)
            putExtra(
                PlayerActivity.EXTRA_RESUME_MS,
                ProgressRepository.get(info.asin)?.positionMs?.coerceAtLeast(0L) ?: 0L
            )
        }
        UiTransitions.open(this, intent)
    }

    private fun onResumeEpisodeClicked(episode: ContentItem) {
        val resumeMs = ProgressRepository.get(episode.asin)?.positionMs?.coerceAtLeast(0L) ?: 0L
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_ASIN, episode.asin)
            putExtra(PlayerActivity.EXTRA_TITLE, episode.title)
            putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, episode.contentType)
            putExtra(PlayerActivity.EXTRA_RESUME_MS, resumeMs)
            if (episode.seriesAsin.isNotEmpty()) putExtra(PlayerActivity.EXTRA_SERIES_ASIN, episode.seriesAsin)
            if (episode.seasonId.isNotEmpty()) putExtra(PlayerActivity.EXTRA_SEASON_ASIN, episode.seasonId)
        }
        UiTransitions.open(this, intent)
    }

    /** Finds the most recent in-progress episode for a series and wires up the Resume CTA.
     *  Checks server-backed items first; falls back to local-only progress map entries. */
    private fun updateSeriesResumeCta(info: DetailInfo) {
        val serverEpisode = ProgressRepository.getInProgressItems()
            .filter { it.seriesAsin == info.asin }
            .maxByOrNull { it.watchProgressMs }
        val resumeEpisode: ContentItem? = serverEpisode ?: run {
            val local = ProgressRepository.getLocalProgressForSeries(info.asin) ?: return@run null
            ContentItem(
                asin = local.first,
                title = "Episode",
                contentType = "Episode",
                watchProgressMs = local.second.positionMs,
                seriesAsin = info.asin,
                seasonId = local.second.seasonAsin
            )
        }
        if (resumeEpisode != null) {
            val label = if (resumeEpisode.seasonNumber != null && resumeEpisode.episodeNumber != null)
                "▶  Resume S${resumeEpisode.seasonNumber}E${resumeEpisode.episodeNumber}"
            else "▶  Resume Episode"
            btnPlay.text = label
            btnPlay.visibility = View.VISIBLE
            btnPlay.setOnClickListener { onResumeEpisodeClicked(resumeEpisode) }
        } else {
            btnPlay.visibility = View.GONE
        }
    }

    private fun onTrailerClicked(info: DetailInfo) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_ASIN, info.asin)
            putExtra(PlayerActivity.EXTRA_TITLE, "${info.title} — Trailer")
            putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, info.contentType)
            putExtra(PlayerActivity.EXTRA_MATERIAL_TYPE, "Trailer")
        }
        UiTransitions.open(this, intent)
    }

    private fun onBrowseClicked(info: DetailInfo) {
        val filter = if (info.contentType.uppercase().contains("SEASON")) "episodes" else "seasons"
        val intent = Intent(this, BrowseActivity::class.java).apply {
            putExtra(BrowseActivity.EXTRA_ASIN, info.asin)
            putExtra(BrowseActivity.EXTRA_TITLE, info.title)
            putExtra(BrowseActivity.EXTRA_CONTENT_TYPE, info.contentType)
            putExtra(BrowseActivity.EXTRA_FILTER, filter)
            putExtra(BrowseActivity.EXTRA_IMAGE_URL, info.posterImageUrl.ifEmpty { fallbackImageUrl })
            putStringArrayListExtra(BrowseActivity.EXTRA_WATCHLIST_ASINS, ArrayList(watchlistAsins))
        }
        UiTransitions.open(this, intent)
    }

    private fun onAllSeasonsClicked(info: DetailInfo) {
        val intent = Intent(this, BrowseActivity::class.java).apply {
            putExtra(BrowseActivity.EXTRA_ASIN, info.showAsin)
            putExtra(BrowseActivity.EXTRA_TITLE, info.showTitle.ifEmpty { info.title })
            putExtra(BrowseActivity.EXTRA_CONTENT_TYPE, "SERIES")
            putExtra(BrowseActivity.EXTRA_FILTER, "seasons")
            putExtra(BrowseActivity.EXTRA_IMAGE_URL, info.posterImageUrl.ifEmpty { fallbackImageUrl })
            putStringArrayListExtra(BrowseActivity.EXTRA_WATCHLIST_ASINS, ArrayList(watchlistAsins))
        }
        UiTransitions.open(this, intent)
    }

    private fun onWatchlistClicked() {
        if (watchlistUpdateInFlight) return   // guard against double-tap
        val isIn = watchlistAsins.contains(currentAsin)
        val overlayItem = com.scriptgod.fireos.avod.model.ContentItem(
            asin = currentAsin,
            title = detailInfo?.title ?: (intent.getStringExtra(EXTRA_TITLE) ?: ""),
            contentType = detailInfo?.contentType ?: currentContentType
        )
        WatchlistActionOverlay.show(
            activity = this,
            item = overlayItem,
            isInWatchlist = isIn
        ) {
            watchlistUpdateInFlight = true
            lifecycleScope.launch {
                try {
                    val success = withContext(Dispatchers.IO) {
                        if (isIn) apiService.removeFromWatchlist(currentAsin)
                        else apiService.addToWatchlist(currentAsin)
                    }
                    if (success) {
                        if (isIn) watchlistAsins.remove(currentAsin)
                        else watchlistAsins.add(currentAsin)
                        updateWatchlistButton(watchlistAsins.contains(currentAsin))
                        val msg = if (isIn) "Removed from watchlist" else "Added to watchlist"
                        Toast.makeText(this@DetailActivity, msg, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@DetailActivity, "Watchlist update failed", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    watchlistUpdateInFlight = false
                }
            }
        }
    }
}
