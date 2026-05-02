package com.mobilevidedit.app

import android.content.Context
import android.os.Build
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlin.io.path.createTempFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles all FFmpeg/FFprobe operations.
 *
 * Every public method is safe to call from a background coroutine (it blocks
 * until the FFmpeg session completes) and returns a [ProcessingState] value
 * that the ViewModel posts to the UI.
 */
class VideoProcessor(private val context: Context) {

    // ── Output directory ──────────────────────────────────────────────────────

    private fun outputDir(): File {
        val dir = File(context.getExternalFilesDir(null), "VideoEdit")
        dir.mkdirs()
        return dir
    }

    private fun newOutputFile(suffix: String = "", extension: String = "mp4"): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(outputDir(), "videdit_${ts}${suffix}.${extension}")
    }

    // ── Probe ─────────────────────────────────────────────────────────────────

    /**
     * Return a human-readable string with basic video information obtained
     * from FFprobe.  Never throws – returns an error message on failure.
     */
    fun probeVideo(path: String): String {
        return try {
            val session = FFprobeKit.execute(
                "-v error -show_entries format=duration,bit_rate " +
                "-show_entries stream=width,height,r_frame_rate,codec_name " +
                "-of default=noprint_wrappers=1 \"$path\""
            )
            val output = session.allLogsAsString ?: ""
            formatProbeOutput(output, path)
        } catch (e: Exception) {
            context.getString(R.string.error_probe, e.message)
        }
    }

    private fun formatProbeOutput(raw: String, path: String): String {
        val lines = raw.lines()
        fun find(key: String) = lines.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")?.trim() ?: "?"

        val codec = find("codec_name")
        val width = find("width")
        val height = find("height")
        val fps = find("r_frame_rate").let { r ->
            if (r.contains('/')) {
                val parts = r.split('/')
                val num = parts[0].toDoubleOrNull()
                val den = parts[1].toDoubleOrNull()
                if (num != null && den != null && den != 0.0)
                    String.format("%.2f", num / den)
                else r
            } else r
        }
        val bitrate = find("bit_rate").let { br ->
            val bps = br.toLongOrNull()
            if (bps != null) "${bps / 1000} kbps" else br
        }
        val durationSec = find("duration").toDoubleOrNull()
        val durationStr = if (durationSec != null) formatDuration(durationSec) else "?"

        return buildString {
            appendLine("Plik: ${File(path).name}")
            appendLine("Kodek: $codec")
            appendLine("Rozdzielczość: ${width}x${height}")
            appendLine("FPS: $fps")
            appendLine("Bitrate: $bitrate")
            append("Duration: $durationStr")
        }
    }

    private fun formatDuration(sec: Double): String {
        val h = (sec / 3600).toInt()
        val m = ((sec % 3600) / 60).toInt()
        val s = sec % 60
        return String.format("%02d:%02d:%06.3f", h, m, s)
    }

    // ── Process (transcode / crop / trim) ─────────────────────────────────────

    /**
     * Apply the given [params] to [srcPath] and write the result to a new file.
     *
     * Returns [ProcessingState.Success] with the output path or
     * [ProcessingState.Error] with a description on failure.
     */
    fun processVideo(srcPath: String, params: VideoProcessParams): ProcessingState {
        // Podwójna walidacja po stronie logiki biznesowej chroni przed
        // uruchomieniem FFmpeg z błędnym zakresem czasu (np. gdy UI zostanie
        // pominięte lub dane wejściowe będą pochodziły z innego źródła).
        if (params.trimStart < 0.0) {
            return ProcessingState.Error(
                "Nieprawidłowy zakres przycinania: początek (trimStart) nie może być mniejszy niż 0 sekund."
            )
        }
        if (params.trimEnd != null && params.trimEnd <= params.trimStart) {
            return ProcessingState.Error(
                "Nieprawidłowy zakres przycinania: koniec (trimEnd) musi być większy niż początek (trimStart)."
            )
        }

        val output = newOutputFile(extension = params.format)

        val args = buildFFmpegArgs(srcPath, params, output.absolutePath)

        return try {
            val session = FFmpegKit.execute(args)
            if (ReturnCode.isSuccess(session.returnCode)) {
                ProcessingState.Success(output.absolutePath)
            } else {
                val logs = session.allLogsAsString ?: context.getString(R.string.error_unknown)
                ProcessingState.Error(context.getString(R.string.error_ffmpeg, logs))
            }
        } catch (e: Exception) {
            ProcessingState.Error(context.getString(R.string.error_ffmpeg_exception, e.message))
        }
    }

    /**
     * Concatenate [path1] and [path2] (re-encoding to ensure compatibility)
     * and write the result to a new file.
     */
    fun mergeVideos(path1: String, path2: String): ProcessingState {
        val output = newOutputFile("_merged")

        // Unikalna nazwa pliku eliminuje kolizje przy kilku sesjach przetwarzania.
        val uniqueSuffix = "_${System.currentTimeMillis()}_${(1000..9999).random()}"
        val listFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createTempFile(
                directory = context.cacheDir.toPath(),
                prefix = "concat_list${uniqueSuffix}_",
                suffix = ".txt"
            ).toFile()
        } else {
            TODO("VERSION.SDK_INT < O")
        }
        listFile.writeText("file '${path1.replace("'", "'\\''")}'\nfile '${path2.replace("'", "'\\''")}'\n")
        val args = "-f concat -safe 0 -i \"${listFile.absolutePath}\" -c:v libx264 -c:a aac -movflags +faststart \"${output.absolutePath}\""

        return try {
            val session = FFmpegKit.execute(args)
            if (ReturnCode.isSuccess(session.returnCode)) {
                ProcessingState.Success(output.absolutePath)
            } else {
                val logs = session.allLogsAsString ?: context.getString(R.string.error_unknown)
                ProcessingState.Error(context.getString(R.string.error_ffmpeg_merge, logs))
            }
        } catch (e: Exception) {
            ProcessingState.Error(context.getString(R.string.error_merge_exception, e.message))
        } finally {
            listFile.delete()
        }
    }

    // ── FFmpeg argument builder ───────────────────────────────────────────────

    /**
     * Build the complete FFmpeg command-line string for a single-clip transcode.
     *
     * Filter chain precedence:
     *   1. crop (spatial)
     *   2. scale (resolution)
     * Temporal trim is implemented via `-ss` / `-to` input flags (fast seek).
     */
    private fun buildFFmpegArgs(
        srcPath: String,
        params: VideoProcessParams,
        outputPath: String
    ): String {
        val sb = StringBuilder()

        // ── Input with optional seek ──────────────────────────────────────────
        if (params.trimStart > 0) {
            sb.append("-ss ${params.trimStart} ")
        }
        sb.append("-i \"$srcPath\" ")
        if (params.trimEnd != null) {
            sb.append("-to ${params.trimEnd - params.trimStart} ")
        }

        // ── Video codec ───────────────────────────────────────────────────────
        sb.append("-c:v libx264 ")

        // ── Bitrate ───────────────────────────────────────────────────────────
        val bitrateValue = parseBitrateValue(params.bitrate)
        if (bitrateValue != null) {
            sb.append("-b:v $bitrateValue ")
        }

        // ── FPS ───────────────────────────────────────────────────────────────
        val fpsValue = parseFpsValue(params.fps)
        if (fpsValue != null) {
            sb.append("-r $fpsValue ")
        }

        // ── Video filters (crop + scale) ──────────────────────────────────────
        val filters = mutableListOf<String>()

        val doCrop = params.cropWidth != null && params.cropHeight != null
        if (doCrop) {
            filters += "crop=${params.cropWidth}:${params.cropHeight}:${params.cropX}:${params.cropY}"
        }

        val scaleValue = parseResolutionFilter(params.resolution)
        if (scaleValue != null) {
            filters += scaleValue
        }

        if (filters.isNotEmpty()) {
            sb.append("-vf \"${filters.joinToString(",")}\" ")
        }

        // ── Audio ─────────────────────────────────────────────────────────────
        if (params.removeAudio) {
            sb.append("-an ")
        } else {
            // Re-encode to AAC for compatibility
            sb.append("-c:a aac ")
        }

        // ── Output ────────────────────────────────────────────────────────────
        sb.append("-movflags +faststart ")
        sb.append("\"$outputPath\"")

        return sb.toString()
    }

    // ── Value parsers ─────────────────────────────────────────────────────────

    /**
     * Parse the bitrate option string and return a value suitable for `-b:v`,
     * e.g. `"5000k"`, or null if the user chose "Keep original".
     */
    private fun parseBitrateValue(option: String): String? {
        if (option.startsWith("Oryginalny") || option.startsWith("Keep")) return null
        val match = Regex("""^(\d+k)""").find(option.trim()) ?: return null
        return match.groupValues[1]
    }

    /**
     * Parse the FPS option string and return the frame-rate number, or null if
     * the user chose "Keep original".
     */
    private fun parseFpsValue(option: String): Int? {
        if (option.startsWith("Oryginalny") || option.startsWith("Keep")) return null
        return option.trim().toIntOrNull()
    }

    /**
     * Parse the resolution option string and return a `scale=` filter string,
     * e.g. `"scale=1920:1080"`, or null if "Original" was chosen.
     */
    private fun parseResolutionFilter(option: String): String? {
        if (option.startsWith("Oryginalna") || option.startsWith("Original")) return null
        val match = Regex("""(\d+)x(\d+)""").find(option) ?: return null
        val (w, h) = match.destructured
        // Use -2 trick to keep divisibility by 2 even after crop
        return "scale=${w}:${h}:force_original_aspect_ratio=decrease,pad=${w}:${h}:(ow-iw)/2:(oh-ih)/2"
    }
}
