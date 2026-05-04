package com.mobilevidedit.app

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.FFmpegSession
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch

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

    private var currentSessionId: Long? = null

    /**
     * Cancel the currently running FFmpeg/FFprobe session.
     */
    fun cancelAnyActiveSession() {
        currentSessionId?.let {
            FFmpegKit.cancel(it)
            currentSessionId = null
        }
    }

    // ── Probe ─────────────────────────────────────────────────────────────────

    /**
     * Probe video file and return structured metadata.
     * Returns null on failure.
     */
    fun probeVideo(path: String): VideoMetadata? {
        return try {
            val session = FFprobeKit.execute(
                "-v error -show_entries format=duration,bit_rate " +
                "-show_entries stream=width,height,r_frame_rate,codec_name,codec_type " +
                "-of default=noprint_wrappers=1 \"$path\""
            )
            val output = session.allLogsAsString ?: ""
            parseProbeOutput(output, path)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseProbeOutput(raw: String, path: String): VideoMetadata? {
        val lines = raw.lines()
        
        var videoCodec: String? = null
        var audioCodec: String? = null
        var width = 0
        var height = 0
        var fps = 0.0
        var bitrateKbps = 0L
        var durationSec = 0.0

        var currentType: String? = null
        
        for (line in lines) {
            val key = line.substringBefore("=").trim()
            val value = line.substringAfter("=", "").trim()
            
            when (key) {
                "codec_type" -> currentType = value
                "codec_name" -> {
                    if (currentType == "video") videoCodec = value
                    else if (currentType == "audio") audioCodec = value
                }
                "width" -> if (currentType == "video") width = value.toIntOrNull() ?: 0
                "height" -> if (currentType == "video") height = value.toIntOrNull() ?: 0
                "r_frame_rate" -> if (currentType == "video") {
                    fps = if (value.contains('/')) {
                        val parts = value.split('/')
                        val num = parts[0].toDoubleOrNull()
                        val den = parts[1].toDoubleOrNull()
                        if (num != null && den != null && den != 0.0) num / den else 0.0
                    } else value.toDoubleOrNull() ?: 0.0
                }
                "bit_rate" -> if (bitrateKbps == 0L) bitrateKbps = (value.toLongOrNull() ?: 0L) / 1000
                "duration" -> if (durationSec == 0.0) durationSec = value.toDoubleOrNull() ?: 0.0
            }
        }

        return videoCodec?.let {
            VideoMetadata(
                fileName = File(path).name,
                videoCodec = it,
                audioCodec = audioCodec,
                width = width,
                height = height,
                fps = fps,
                bitrateKbps = bitrateKbps,
                durationSec = durationSec
            )
        }
    }

    /**
     * Clear all temporary files in cache and app-specific video directory.
     */
    fun clearTemporaryFiles() {
        try {
            outputDir().listFiles()?.forEach { it.delete() }
            context.cacheDir.listFiles()?.forEach { 
                if (it.name.startsWith("video1_src") || it.name.startsWith("video2_src") || it.name.contains("concat_list")) {
                    it.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── Process (transcode / crop / trim) ─────────────────────────────────────

    /**
     * Apply the given [params] to [srcPath] and write the result to a new file.
     */
    fun processVideo(
        srcPath: String,
        params: VideoProcessParams,
        totalDurationMs: Long,
        onProgress: (Int) -> Unit
    ): ProcessingState {
        if (params.trimStart < 0.0) {
            return ProcessingState.Error("Nieprawidłowy zakres przycinania (początek < 0).")
        }
        if (params.trimEnd != null && params.trimEnd <= params.trimStart) {
            return ProcessingState.Error("Nieprawidłowy zakres przycinania (koniec <= początek).")
        }

        val output = newOutputFile(extension = params.format)
        val args = buildFFmpegArgs(srcPath, params, output.absolutePath)

        val targetDurationMs = if (params.trimEnd != null) {
            ((params.trimEnd - params.trimStart) * 1000).toLong()
        } else {
            totalDurationMs - (params.trimStart * 1000).toLong()
        }

        return runFFmpeg(args, targetDurationMs, onProgress, output.absolutePath)
    }

    /**
     * Concatenate [path1] and [path2].
     */
    fun mergeVideos(
        path1: String,
        path2: String,
        totalDurationMs: Long,
        useStreamCopy: Boolean = false,
        onProgress: (Int) -> Unit
    ): ProcessingState {
        val output = newOutputFile("_merged")
        val uniqueSuffix = "_${System.currentTimeMillis()}"
        val listFile = File.createTempFile("concat_list${uniqueSuffix}_", ".txt", context.cacheDir)
        listFile.writeText("file '${path1.replace("'", "'\\''")}'\nfile '${path2.replace("'", "'\\''")}'\n")
        
        val codecArgs = if (useStreamCopy) "-c copy" else "-c:v h264_mediacodec -c:a aac"
        val args = "-f concat -safe 0 -i \"${listFile.absolutePath}\" $codecArgs -movflags +faststart \"${output.absolutePath}\""

        return try {
            runFFmpeg(args, totalDurationMs, onProgress, output.absolutePath)
        } finally {
            listFile.delete()
        }
    }

    /**
     * Helper to run FFmpeg asynchronously while blocking the current thread,
     * allowing for cancellation via [currentSessionId].
     */
    private fun runFFmpeg(
        args: String,
        targetDurationMs: Long,
        onProgress: (Int) -> Unit,
        outputPath: String
    ): ProcessingState {
        val latch = CountDownLatch(1)
        var sessionResult: FFmpegSession? = null

        FFmpegKitConfig.enableStatisticsCallback { stats ->
            if (stats.time > 0 && targetDurationMs > 0) {
                val progress = (stats.time * 100 / targetDurationMs).toInt().coerceIn(0, 100)
                onProgress(progress)
            }
        }

        val session = FFmpegKit.executeAsync(args) { s ->
            sessionResult = s
            latch.countDown()
        }
        
        currentSessionId = session.sessionId
        
        return try {
            latch.await()
            FFmpegKitConfig.enableStatisticsCallback(null)
            currentSessionId = null
            
            val completedSession = sessionResult ?: return ProcessingState.Error("Błąd sesji FFmpeg")
            val returnCode = completedSession.returnCode
            
            if (ReturnCode.isSuccess(returnCode)) {
                ProcessingState.Success(outputPath)
            } else if (ReturnCode.isCancel(returnCode)) {
                ProcessingState.Idle
            } else {
                val logs = completedSession.allLogsAsString ?: context.getString(R.string.error_unknown)
                ProcessingState.Error(context.getString(R.string.error_ffmpeg, logs))
            }
        } catch (e: Exception) {
            currentSessionId = null
            ProcessingState.Error(context.getString(R.string.error_ffmpeg_exception, e.message))
        }
    }

    /**
     * Generate a single preview frame with filters applied.
     */
    fun generatePreviewFrame(
        srcPath: String,
        params: VideoProcessParams,
        atTimeSec: Double
    ): String? {
        val output = File(context.cacheDir, "preview_frame.jpg")
        if (output.exists()) output.delete()

        val filters = mutableListOf<String>()
        if (params.cropWidth != null && params.cropHeight != null) {
            filters += "crop=${params.cropWidth}:${params.cropHeight}:${params.cropX}:${params.cropY}"
        }
        parseResolutionFilter(params.resolution)?.let { filters += it }
        if (params.grayscale) {
            filters += "format=gray"
        }
        if (params.brightness != 0.0f || params.contrast != 1.0f) {
            filters += "eq=brightness=${params.brightness}:contrast=${params.contrast}"
        }

        val filterArg = if (filters.isNotEmpty()) "-vf \"${filters.joinToString(",")}\" " else ""
        
        // Fast seek with -ss before -i, then accurate seek with -ss after -i if needed, 
        // but for preview frame -ss before -i is usually enough and much faster.
        val args = "-ss $atTimeSec -i \"$srcPath\" $filterArg -frames:v 1 -q:v 2 \"${output.absolutePath}\""
        
        val session = FFmpegKit.execute(args)
        return if (ReturnCode.isSuccess(session.returnCode)) {
            output.absolutePath
        } else {
            null
        }
    }

    // ── FFmpeg argument builder ───────────────────────────────────────────────


    private fun buildFFmpegArgs(
        srcPath: String,
        params: VideoProcessParams,
        outputPath: String
    ): String {
        val sb = StringBuilder()
        if (params.trimStart > 0) sb.append("-ss ${params.trimStart} ")
        sb.append("-i \"$srcPath\" ")
        if (params.trimEnd != null) sb.append("-to ${params.trimEnd - params.trimStart} ")

        sb.append("-c:v h264_mediacodec ")

        parseBitrateValue(params.bitrate)?.let { sb.append("-b:v $it ") }
        parseFpsValue(params.fps)?.let { sb.append("-r $it ") }

        val filters = mutableListOf<String>()
        if (params.cropWidth != null && params.cropHeight != null) {
            filters += "crop=${params.cropWidth}:${params.cropHeight}:${params.cropX}:${params.cropY}"
        }
        parseResolutionFilter(params.resolution)?.let { filters += it }
        if (params.grayscale) {
            filters += "format=gray"
        }
        if (params.brightness != 0.0f || params.contrast != 1.0f) {
            filters += "eq=brightness=${params.brightness}:contrast=${params.contrast}"
        }
        if (filters.isNotEmpty()) sb.append("-vf \"${filters.joinToString(",")}\" ")

        if (params.removeAudio) sb.append("-an ") else sb.append("-c:a aac ")

        sb.append("-movflags +faststart \"$outputPath\"")
        return sb.toString()
    }

    private fun parseBitrateValue(option: String): String? {
        if (option.startsWith("Oryginalny") || option.startsWith("Keep")) return null
        return Regex("""^(\d+k)""").find(option.trim())?.groupValues?.get(1)
    }

    private fun parseFpsValue(option: String): Int? {
        if (option.startsWith("Oryginalny") || option.startsWith("Keep")) return null
        return option.trim().toIntOrNull()
    }

    private fun parseResolutionFilter(option: String): String? {
        if (option.startsWith("Oryginalna") || option.startsWith("Original")) return null
        val match = Regex("""(\d+)x(\d+)""").find(option) ?: return null
        val (w, h) = match.destructured
        return "scale=${w}:${h}:force_original_aspect_ratio=decrease,pad=${w}:${h}:(ow-iw)/2:(oh-ih)/2"
    }
}
