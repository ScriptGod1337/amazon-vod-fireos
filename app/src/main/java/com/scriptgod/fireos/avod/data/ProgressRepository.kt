package com.scriptgod.fireos.avod.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.scriptgod.fireos.avod.api.AmazonApiService
import com.scriptgod.fireos.avod.model.ContentItem
import java.util.concurrent.ConcurrentHashMap

object ProgressRepository {
    private const val TAG = "ProgressRepository"
    private const val PREFS_NAME = "progress_cache"
    private const val KEY_PROGRESS_MAP = "progress_map"

    data class ProgressEntry(
        val positionMs: Long,
        val runtimeMs: Long,
        val seriesAsin: String = "",
        val seasonAsin: String = ""
    )

    private val gson = Gson()
    private val progressMap = ConcurrentHashMap<String, ProgressEntry>()
    private var prefs: SharedPreferences? = null
    private var initialized = false
    private var inProgressItems: List<ContentItem> = emptyList()

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadLocalCache()
        initialized = true
    }

    @Synchronized
    fun clear() {
        progressMap.clear()
        inProgressItems = emptyList()
        prefs?.edit()?.clear()?.apply()
    }

    suspend fun refresh(apiService: AmazonApiService): Set<String> {
        ensureInitialized()
        val (watchlistAsins, serverProgress, serverInProgressItems) = apiService.getWatchlistData()
        synchronized(this) {
            progressMap.clear()
            progressMap.putAll(loadPersistedEntries())
            for ((asin, progress) in serverProgress) {
                // Repository contract: server wins on refresh. Local writes only become
                // authoritative again during the active playback session after refresh.
                progressMap[asin] = ProgressEntry(progress.first, progress.second)
            }
            inProgressItems = serverInProgressItems.map { item ->
                val entry = progressMap[item.asin]
                item.copy(
                    watchProgressMs = entry?.positionMs ?: item.watchProgressMs,
                    runtimeMs = if (entry != null && item.runtimeMs == 0L) entry.runtimeMs else item.runtimeMs
                )
            }
            persistAll()
            Log.i(TAG, "Refreshed progress: entries=${progressMap.size}, inProgressItems=${inProgressItems.size}")
        }
        return watchlistAsins
    }

    fun update(asin: String, posMs: Long, durMs: Long, materialType: String = "Feature", seriesAsin: String = "", seasonAsin: String = "") {
        ensureInitialized()
        if (materialType == "Trailer") return
        if (asin.isBlank() || posMs <= 0L) return
        synchronized(this) {
            val normalizedPos = if (durMs > 0L && posMs >= durMs * 9 / 10) -1L else posMs
            progressMap[asin] = ProgressEntry(normalizedPos, durMs.coerceAtLeast(0L), seriesAsin, seasonAsin)
            persistAll()
        }
    }

    fun get(asin: String): ProgressEntry? {
        ensureInitialized()
        return progressMap[asin]
    }

    fun getInProgressItems(): List<ContentItem> {
        ensureInitialized()
        return inProgressItems
    }

    fun getInProgressEntries(): Map<String, ProgressEntry> {
        ensureInitialized()
        return progressMap.filterValues { entry -> entry.positionMs > 0L }
    }

    /** Returns the local progress entry (asin, entry) with the highest positionMs for the given
     *  series ASIN — or null if none exists. Used by DetailActivity to surface a series resume
     *  CTA even before a server refresh has populated inProgressItems. */
    fun getLocalProgressForSeries(seriesAsin: String): Pair<String, ProgressEntry>? {
        ensureInitialized()
        return progressMap.entries
            .filter { (_, entry) -> entry.seriesAsin == seriesAsin && entry.positionMs > 0L }
            .maxByOrNull { (_, entry) -> entry.positionMs }
            ?.let { (asin, entry) -> Pair(asin, entry) }
    }

    private fun ensureInitialized() {
        check(initialized && prefs != null) { "ProgressRepository.init(context) must be called first" }
    }

    private fun loadLocalCache() {
        progressMap.clear()
        progressMap.putAll(loadPersistedEntries())
        inProgressItems = emptyList()
    }

    private fun loadPersistedEntries(): Map<String, ProgressEntry> {
        val json = prefs?.getString(KEY_PROGRESS_MAP, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, ProgressEntry>>() {}.type
            gson.fromJson<Map<String, ProgressEntry>>(json, type).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read local progress cache", e)
            emptyMap()
        }
    }

    private fun persistAll() {
        val json = gson.toJson(progressMap)
        prefs?.edit()?.putString(KEY_PROGRESS_MAP, json)?.apply()
    }
}
