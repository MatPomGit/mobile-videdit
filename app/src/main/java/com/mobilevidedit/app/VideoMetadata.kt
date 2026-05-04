package com.mobilevidedit.app

import java.util.Locale

/**
 * Strukturalne informacje o pliku wideo pobrane przez FFprobe.
 */
data class VideoMetadata(
    val fileName: String,
    val videoCodec: String,
    val audioCodec: String?,
    val width: Int,
    val height: Int,
    val fps: Double,
    val bitrateKbps: Long,
    val durationSec: Double
) {
    val resolution: String get() = "${width}x${height}"

    /**
     * Sprawdza, czy dwa pliki wideo mają identyczne parametry strumieni,
     * co pozwala na szybkie łączenie bez re-kodowania (Stream Copy).
     */
    fun isCompatibleForStreamCopy(other: VideoMetadata): Boolean {
        return videoCodec == other.videoCodec &&
                audioCodec == other.audioCodec &&
                width == other.width &&
                height == other.height &&
                Math.abs(fps - other.fps) < 0.01
    }

    /**
     * Zwraca czytelny dla użytkownika opis (używane w UI).
     */
    fun formatDisplayInfo(): String {
        return buildString {
            appendLine("Plik: $fileName")
            appendLine("Kodek wideo: $videoCodec")
            audioCodec?.let { appendLine("Kodek audio: $it") }
            appendLine("Rozdzielczość: $resolution")
            appendLine("FPS: ${String.format(Locale.US, "%.2f", fps)}")
            appendLine("Bitrate: $bitrateKbps kbps")
            append("Czas trwania: ${formatDuration(durationSec)}")
        }
    }

    private fun formatDuration(sec: Double): String {
        val h = (sec / 3600).toInt()
        val m = ((sec % 3600) / 60).toInt()
        val s = sec % 60
        return String.format(Locale.US, "%02d:%02d:%06.3f", h, m, s)
    }
}
