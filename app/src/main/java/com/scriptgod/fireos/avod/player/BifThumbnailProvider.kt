package com.scriptgod.fireos.avod.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class BifThumbnailProvider(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "BifThumbnailProvider"
    }

    /** BIF index: list of (timecodeMs, byteOffset) pairs loaded once per playback session. */
    private var bifEntries: List<Pair<Long, Int>>? = null
    private val thumbCache = LruCache<Int, Bitmap>(10)

    /**
     * Downloads and parses the BIF file header + index table so individual frames can be
     * fetched on demand via HTTP Range requests while the user scrubs.
     *
     * BIF header (64 bytes total):
     *   bytes 0-7:   magic
     *   bytes 8-11:  version (uint32 LE)
     *   bytes 12-15: image count (uint32 LE)
     *   bytes 16-19: timecode multiplier in ms (uint32 LE; e.g. 1000 = 1 frame/sec)
     *   bytes 20-63: reserved
     * BIF index starts at byte 64: N+1 entries of (uint32 timestamp, uint32 byteOffset),
     * terminated by (0xFFFFFFFF, totalFileSize).
     */
    fun loadIndex(bifUrl: String) {
        scope.launch(Dispatchers.IO) {
            try {
                // Fetch header to get image count and timecode multiplier.
                val headerBytes = bifRangeGet(bifUrl, 0, 63) ?: return@launch
                if (headerBytes.size < 20) return@launch
                val buf = java.nio.ByteBuffer.wrap(headerBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buf.position(8)  // skip magic
                buf.int          // skip version
                val imageCount = buf.int
                val multiplier = buf.int.toLong().coerceAtLeast(1L)
                if (imageCount <= 0 || imageCount > 100_000) return@launch

                // Fetch the index table (N+1 entries × 8 bytes each).
                val indexStart = 64L
                val indexBytes = bifRangeGet(bifUrl, indexStart, indexStart + (imageCount + 1) * 8 - 1)
                    ?: return@launch

                val idxBuf = java.nio.ByteBuffer.wrap(indexBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val entries = ArrayList<Pair<Long, Int>>(imageCount)
                for (i in 0..imageCount) {
                    val ts = idxBuf.int.toLong() and 0xFFFFFFFFL
                    val off = idxBuf.int
                    if (ts == 0xFFFFFFFFL) break
                    entries.add(Pair(ts * multiplier, off))
                }
                Log.i(TAG, "BIF index loaded: ${entries.size} frames, multiplier=${multiplier}ms")
                withContext(Dispatchers.Main) { bifEntries = entries }
            } catch (e: Exception) {
                Log.w(TAG, "BIF index load failed", e)
            }
        }
    }

    fun bifRangeGet(url: String, from: Long, to: Long): ByteArray? {
        return try {
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("Range", "bytes=$from-$to")
                .get().build()
            okhttp3.OkHttpClient().newCall(req).execute().body?.bytes()
        } catch (e: Exception) { null }
    }

    /**
     * Fetches the thumbnail at [posMs] and delivers it via [onBitmap].
     * Returns null via callback if thumbnails are unavailable or loading fails.
     */
    fun getThumbnailAt(posMs: Long, bifUrl: String, onBitmap: (Bitmap?) -> Unit) {
        val entries = bifEntries
        if (entries == null || entries.isEmpty()) {
            onBitmap(null)
            return
        }

        // Binary search: largest timecodeMs <= posMs.
        var lo = 0; var hi = entries.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (entries[mid].first <= posMs) lo = mid else hi = mid - 1
        }
        val idx = lo

        val cached = thumbCache.get(idx)
        if (cached != null) {
            onBitmap(cached)
            return
        }

        val fromOffset = entries[idx].second.toLong()
        val toOffset = if (idx + 1 < entries.size) entries[idx + 1].second.toLong() - 1
                       else fromOffset + 65535L

        scope.launch(Dispatchers.IO) {
            try {
                val bytes = bifRangeGet(bifUrl, fromOffset, toOffset)
                if (bytes == null) {
                    withContext(Dispatchers.Main) { onBitmap(null) }
                    return@launch
                }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) thumbCache.put(idx, bmp)
                withContext(Dispatchers.Main) { onBitmap(bmp) }
            } catch (e: Exception) {
                Log.w(TAG, "BIF frame fetch failed idx=$idx", e)
                withContext(Dispatchers.Main) { onBitmap(null) }
            }
        }
    }

    fun hasBifEntries(): Boolean = bifEntries != null && bifEntries!!.isNotEmpty()
}
