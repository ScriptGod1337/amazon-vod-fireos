package com.scriptgod.fireos.avod.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.scriptgod.fireos.avod.model.AudioTrack

@UnstableApi
class AudioTrackResolver(private var availableAudioTracks: List<AudioTrack>) {

    companion object {
        private const val TAG = "AudioTrackResolver"
        val CHANNEL_SUFFIX_REGEX = Regex("""\s+\d\.\d(\s*(surround|atmos))?""", RegexOption.IGNORE_CASE)
    }

    data class TrackOption(
        val group: Tracks.Group,
        val groupIndex: Int,
        val trackIndex: Int,
        val label: String,
        val familyKind: String,
        val isSelected: Boolean,
        val isAudioDescription: Boolean,
        val bitrate: Int,
        val language: String = ""
    )

    data class AudioLiveCandidate(
        val option: TrackOption,
        val normalizedLanguage: String,
        val rawLabel: String,
        val channelCount: Int
    )

    data class AudioResolvedOption(
        val option: TrackOption,
        val familyKey: String
    )

    data class AudioMetadataFamily(
        val familyKind: String,
        val label: String,
        val isAudioDescription: Boolean
    )

    var lastLoggedAudioTrackSignature: String = ""
    var lastLoggedAudioResolutionSignature: String = ""

    fun updateAvailableTracks(tracks: List<AudioTrack>) {
        availableAudioTracks = tracks
    }

