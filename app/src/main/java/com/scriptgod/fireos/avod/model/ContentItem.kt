package com.scriptgod.fireos.avod.model

enum class ContentKind {
    MOVIE,
    SERIES,
    SEASON,
    EPISODE,
    LIVE,
    OTHER
}

enum class Availability {
    PRIME,
    FREEVEE,
    LIVE,
    UNKNOWN
}

data class ContentItem(
    val asin: String,
    val title: String,
    val subtitle: String = "",
    val imageUrl: String = "",
    val contentType: String = "Feature",   // Feature, Episode, Trailer, live, etc.
    val contentId: String = asin,
    val showId: String = "",
    val seasonId: String = "",
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val kind: ContentKind = ContentKind.OTHER,
    val availability: Availability = Availability.UNKNOWN,
    val seriesAsin: String = "",
    val isPrime: Boolean = true,
    val isFreeWithAds: Boolean = false,
    val isLive: Boolean = false,
    val channelId: String = "",
    val isInWatchlist: Boolean = false,
    val runtimeMs: Long = 0,
    val watchProgressMs: Long = 0  // 0 = not started, -1 = fully watched, >0 = position ms
)

fun ContentItem.isIncludedWithPrime(): Boolean = isPrime && !isFreeWithAds && !isLive

fun ContentItem.isMovie(): Boolean = kind == ContentKind.MOVIE

fun ContentItem.isSeriesContainer(): Boolean = kind == ContentKind.SERIES || kind == ContentKind.SEASON

fun ContentItem.isEpisode(): Boolean = kind == ContentKind.EPISODE

fun ContentItem.isLiveChannel(): Boolean = kind == ContentKind.LIVE

fun ContentItem.isFullyWatched(): Boolean = when {
    isSeriesContainer() -> false
    watchProgressMs == -1L -> true
    watchProgressMs <= 0L -> false
    runtimeMs <= 0L -> false
    watchProgressMs >= (runtimeMs * 95 / 100) -> true
    else -> false
}

fun ContentItem.primaryAvailabilityBadge(): String? = when {
    availability == Availability.FREEVEE -> "Freevee"
    availability == Availability.LIVE -> "Live"
    availability == Availability.PRIME || isIncludedWithPrime() -> "Prime"
    else -> null
}

data class SubtitleTrack(
    val url: String,
    val languageCode: String,
    val type: String // "sdh", "regular", "forced"
)

data class AudioTrack(
    val displayName: String,
    val languageCode: String = "",
    val type: String = "",
    val index: String = "",
    val isOriginalLanguage: Boolean = false
) {
    fun isAudioDescription(): Boolean = displayName.contains("audio description", ignoreCase = true)
}

data class PlaybackInfo(
    val manifestUrl: String,
    val licenseUrl: String,
    val asin: String,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList(),
    val bifUrl: String = ""  // BIF trickplay file URL from trickplayUrls in GetPlaybackResources
) {
    val hasThumbnails: Boolean get() = bifUrl.isNotEmpty()
}
