package com.scriptgod.fireos.avod.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

@UnstableApi
object VideoFormatLabeler {

    data class FormatLabels(val videoLabel: String, val audioLabel: String)

    fun computeFormatLabels(
        videoFormat: Format?,
        currentVideoBitrateKbps: Int,
        currentAudioBitrateKbps: Int,
        currentAudioChannelCount: Int
    ): FormatLabels {
        if (videoFormat == null) return FormatLabels("", "")

        val fmt = videoFormat
        val res = when (fmt.height) {
            in 2160..Int.MAX_VALUE -> "4K"
            1080                   -> "1080p"
            720                    -> "720p"
            0                      -> ""
            else                   -> "${fmt.height}p"
        }
        val codec = when {
            fmt.sampleMimeType?.contains("hevc", ignoreCase = true) == true -> "H265"
            fmt.sampleMimeType?.contains("avc",  ignoreCase = true) == true -> "H264"
            else -> ""
        }
        val codecs = fmt.codecs ?: ""
        val hdr = when {
            fmt.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            fmt.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG    -> "HLG"
            codecs.startsWith("dvhe") || codecs.startsWith("dvav")  -> "DV"
            codecs.startsWith("hvc1.2") || codecs.startsWith("hev1.2") -> "HDR10"
            else -> ""
        }
        val vBitrate = if (currentVideoBitrateKbps > 0) " · ${currentVideoBitrateKbps}k" else ""
        val videoLabel = listOf(res, codec, hdr).filter { it.isNotEmpty() }.joinToString(" · ") + vBitrate

        val channels = when (currentAudioChannelCount) {
            1 -> "1.0"
            2 -> "2.0"
            6 -> "5.1"
            8 -> "7.1"
            else -> if (currentAudioChannelCount > 0) "${currentAudioChannelCount}ch" else ""
        }
        val audioParts = listOf(channels, if (currentAudioBitrateKbps > 0) "${currentAudioBitrateKbps}k" else "").filter { it.isNotEmpty() }
        val audioLabel = audioParts.joinToString(" · ")

        return FormatLabels(videoLabel, audioLabel)
    }

    fun playbackStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    fun formatSummary(format: Format?): String {
        if (format == null) return "none"
        val label = format.label?.takeIf { it.isNotBlank() } ?: "no-label"
        val mime = format.sampleMimeType ?: "no-mime"
        val codecs = format.codecs ?: "no-codecs"
        val lang = format.language ?: "und"
        return "label=$label lang=$lang mime=$mime codecs=$codecs channels=${format.channelCount} bitrate=${format.bitrate}"
    }
}