    fun buildTrackOptions(trackType: Int, tracks: Tracks): List<TrackOption> {
        if (trackType == C.TRACK_TYPE_AUDIO) {
            return buildAudioTrackOptions(tracks)
        }

        val groups = tracks.groups.filter { it.type == trackType }
        val options = mutableListOf<TrackOption>()

        groups.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                val metadataName = if (trackType == C.TRACK_TYPE_AUDIO) {
                    resolveAudioMetadataName(format, isAudioDescriptionTrack(format))
                } else null
                val isAd = trackType == C.TRACK_TYPE_AUDIO && isAudioDescriptionTrack(format, metadataName)
                if (trackType == C.TRACK_TYPE_AUDIO) {
                    val normalizedLanguage = normalizeAudioGroupLanguage(format.language)
                    val rawLabel = format.label?.trim().orEmpty()
                    val metadata = metadataName?.let { AudioTrack(displayName = it) }
                    val familyKind = resolveAudioFamilyKind(format, metadata, rawLabel)
                    val label = resolveAudioMenuLabel(
                        normalizedLanguage = normalizedLanguage,
                        metadata = metadata,
                        rawLabel = rawLabel,
                        fallbackLanguage = normalizedLanguage,
                        channelCount = format.channelCount,
                        familyKind = familyKind
                    )
                    options += TrackOption(
                        group = group,
                        groupIndex = groupIndex,
                        trackIndex = trackIndex,
                        label = label,
                        familyKind = familyKind,
                        isSelected = group.isTrackSelected(trackIndex),
                        isAudioDescription = isAd,
                        bitrate = format.bitrate
                    )
                } else {
                    val label = buildTrackLabel(trackType, format, isAd, metadataName)
                    options += TrackOption(
                        group = group,
                        groupIndex = groupIndex,
                        trackIndex = trackIndex,
                        label = label,
                        familyKind = "text",
                        isSelected = group.isTrackSelected(trackIndex),
                        isAudioDescription = isAd,
                        bitrate = format.bitrate
                    )
                }
            }
        }

        val bestByLabel = linkedMapOf<String, TrackOption>()
        for (option in options) {
            val key = if (trackType == C.TRACK_TYPE_AUDIO) {
                audioMenuKey(option.label, option.isAudioDescription)
            } else {
                option.label
            }
            val existing = bestByLabel[key]
            val better = existing == null ||
                (option.isSelected && !existing.isSelected) ||
                (!existing.isSelected && option.bitrate > existing.bitrate)
            if (better) {
                bestByLabel[key] = option
            }
        }

        return bestByLabel.values.sortedWith(
            compareBy<TrackOption> { if (it.isAudioDescription) 1 else 0 }
                .thenBy { it.label.lowercase() }
        )
    }

    fun buildAudioTrackOptions(tracks: Tracks): List<TrackOption> {
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val resolved = mutableListOf<TrackOption>()
        val resolutionEntries = mutableListOf<String>()
        val languageVariantCounts = mutableMapOf<String, Int>()
        groups.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                val normalizedLanguage = normalizeAudioGroupLanguage(format.language)
                val rawLabel = format.label?.trim().orEmpty()
                // Use MPD Role flag (set by MpdTimingCorrector for _descriptive AdaptationSets)
                // as the authoritative AD signal before metadata lookup.
                val mpdIsAd = (format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0
                // Keep AD and non-AD ordinals independent so MPD track ordering (e.g.
                // [dialog, descriptive, boost-medium]) doesn't shift the non-AD counter.
                val variantKey = "$normalizedLanguage|${format.channelCount}|${if (mpdIsAd) "ad" else "main"}"
                val variantOrdinal = languageVariantCounts[variantKey] ?: 0
                languageVariantCounts[variantKey] = variantOrdinal + 1
                val metadata = resolveAudioOptionMetadata(
                    normalizedLanguage = normalizedLanguage,
                    rawLabel = rawLabel,
                    trackIndex = trackIndex,
                    variantOrdinal = variantOrdinal,
                    mpdIsAd = mpdIsAd
                )
                val isAd = isAudioDescriptionTrack(format, metadata?.displayName)
                val familyKind = when {
                    isAd -> "ad"
                    else -> resolveAudioFamilyKind(format, metadata, rawLabel)
                }
                val label = resolveAudioMenuLabel(
                    normalizedLanguage = normalizedLanguage,
                    metadata = metadata,
                    rawLabel = rawLabel,
                    fallbackLanguage = normalizedLanguage,
                    channelCount = format.channelCount,
                    familyKind = familyKind
                )
                resolved += TrackOption(
                    group = group,
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    label = label,
                    familyKind = familyKind,
                    isSelected = group.isTrackSelected(trackIndex),
                    isAudioDescription = familyKind == "ad",
                    bitrate = format.bitrate,
                    language = normalizedLanguage
                )
                resolutionEntries += "group=$groupIndex track=$trackIndex raw=${rawLabel.ifBlank { "-" }} lang=${format.language.orEmpty()} role=${format.roleFlags} channels=${format.channelCount} metadata=${metadata?.displayName ?: "-"} type=${metadata?.type ?: "-"} index=${metadata?.index ?: "-"} family=$familyKind label=$label selected=${group.isTrackSelected(trackIndex)}"
            }
        }

        val resolutionSignature = resolutionEntries.joinToString(" || ")
        if (resolutionSignature != lastLoggedAudioResolutionSignature) {
            lastLoggedAudioResolutionSignature = resolutionSignature
            Log.i(TAG, "Audio track resolution: ${resolutionEntries.joinToString(" | ")}")
        }

        val bestByLabel = linkedMapOf<String, TrackOption>()
        for (option in resolved) {
            val key = option.label
                .replace("\\s+".toRegex(), " ")
                .trim()
                .lowercase()
            val existing = bestByLabel[key]
            val better = existing == null ||
                (option.isSelected && !existing.isSelected) ||
                (!existing.isSelected && option.bitrate > existing.bitrate)
            if (better) {
                bestByLabel[key] = option
            }
        }

        val sorted = bestByLabel.values.sortedWith(
            compareBy<TrackOption> { it.language }
                .thenBy { if (it.isAudioDescription) 1 else 0 }
                .thenBy { it.label.lowercase() }
        )

        Log.i(TAG, "Audio menu options: ${
            sorted.joinToString(" | ") {
                "label=${it.label}, selected=${it.isSelected}, group=${it.groupIndex}, track=${it.trackIndex}"
            }
        }")
        return sorted
    }

    fun buildSubtitleLabel(langCode: String, type: String): String {
        val lang = java.util.Locale.forLanguageTag(langCode).displayLanguage
        return when (type) {
            "sdh" -> "$lang [SDH]"
            "forced" -> "$lang [Forced]"
            else -> lang
        }
    }

    fun resolveAudioOptionMetadata(
        normalizedLanguage: String,
        rawLabel: String,
        trackIndex: Int,
        variantOrdinal: Int,
        mpdIsAd: Boolean = false
    ): AudioTrack? {
        if (availableAudioTracks.isEmpty()) return null

        val directMatch = availableAudioTracks.firstOrNull {
            it.displayName.equals(rawLabel, ignoreCase = true)
        }
        if (directMatch != null) return directMatch

        val languageMatches = availableAudioTracks.filter {
            normalizeAudioGroupLanguage(it.languageCode) == normalizedLanguage
        }
        if (languageMatches.isEmpty()) return null

        // If the MPD authoritatively identifies this track's AD status, filter metadata
        // to only match the correct family (descriptive vs. non-descriptive).
        val typedByMpd = if (mpdIsAd) {
            languageMatches.filter { it.type.equals("descriptive", ignoreCase = true) }
                .ifEmpty { languageMatches }
        } else {
            languageMatches.filter { !it.type.equals("descriptive", ignoreCase = true) }
                .ifEmpty { languageMatches }
        }

        val requestedFamily = audioFamilyKind(null, rawLabel)
        val typedMatches = if (requestedFamily == "main") {
            typedByMpd
        } else {
            typedByMpd.filter { audioFamilyKind(it, it.displayName) == requestedFamily }
        }
        val candidates = typedMatches.ifEmpty { typedByMpd }

        val orderedByFamily = candidates
            .sortedWith(
                compareBy<AudioTrack> { audioFamilyRank(audioFamilyKind(it, it.displayName)) }
                    .thenBy { it.displayName.length }
            )
            .distinctBy { audioFamilyKind(it, it.displayName) }

        return orderedByFamily.getOrNull(variantOrdinal)
            ?: orderedByFamily.firstOrNull()
            ?: candidates.minWithOrNull(compareBy<AudioTrack> { it.displayName.length })
    }

    fun metadataFamiliesForLanguage(normalizedLanguage: String): List<AudioMetadataFamily> {
        return availableAudioTracks
            .filter { normalizeAudioGroupLanguage(it.languageCode) == normalizedLanguage }
            .map {
                val familyKind = audioFamilyKind(it, it.displayName)
                AudioMetadataFamily(
                    familyKind = familyKind,
                    label = it.displayName.replace("\\s+".toRegex(), " ").trim(),
                    isAudioDescription = familyKind == "ad"
                )
            }
            .distinctBy { it.familyKind }
            .sortedBy { audioFamilyRank(it.familyKind) }
    }

    fun audioFamilyRank(kind: String): Int = when (kind) {
        "main" -> 0
        "boost-medium" -> 1
        "boost-high" -> 2
        "boost" -> 3
        "ad" -> 4
        else -> 5
    }

    fun audioFamilyKind(metadata: AudioTrack?, label: String): String {
        val metadataType = metadata?.type.orEmpty()
        val source = listOf(metadata?.displayName.orEmpty(), metadataType, label)
            .joinToString(" ")
            .lowercase()
        return when {
            metadataType.equals("descriptive", ignoreCase = true) ||
                source.contains("audio description") -> "ad"
            source.contains("dialogue boost: high") -> "boost-high"
            source.contains("dialogue boost: medium") -> "boost-medium"
            source.contains("dialogue boost") -> "boost"
            else -> "main"
        }
    }

    fun resolveAudioFamilyKind(
        format: Format,
        metadata: AudioTrack?,
        rawLabel: String
    ): String {
        val metadataType = metadata?.type.orEmpty()
        val source = listOf(
            metadata?.displayName.orEmpty(),
            metadataType,
            rawLabel,
            format.label.orEmpty()
        ).joinToString(" ").lowercase()
        return when {
            isAudioDescriptionTrack(format, metadata?.displayName) ||
                metadataType.equals("descriptive", ignoreCase = true) ||
                source.contains("audio description") -> "ad"
            source.contains("dialogue boost: high") -> "boost-high"
            source.contains("dialogue boost: medium") -> "boost-medium"
            source.contains("dialogue boost") -> "boost"
            else -> "main"
        }
    }

    fun resolveAudioMenuLabel(
        normalizedLanguage: String,
        metadata: AudioTrack?,
        rawLabel: String,
        fallbackLanguage: String,
        channelCount: Int,
        familyKind: String
    ): String {
        val baseLanguage = displayLanguage(fallbackLanguage.ifBlank { normalizedLanguage })
        val metadataLabel = metadata?.displayName?.replace("\\s+".toRegex(), " ")?.trim().orEmpty()
        val liveLabel = rawLabel.replace("\\s+".toRegex(), " ").trim()
        val resolved = when (familyKind) {
            "main" -> when {
                CHANNEL_SUFFIX_REGEX.containsMatchIn(liveLabel) -> liveLabel
                metadataLabel.isNotBlank() -> metadataLabel
                liveLabel.isNotBlank() -> liveLabel
                else -> baseLanguage
            }
            "ad" -> when {
                metadataLabel.isNotBlank() -> metadataLabel
                liveLabel.isNotBlank() -> liveLabel
                else -> "$baseLanguage [Audio Description]"
            }
            "boost-high" -> when {
                metadataLabel.isNotBlank() -> "$metadataLabel [Dialogue Boost: High]"
                liveLabel.isNotBlank() -> "$liveLabel [Dialogue Boost: High]"
                else -> "$baseLanguage [Dialogue Boost: High]"
            }
            "boost-medium" -> when {
                metadataLabel.isNotBlank() -> "$metadataLabel [Dialogue Boost: Medium]"
                liveLabel.isNotBlank() -> "$liveLabel [Dialogue Boost: Medium]"
                else -> "$baseLanguage [Dialogue Boost: Medium]"
            }
            "boost" -> when {
                metadataLabel.isNotBlank() -> "$metadataLabel [Dialogue Boost]"
                liveLabel.isNotBlank() -> "$liveLabel [Dialogue Boost]"
                else -> "$baseLanguage [Dialogue Boost]"
            }
            else -> when {
                metadataLabel.isNotBlank() -> metadataLabel
                liveLabel.isNotBlank() -> liveLabel
                else -> baseLanguage
            }
        }
        return appendChannelLayout(resolved, channelCount)
    }

    fun decorateAudioLabel(
        liveLabel: String,
        metadataLabel: String,
        familyKind: String,
        normalizedLanguage: String,
        channelCount: Int
    ): String {
        val cleanedLive = liveLabel.replace("\\s+".toRegex(), " ").trim()
        val base = when {
            familyKind == "main" && CHANNEL_SUFFIX_REGEX.containsMatchIn(cleanedLive) -> cleanedLive
            metadataLabel.isNotBlank() -> metadataLabel
            cleanedLive.isNotBlank() -> cleanedLive
            else -> displayLanguage(normalizedLanguage)
        }
        return appendChannelLayout(base, channelCount)
    }

    fun fallbackAudioLabel(candidate: AudioLiveCandidate, normalizedLanguage: String): String {
        val cleanedLive = candidate.rawLabel.replace("\\s+".toRegex(), " ").trim()
        val base = cleanedLive.ifBlank { displayLanguage(normalizedLanguage) }
        return appendChannelLayout(base, candidate.channelCount)
    }

    fun buildTrackLabel(trackType: Int, format: Format, isAudioDescription: Boolean, metadataName: String? = null): String {
        val language = format.language
            ?.let { java.util.Locale.forLanguageTag(it).displayLanguage }
            ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
            ?: "Unknown"

        if (trackType == C.TRACK_TYPE_AUDIO) {
            if (!metadataName.isNullOrBlank()) return metadataName
            val label = format.label?.trim().orEmpty()
            if (label.isNotBlank()) {
                return if (isAudioDescription && !label.contains("AD", ignoreCase = true)) {
                    "$label [AD]"
                } else label
            }
            return if (isAudioDescription) "$language [Audio Description]" else language
        }

        val label = format.label?.trim().orEmpty()
        if (label.isNotBlank()) return label
        val flags = mutableListOf<String>()
        if ((format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) flags += "Forced"
        if ((format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0) flags += "SDH"
        return listOf(language, flags.joinToString(" ")).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun resolveAudioMetadataName(
        format: Format,
        guessedAd: Boolean
    ): String? {
        if (availableAudioTracks.isEmpty()) return null
        val normalizedLanguage = normalizeLanguageCode(format.language)
        val directLabel = format.label?.trim().orEmpty()
        if (directLabel.isNotBlank()) {
            availableAudioTracks.firstOrNull { it.displayName.equals(directLabel, ignoreCase = true) }
                ?.let { return it.displayName }
        }

        val languageMatches = availableAudioTracks.filter {
            normalizeLanguageCode(it.languageCode) == normalizedLanguage
        }

        if (languageMatches.size == 1) return languageMatches.first().displayName

        if (languageMatches.isNotEmpty()) {
            val preferredMatches = if (guessedAd) {
                languageMatches.filter {
                    it.isAudioDescription() || it.type.equals("descriptive", ignoreCase = true)
                }
            } else {
                languageMatches.filter {
                    !it.isAudioDescription() &&
                        it.type.equals("dialog", ignoreCase = true)
                }.ifEmpty {
                    languageMatches.filterNot {
                        it.isAudioDescription()
                    }
                }
            }
            if (preferredMatches.size == 1) return preferredMatches.first().displayName
            if (directLabel.isNotBlank()) {
                preferredMatches.firstOrNull {
                    it.displayName.contains(directLabel, ignoreCase = true)
                }?.let { return it.displayName }
            }
        }

        if (directLabel.isNotBlank()) {
            availableAudioTracks.firstOrNull {
                it.displayName.contains(directLabel, ignoreCase = true)
            }?.let { return it.displayName }
        }
        return null
    }

    fun normalizeLanguageCode(languageCode: String?): String {
        if (languageCode.isNullOrBlank()) return ""
        return languageCode.replace('_', '-').lowercase()
    }

    fun normalizeAudioGroupLanguage(languageCode: String?): String {
        if (languageCode.isNullOrBlank()) return ""
        return normalizeLanguageCode(languageCode).substringBefore('-')
    }

    fun appendChannelLayout(label: String, channelCount: Int): String {
        if (channelCount <= 0) return label
        if (CHANNEL_SUFFIX_REGEX.containsMatchIn(label)) return label
        val suffix = when (channelCount) {
            1 -> "1.0"
            2 -> "2.0"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${channelCount}.0"
        }
        return "$label $suffix"
    }

    fun displayLanguage(normalizedLanguage: String): String =
        normalizedLanguage
            .takeIf { it.isNotBlank() }
            ?.let { java.util.Locale.forLanguageTag(it).displayLanguage }
            ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
            ?: "Unknown"

    fun isDialogueBoostLabel(label: String): Boolean =
        label.contains("dialogue boost", ignoreCase = true)

    fun audioMenuKey(label: String, isAudioDescription: Boolean): String {
        val normalized = label
            .replace("[Audio Description]", "", ignoreCase = true)
            .replace("[Dialogue Boost: High]", "", ignoreCase = true)
            .replace("[Dialogue Boost: Medium]", "", ignoreCase = true)
            .replace("[Dialogue Boost]", "", ignoreCase = true)
            .replace("[AD]", "", ignoreCase = true)
            .replace(CHANNEL_SUFFIX_REGEX, "")
            .trim()
            .lowercase()
        return "$normalized|${if (isAudioDescription) "ad" else "main"}"
    }

    fun isAudioDescriptionTrack(format: Format, metadataName: String? = null): Boolean {
        // Check live format role flags first — set when MPD contains <Role value="description">
        // (injected by MpdTimingCorrector for audioTrackId="*_descriptive" AdaptationSets).
        if ((format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) return true
        if (!metadataName.isNullOrBlank()) {
            return metadataName.contains("audio description", ignoreCase = true) ||
                metadataName.contains("[AD]", ignoreCase = true)
        }
        val label = format.label.orEmpty()
        return label.contains("audio description", ignoreCase = true) ||
            label.contains("described video", ignoreCase = true) ||
            Regex("""\[AD]""", RegexOption.IGNORE_CASE).containsMatchIn(label)
    }

    fun buildAudioFamilyKey(normalizedLanguage: String, metadata: AudioTrack?, rawLabel: String): String {
        return "$normalizedLanguage|${audioFamilyKind(metadata, rawLabel)}"
    }

    fun getAvailableAudioTracks(): List<AudioTrack> = availableAudioTracks
}
