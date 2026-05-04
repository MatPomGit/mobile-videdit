package com.mobilevidedit.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "video_processing_channel"
        const val NOTIFICATION_ID = 1001
        
        const val KEY_SRC_PATH = "src_path"
        const val KEY_SRC_URI = "src_uri"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS = "progress"
        
        // Params for processVideo
        const val KEY_RESOLUTION = "resolution"
        const val KEY_FPS = "fps"
        const val KEY_BITRATE = "bitrate"
        const val KEY_TRIM_START = "trim_start"
        const val KEY_TRIM_END = "trim_end"
        const val KEY_FORMAT = "format"
        const val KEY_REMOVE_AUDIO = "remove_audio"
        const val KEY_EXPORT_MODE = "export_mode"
        const val KEY_GRAYSCALE = "grayscale"
        const val KEY_BRIGHTNESS = "brightness"
        const val KEY_CONTRAST = "contrast"
        const val KEY_TOTAL_DURATION_MS = "total_duration_ms"
        
        // Mode selector
        const val KEY_OPERATION_TYPE = "operation_type"
        const val OP_PROCESS = "process"
        const val OP_MERGE = "merge"
        
        // Params for merge
        const val KEY_PATH1 = "path1"
        const val KEY_PATH2 = "path2"
        const val KEY_USE_STREAM_COPY = "use_stream_copy"
    }

    override suspend fun doWork(): Result {
        val operation = inputData.getString(KEY_OPERATION_TYPE) ?: return Result.failure()
        
        setForeground(createForegroundInfo(0))

        val processor = VideoProcessor(applicationContext)
        
        return withContext(Dispatchers.IO) {
            try {
                val state = when (operation) {
                    OP_PROCESS -> {
                        val srcPath = inputData.getString(KEY_SRC_PATH) ?: return@withContext Result.failure()
                        val totalDurationMs = inputData.getLong(KEY_TOTAL_DURATION_MS, 0L)
                        val params = VideoProcessParams(
                            resolution = inputData.getString(KEY_RESOLUTION) ?: "Original",
                            fps = inputData.getString(KEY_FPS) ?: "Keep original",
                            bitrate = inputData.getString(KEY_BITRATE) ?: "Keep original",
                            cropWidth = null, // simplified for now
                            cropHeight = null,
                            trimStart = inputData.getDouble(KEY_TRIM_START, 0.0),
                            trimEnd = if (inputData.keyValueMap.containsKey(KEY_TRIM_END)) inputData.getDouble(KEY_TRIM_END, 0.0) else null,
                            format = inputData.getString(KEY_FORMAT) ?: "mp4",
                            removeAudio = inputData.getBoolean(KEY_REMOVE_AUDIO, false),
                            exportMode = ExportMode.valueOf(inputData.getString(KEY_EXPORT_MODE) ?: ExportMode.NEW_FILE.name),
                            grayscale = inputData.getBoolean(KEY_GRAYSCALE, false),
                            brightness = inputData.getFloat(KEY_BRIGHTNESS, 0.0f),
                            contrast = inputData.getFloat(KEY_CONTRAST, 1.0f)
                        )
                        
                        processor.processVideo(srcPath, params, totalDurationMs) { progress ->
                            setProgressAsync(workDataOf(KEY_PROGRESS to progress))
                            notificationManager.notify(NOTIFICATION_ID, createNotification(progress))
                        }
                    }
                    OP_MERGE -> {
                        val path1 = inputData.getString(KEY_PATH1) ?: return@withContext Result.failure()
                        val path2 = inputData.getString(KEY_PATH2) ?: return@withContext Result.failure()
                        val totalDurationMs = inputData.getLong(KEY_TOTAL_DURATION_MS, 0L)
                        val useStreamCopy = inputData.getBoolean(KEY_USE_STREAM_COPY, false)
                        
                        processor.mergeVideos(path1, path2, totalDurationMs, useStreamCopy) { progress ->
                            setProgressAsync(workDataOf(KEY_PROGRESS to progress))
                            notificationManager.notify(NOTIFICATION_ID, createNotification(progress))
                        }
                    }
                    else -> ProcessingState.Error("Unknown operation")
                }

                when (state) {
                    is ProcessingState.Success -> {
                        Result.success(workDataOf(KEY_OUTPUT_PATH to state.outputPath))
                    }
                    is ProcessingState.Error -> {
                        Result.failure(workDataOf(KEY_ERROR to state.message))
                    }
                    else -> Result.failure()
                }
            } catch (e: Exception) {
                Result.failure(workDataOf(KEY_ERROR to e.message))
            } finally {
                processor.cancelAnyActiveSession()
            }
        }
    }

    private fun createForegroundInfo(progress: Int): ForegroundInfo {
        createNotificationChannel()
        return ForegroundInfo(NOTIFICATION_ID, createNotification(progress))
    }

    private fun createNotification(progress: Int) = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        .setContentTitle("Video Processing")
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setProgress(100, progress, false)
        .setOngoing(true)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Processing",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }
}
