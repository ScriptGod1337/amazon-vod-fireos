package com.scriptgod.fireos.avod.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import com.scriptgod.fireos.avod.model.AudioTrack

/**
 * Pure string-formatting and classification functions for audio tracks.
 * No state, no metadata list — every function is a pure transformation of its inputs.
 */
@UnstableApi
object AudioTrackLabeler {

    val CHANNEL_SUFFIX_REGEX = Regex("""\s+\d\.\d(\s*(surround|atmos))?""", RegexOption.IGNORE_CASE)

    private fun audioSignalText(vararg parts: String?): String =
        parts.joinToString(" ") { it.orEmpty() }.lowercase()

    private fun dialogueBoostFamilyKind(source: String): String? = when {
        source.contains("dialogue boost: high") -> "boost-high"
        source.contains("dialogue boost: medium") -> "boost-medium"
        source.contains("dialogue boost") -> "boost"
        else -> null
    }

    private fun hasAudioDescriptionSignal(
        metadataType: String?,
        metadataName: String?,
        label: String
    ): Boolean {
        val metadataNameValue = metadataName.orEmpty()
        val source = audioSignalText(metadataNameValue, metadataType, label)
        return metadataType.equals("descriptive", ignoreCase = true) ||
            metadataNameValue.contains("audio description", ignoreCase = true) ||
            metadataNameValue.contains("[AD]", ignoreCase = true) ||
            source.contains("audio description") ||
            source.contains("described video") ||
            Regex("""\[AD]""", RegexOption.IGNORE_CASE).containsMatchIn(source)
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
        val source = audioSignalText(metadata?.displayName, metadataType, label)
        return when {
            hasAudioDescriptionSignal(metadataType, metadata?.displayName, label) -> "ad"
            dialogueBoostFamilyKind(source) == "boost-high" -> "boost-high"
            dialogueBoostFamilyKind(source) == "boost-medium" -> "boost-medium"
            dialogueBoostFamilyKind(source) == "boost" -> "boost"
            else -> "main"
        }
    }

    fun resolveAudioFamilyKind(
        format: Format,
        metadata: AudioTrack?,
        rawLabel: String
    ): String {
        val metadataType = metadata?.type.orEmpty()
        val source = audioSignalText(metadata?.displayName, metadataType, rawLabel, format.label)
        return when {
            isAudioDescriptionTrack(format, metadata?.displayName) ||
                hasAudioDescriptionSignal(metadataType, metadata?.displayName, rawLabel) -> "ad"
            dialogueBoostFamilyKind(source) == "boost-high" -> "boost-high"
            dialogueBoostFamilyKind(source) == "boost-medium" -> "boost-medium"
            dialogueBoostFamilyKind(source) == "boost" -> "boost"
            else -> "main"
        }
    }

    fun isAudioDescriptionTrack(format: Format, metadataName: String? = null): Boolean {
        // Check live format role flags first — set when MPD contains <Role value="description">
        // (injected by MpdTimingCorrector for audioTrackId="*_descriptive" AdaptationSets).
        if ((format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) return true
        return hasAudioDescriptionSignal(
            metadataType = null,
            metadataName = metadataName,
            label = format.label.orEmpty()
        )
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

    fun buildSubtitleLabel(langCode: String, type: String): String {
        val lang = java.util.Locale.forLanguageTag(langCode).displayLanguage
        return when (type) {
            "sdh" -> "$lang [SDH]"
            "forced" -> "$lang [Forced]"
            else -> lang
        }
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

    fun normalizeLanguageCode(languageCode: String?): String {
        if (languageCode.isNullOrBlank()) return ""
        return languageCode.replace('_', '-').lowercase()
    }

    fun normalizeAudioGroupLanguage(languageCode: String?): String {
        if (languageCode.isNullOrBlank()) return ""
        return normalizeLanguageCode(languageCode).substringBefore('-')
    }

    fun buildAudioFamilyKey(normalizedLanguage: String, metadata: AudioTrack?, rawLabel: String): String =
        "$normalizedLanguage|${audioFamilyKind(metadata, rawLabel)}"
}
