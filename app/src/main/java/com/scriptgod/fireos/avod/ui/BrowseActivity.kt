package com.scriptgod.fireos.avod.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scriptgod.fireos.avod.R
import com.scriptgod.fireos.avod.api.AmazonApiService
import com.scriptgod.fireos.avod.auth.AmazonAuthService
import com.scriptgod.fireos.avod.data.ProgressRepository
import com.scriptgod.fireos.avod.model.ContentItem
import com.scriptgod.fireos.avod.model.isEpisode
import com.scriptgod.fireos.avod.model.isSeriesContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BrowseActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BrowseActivity"
        const val EXTRA_ASIN = "extra_asin"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CONTENT_TYPE = "extra_content_type"
        const val EXTRA_FILTER = "extra_filter"  // "seasons" or "episodes"
        const val EXTRA_IMAGE_URL = "extra_image_url"  // fallback image for child items
        const val EXTRA_WATCHLIST_ASINS = "extra_watchlist_asins"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var shimmerRecyclerView: RecyclerView
    private lateinit var tvError: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvContextChip: TextView
    private lateinit var tvCountChip: TextView
    private lateinit var btnBack: Button
    private lateinit var headerCard: View
    private lateinit var gridPanel: View
    private lateinit var apiService: AmazonApiService
    private lateinit var adapter: ContentAdapter
    private var parentImageUrl: String = ""
    private var watchlistAsins: MutableSet<String> = mutableSetOf()
    private var preferHeaderFocus: Boolean = false
    private var currentFilter: String? = null
    private var browseAsin: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)

        recyclerView = findViewById(R.id.recycler_view)
        progressBar = findViewById(R.id.progress_bar)
        shimmerRecyclerView = findViewById(R.id.shimmer_recycler_view)
        tvError = findViewById(R.id.tv_error)
        tvTitle = findViewById(R.id.tv_browse_title)
        tvSubtitle = findViewById(R.id.tv_browse_subtitle)
        tvHint = findViewById(R.id.tv_browse_hint)
        tvContextChip = findViewById(R.id.tv_browse_context_chip)
        tvCountChip = findViewById(R.id.tv_browse_count_chip)
        btnBack = findViewById(R.id.btn_browse_back)
        headerCard = findViewById(R.id.browse_header_card)
        gridPanel = findViewById(R.id.browse_grid_panel)

        val tokenFile = LoginActivity.findTokenFile(this)
            ?: run { finish(); return }
        val authService = AmazonAuthService(tokenFile)
        apiService = AmazonApiService(authService)
        ProgressRepository.init(applicationContext)

        watchlistAsins = (intent.getStringArrayListExtra(EXTRA_WATCHLIST_ASINS) ?: ArrayList()).toMutableSet()

        shimmerRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        shimmerRecyclerView.adapter = ShimmerAdapter()
        btnBack.setOnClickListener { UiTransitions.close(this) }

        val asin = intent.getStringExtra(EXTRA_ASIN) ?: return
        browseAsin = asin
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val contentType = intent.getStringExtra(EXTRA_CONTENT_TYPE) ?: ""
        parentImageUrl = intent.getStringExtra(EXTRA_IMAGE_URL) ?: ""

        tvTitle.text = title

        // Determine filter: explicit extra overrides content type inference
        val filter = intent.getStringExtra(EXTRA_FILTER)
            ?: if (AmazonApiService.isSeriesContentType(contentType)) "seasons" else null
        currentFilter = filter
        preferHeaderFocus = filter == "seasons"
        configureGridForFilter(filter)

        applyBrowseHeader(filter, contentType)
        loadDetails(asin, filterType = filter, fallbackImage = parentImageUrl)

        if (preferHeaderFocus) {
            btnBack.requestFocus()
        }
    }

    private fun loadDetails(asin: String, filterType: String? = null, fallbackImage: String = "") {
        showLoadingState()
        tvError.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    apiService.detectTerritory()
                    apiService.getDetailPage(asin)
                }
                hideLoadingState()
                Log.i(TAG, "Detail page for $asin returned ${items.size} items, types: ${items.map { it.contentType }.distinct()}")

                val filtered = when (filterType) {
                    "seasons" -> {
                        // Show seasons first; if none, show episodes directly
                        val seasons = items.filter { it.kind == com.scriptgod.fireos.avod.model.ContentKind.SEASON }
                        if (seasons.isNotEmpty()) seasons
                        else {
                            // No seasons — might be episodes directly
                            val episodes = items.filter { it.isEpisode() }
                            if (episodes.isNotEmpty()) {
                                currentFilter = "episodes"
                                applyBrowseHeader("episodes", "EPISODE")
                                configureGridForFilter("episodes")
                                episodes
                            } else items // show all
                        }
                    }
                    "episodes" -> {
                        val episodes = items.filter { it.isEpisode() }
                        if (episodes.isNotEmpty()) episodes else items
                    }
                    else -> items
                }

                // Apply fallback image to items that have no image
                val withImages = if (fallbackImage.isNotEmpty()) {
                    filtered.map { item ->
                        if (item.imageUrl.isEmpty()) item.copy(imageUrl = fallbackImage) else item
                    }
                } else filtered

                if (withImages.isEmpty()) {
                    recyclerView.visibility = View.GONE
                    tvError.text = "No content found"
                    tvError.visibility = View.VISIBLE
                    tvCountChip.text = "0 Items"
                } else {
                    recyclerView.visibility = View.VISIBLE
                    val withProgress = withImages.map { item ->
                        val progress = ProgressRepository.get(item.asin)
                        if (progress != null) item.copy(
                            watchProgressMs = progress.positionMs,
                            runtimeMs = if (item.runtimeMs == 0L) progress.runtimeMs else item.runtimeMs
                        ) else item
                    }
                    val withWatchlist = withProgress.map { it.copy(isInWatchlist = watchlistAsins.contains(it.asin)) }
                    adapter.submitList(withWatchlist)
                    val itemLabel = if (withWatchlist.size == 1) "1 Item" else "${withWatchlist.size} Items"
                    tvCountChip.text = itemLabel
                    UiMotion.revealFresh(headerCard, gridPanel)
                    recyclerView.post { requestPreferredFocus() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading details for $asin", e)
                hideLoadingState()
                recyclerView.visibility = View.GONE
                tvError.text = "Error: ${e.message}"
                tvError.visibility = View.VISIBLE
                tvCountChip.text = "Unavailable"
            }
        }
    }

    private fun showLoadingState() {
        progressBar.visibility = View.GONE
        recyclerView.visibility = View.GONE
        shimmerRecyclerView.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        tvCountChip.text = "Loading..."
    }

    private fun hideLoadingState() {
        progressBar.visibility = View.GONE
        shimmerRecyclerView.visibility = View.GONE
    }

    private fun applyBrowseHeader(filter: String?, contentType: String) {
        val isSeries = AmazonApiService.isSeriesContentType(contentType)
        val contextLabel: String
        val subtitle: String
        val hint: String

        when (filter) {
            "seasons" -> {
                contextLabel = "Seasons"
                subtitle = "Select a season"
                hint = "Browse seasons and open a season overview before drilling into episodes."
            }
            "episodes" -> {
                contextLabel = "Episodes"
                subtitle = "Select an episode"
                hint = "Choose an episode to start playback directly from this browse screen."
            }
            else -> {
                contextLabel = if (isSeries) "Series" else "Collection"
                subtitle = if (isSeries) "Browse available titles" else "Select a title"
                hint = "Use the grid to explore this set of items. MENU still opens the watchlist action."
            }
        }

        tvContextChip.text = contextLabel
        tvSubtitle.text = subtitle
        tvSubtitle.visibility = View.VISIBLE
        tvHint.text = hint
    }

    private fun configureGridForFilter(filter: String?) {
        currentFilter = filter
        val presentation = when (filter) {
            "seasons" -> CardPresentation.SEASON
            "episodes" -> CardPresentation.EPISODE
            else -> CardPresentation.POSTER
        }
        val spanCount = when (presentation) {
            CardPresentation.EPISODE -> 5
            CardPresentation.SEASON -> 4
            CardPresentation.LANDSCAPE -> 4
            else -> 4
        }
        adapter = ContentAdapter(
            onItemClick = { item -> onItemSelected(item) },
            onMenuKey = { item -> showItemMenu(item) },
            nextFocusUpId = R.id.btn_browse_back,
            onVerticalFocusMove = { position, direction -> handleGridVerticalMove(position, direction) },
            presentation = presentation
        )
        recyclerView.layoutManager = GridLayoutManager(this, spanCount)
        recyclerView.adapter = adapter
        recyclerView.itemAnimator = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val item = focusedContentItem()
            if (item != null) {
                showItemMenu(item)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun focusedContentItem(): ContentItem? {
        var view: View? = recyclerView.findFocus() ?: return null
        while (view != null && view !== recyclerView) {
            val item = view.tag as? ContentItem
            if (item != null) return item
            view = view.parent as? View
        }
        return null
    }

    override fun onResume() {
        super.onResume()
        val currentList = adapter.currentList
        if (currentList.isNotEmpty()) {
            val updated = currentList.map { item ->
                val progress = ProgressRepository.get(item.asin)
                item.copy(
                    isInWatchlist = watchlistAsins.contains(item.asin),
                    watchProgressMs = progress?.positionMs ?: item.watchProgressMs,
                    runtimeMs = progress?.runtimeMs ?: item.runtimeMs
                )
            }
            adapter.submitList(updated)
        }
        recyclerView.post {
            UiMotion.reveal(headerCard, gridPanel)
            requestPreferredFocus()
        }
    }

    private fun requestPreferredFocus() {
        if (preferHeaderFocus) {
            btnBack.requestFocus()
            return
        }
        val firstChild = recyclerView.getChildAt(0)
        if (firstChild != null) firstChild.requestFocus()
        else recyclerView.requestFocus()
    }

    private fun handleGridVerticalMove(position: Int, direction: Int): Boolean {
        val layoutManager = recyclerView.layoutManager as? GridLayoutManager ?: return false
        val spanCount = layoutManager.spanCount
        if (direction < 0) {
            val targetPosition = position - spanCount
            if (targetPosition < 0) {
                btnBack.requestFocus()
                return true
            }
            return requestGridPosition(targetPosition)
        }

        val targetPosition = position + spanCount
        if (targetPosition >= adapter.itemCount) return false
        return requestGridPosition(targetPosition)
    }

    private fun requestGridPosition(position: Int): Boolean {
        val holder = recyclerView.findViewHolderForAdapterPosition(position)
        if (holder?.itemView != null) {
            holder.itemView.requestFocus()
            return true
        }
        recyclerView.scrollToPosition(position)
        recyclerView.post {
            recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
        return true
    }

    // --- Watchlist context menu (MENU key) ---

    private fun showItemMenu(item: ContentItem) {
        WatchlistActionOverlay.show(
            activity = this,
            item = item,
            isInWatchlist = watchlistAsins.contains(item.asin)
        ) { toggleWatchlist(item) }
    }

    private fun toggleWatchlist(item: ContentItem) {
        val isCurrentlyIn = watchlistAsins.contains(item.asin)
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                if (isCurrentlyIn) apiService.removeFromWatchlist(item.asin)
                else apiService.addToWatchlist(item.asin)
            }
            if (success) {
                if (isCurrentlyIn) watchlistAsins.remove(item.asin)
                else watchlistAsins.add(item.asin)

                val result = if (isCurrentlyIn) "Removed from" else "Added to"
                Toast.makeText(this@BrowseActivity, "$result watchlist", Toast.LENGTH_SHORT).show()

                val updated = adapter.currentList.map { ci ->
                    if (ci.asin == item.asin) ci.copy(isInWatchlist = !isCurrentlyIn) else ci
                }
                adapter.submitList(updated)
            } else {
                Toast.makeText(this@BrowseActivity, "Watchlist update failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onItemSelected(item: ContentItem) {
        Log.i(TAG, "Selected: ${item.asin} — ${item.title} (type=${item.contentType})")
        when {
            // Season selected → overview page (description, IMDb, episodes list)
            item.isSeriesContainer() -> {
                val intent = if (currentFilter == "seasons") {
                    Intent(this, BrowseActivity::class.java).apply {
                        putExtra(EXTRA_ASIN, item.seasonId.ifEmpty { item.contentId })
                        putExtra(EXTRA_TITLE, item.title)
                        putExtra(EXTRA_CONTENT_TYPE, item.contentType)
                        putExtra(EXTRA_FILTER, "episodes")
                        putExtra(EXTRA_IMAGE_URL, item.imageUrl.ifEmpty { parentImageUrl })
                        putStringArrayListExtra(EXTRA_WATCHLIST_ASINS, ArrayList(watchlistAsins))
                    }
                } else {
                    Intent(this, DetailActivity::class.java).apply {
                        putExtra(DetailActivity.EXTRA_ASIN, item.asin)
                        putExtra(DetailActivity.EXTRA_TITLE, item.title)
                        putExtra(DetailActivity.EXTRA_CONTENT_TYPE, item.contentType)
                        putExtra(DetailActivity.EXTRA_IMAGE_URL, item.imageUrl.ifEmpty { parentImageUrl })
                        putExtra(DetailActivity.EXTRA_IS_PRIME, item.isPrime)
                        putStringArrayListExtra(DetailActivity.EXTRA_WATCHLIST_ASINS, ArrayList(watchlistAsins))
                    }
                }
                UiTransitions.open(this, intent)
            }
            // Episode/Movie/Feature → play
            else -> {
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_ASIN, item.asin)
                    putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                    putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, item.contentType)
                    putExtra(PlayerActivity.EXTRA_RESUME_MS, item.watchProgressMs.coerceAtLeast(0L))
                    if (item.seriesAsin.isNotEmpty()) putExtra(PlayerActivity.EXTRA_SERIES_ASIN, item.seriesAsin)
                    // Pass season ASIN so onPlaybackCompleted() can auto-advance to next episode.
                    // Prefer item.seasonId (server-provided); fall back to browseAsin when
                    // BrowseActivity is showing the episode list for a known season.
                    val seasonAsin = item.seasonId.ifEmpty {
                        if (currentFilter == "episodes") browseAsin else ""
                    }
                    if (seasonAsin.isNotEmpty()) putExtra(PlayerActivity.EXTRA_SEASON_ASIN, seasonAsin)
                }
                UiTransitions.open(this, intent)
            }
        }
    }
}
