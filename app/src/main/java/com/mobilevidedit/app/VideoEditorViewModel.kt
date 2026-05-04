package com.mobilevidedit.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel that owns all mutable video-editor state and delegates heavy
 * FFmpeg work to [VideoProcessor].
 */
class VideoEditorViewModel(
    private val processorFactory: (Context) -> VideoProcessor = { context -> VideoProcessor(context) }
) : ViewModel() {

    private var workManager: WorkManager? = null
    private val WORK_NAME = "video_processing_work"

    // ── LiveData exposed to the UI ────────────────────────────────────────────

    private val _video1Metadata = MutableLiveData<VideoMetadata?>(null)
    val video1Metadata: LiveData<VideoMetadata?> = _video1Metadata

    private val _video1Uri = MutableLiveData<Uri?>(null)
    val video1Uri: LiveData<Uri?> = _video1Uri

    private val _video1Path = MutableLiveData<String?>(null)
    val video1Path: LiveData<String?> = _video1Path

    private val _video2Path = MutableLiveData<String?>(null)
    val video2Path: LiveData<String?> = _video2Path

    private val _videoInfo = MutableLiveData("")
    val videoInfo: LiveData<String> = _videoInfo

    private val _videoDurationSec = MutableLiveData(0.0)
    val videoDurationSec: LiveData<Double> = _videoDurationSec

    private val _previewFramePath = MutableLiveData<String?>(null)
    val previewFramePath: LiveData<String?> = _previewFramePath

    private val _processingState = MutableLiveData<ProcessingState>(ProcessingState.Idle)
    val processingState: LiveData<ProcessingState> = _processingState

    // ── Internal state ────────────────────────────────────────────────────────

    private var processor: VideoProcessor? = null
    private var appContext: Context? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Load the primary video (slot 1).  Copies the content-URI to an app-private
     * cache file so that FFmpeg can access it by plain file path.
     */
    fun setVideo1(uri: Uri, context: Context) {
        workManager = WorkManager.getInstance(context.applicationContext)
        viewModelScope.launch(Dispatchers.IO) {
            val path = UriUtils.copyUriToCache(context, uri, "video1_src")
            if (path != null) {
                appContext = context.applicationContext
                val proc = ensureProcessor(context) ?: return@launch
                val metadata = proc.probeVideo(path)
                if (metadata != null) {
                    withContext(Dispatchers.Main) {
                        _video1Uri.value = uri
                        _video1Path.value = path
                        _video1Metadata.value = metadata
                        _videoInfo.value = metadata.formatDisplayInfo()
                        _videoDurationSec.value = metadata.durationSec
                    }
                } else {
                    _processingState.postValue(
                        ProcessingState.Error("Nie udało się przeanalizować pliku wideo")
                    )
                }
            } else {
                _processingState.postValue(
                    ProcessingState.Error("Nie udało się odczytać lub skopiować pierwszego pliku video")
                )
            }
        }
    }

    /**
     * Load the secondary video (slot 2) used for the merge operation.
     */
    fun setVideo2(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            appContext = context.applicationContext
            val path = UriUtils.copyUriToCache(context, uri, "video2_src")
            if (path != null) {
                withContext(Dispatchers.Main) {
                    _video2Path.value = path
                }
            } else {
                _processingState.postValue(
                    ProcessingState.Error("Nie udało się odczytać lub skopiować drugiego pliku video")
                )
            }
        }
    }

    /**
     * Apply [params] to the primary video using FFmpeg and emit a
     * [ProcessingState.Success] or [ProcessingState.Error] when done.
     */
    fun processVideo(params: VideoProcessParams) {
        val srcPath = _video1Path.value
        val srcUri = _video1Uri.value
        val wm = workManager
        if (srcPath == null || srcUri == null || wm == null) {
            _processingState.value = ProcessingState.Error("Brak wczytanego pliku video lub WorkManager nieaktywny")
            return
        }
        val totalDurationMs = ((_videoDurationSec.value ?: 0.0) * 1000).toLong()

        val inputData = Data.Builder()
            .putString(VideoWorker.KEY_OPERATION_TYPE, VideoWorker.OP_PROCESS)
            .putString(VideoWorker.KEY_SRC_PATH, srcPath)
            .putString(VideoWorker.KEY_SRC_URI, srcUri.toString())
            .putLong(VideoWorker.KEY_TOTAL_DURATION_MS, totalDurationMs)
            .putString(VideoWorker.KEY_RESOLUTION, params.resolution)
            .putString(VideoWorker.KEY_FPS, params.fps)
            .putString(VideoWorker.KEY_BITRATE, params.bitrate)
            .putDouble(VideoWorker.KEY_TRIM_START, params.trimStart)
            .putString(VideoWorker.KEY_FORMAT, params.format)
            .putBoolean(VideoWorker.KEY_REMOVE_AUDIO, params.removeAudio)
            .putString(VideoWorker.KEY_EXPORT_MODE, params.exportMode.name)
        
        params.trimEnd?.let { inputData.putDouble(VideoWorker.KEY_TRIM_END, it) }

        val workRequest = OneTimeWorkRequestBuilder<VideoWorker>()
            .setInputData(inputData.build())
            .build()

        wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, workRequest)
        
        observeWork(workRequest.id, params.exportMode, srcUri)
    }

    /**
     * Concatenate [video1Path] and [video2Path] with FFmpeg and emit the result.
     */
    suspend fun mergeVideos() {
        val path1 = _video1Path.value
        val path2 = _video2Path.value
        val meta1 = _video1Metadata.value
        val wm = workManager
        
        if (path1 == null || path2 == null || meta1 == null || wm == null) {
            _processingState.value =
                ProcessingState.Error("Wymagane są dwa pliki video do połączenia")
            return
        }
        val proc = ensureProcessor() ?: return

        // Pobieramy metadane drugiego pliku, aby sprawdzić kompatybilność "Smart Merge"
        val meta2 = withContext(Dispatchers.IO) { proc.probeVideo(path2) }
        if (meta2 == null) {
            _processingState.value = ProcessingState.Error("Nie udało się przeanalizować drugiego pliku video")
            return
        }

        val totalDurationMs = ((meta1.durationSec + meta2.durationSec) * 1000).toLong()
        val canStreamCopy = meta1.isCompatibleForStreamCopy(meta2)

        val inputData = Data.Builder()
            .putString(VideoWorker.KEY_OPERATION_TYPE, VideoWorker.OP_MERGE)
            .putString(VideoWorker.KEY_PATH1, path1)
            .putString(VideoWorker.KEY_PATH2, path2)
            .putLong(VideoWorker.KEY_TOTAL_DURATION_MS, totalDurationMs)
            .putBoolean(VideoWorker.KEY_USE_STREAM_COPY, canStreamCopy)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<VideoWorker>()
            .setInputData(inputData)
            .build()

        wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, workRequest)
        
        observeWork(workRequest.id, ExportMode.NEW_FILE, null)
    }

    /**
     * Generate a preview frame with the given [params].
     */
    fun updatePreview(params: VideoProcessParams, atTimeSec: Double = 0.0) {
        val srcPath = _video1Path.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val proc = ensureProcessor() ?: return@launch
            val path = proc.generatePreviewFrame(srcPath, params, atTimeSec)
            _previewFramePath.postValue(path)
        }
    }

    private fun observeWork(workId: java.util.UUID, exportMode: ExportMode, srcUri: Uri?) {
        viewModelScope.launch {
            workManager?.getWorkInfoByIdFlow(workId)?.collectLatest { workInfo ->
                when (workInfo?.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getInt(VideoWorker.KEY_PROGRESS, 0)
                        _processingState.postValue(ProcessingState.Processing("Przetwarzanie w tle...", progress))
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val outputPath = workInfo.outputData.getString(VideoWorker.KEY_OUTPUT_PATH)
                        if (outputPath != null) {
                            if (exportMode == ExportMode.REPLACE_ORIGINAL && srcUri != null) {
                                handleReplaceOriginal(outputPath, srcUri)
                            } else {
                                _processingState.postValue(ProcessingState.Success(outputPath))
                            }
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        val error = workInfo.outputData.getString(VideoWorker.KEY_ERROR) ?: "Błąd zadania w tle"
                        _processingState.postValue(ProcessingState.Error(error))
                    }
                    WorkInfo.State.CANCELLED -> {
                        _processingState.postValue(ProcessingState.Idle)
                    }
                    else -> {}
                }
            }
        }
    }

    private suspend fun handleReplaceOriginal(outputPath: String, srcUri: Uri) {
        val context = appContext ?: return
        _processingState.postValue(ProcessingState.Processing("Zapisywanie do oryginału…", 100))
        val saved = withContext(Dispatchers.IO) {
            UriUtils.saveFileToUri(context, outputPath, srcUri)
        }
        if (saved) {
            _processingState.postValue(ProcessingState.Success(srcUri.toString()))
        } else {
            _processingState.postValue(ProcessingState.Error("Nie udało się nadpisać oryginalnego pliku"))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────


    /**
     * Zapewnia pojedynczy punkt inicjalizacji [VideoProcessor] wraz ze spójną
     * obsługą błędów. Rozdzielenie inicjalizacji od akcji użytkownika upraszcza
     * testy i utrzymanie, bo logika tworzenia zależności nie jest rozsiana po
     * metodach biznesowych.
     */
    private fun ensureProcessor(context: Context? = appContext): VideoProcessor? {
        processor?.let { return it }

        val safeContext = context?.applicationContext
        if (safeContext == null) {
            _processingState.postValue(
                ProcessingState.Error("Nie można zainicjalizować procesora video: brak kontekstu aplikacji")
            )
            return null
        }

        return try {
            processorFactory(safeContext).also { 
                processor = it
                it.clearTemporaryFiles()
            }
        } catch (error: Throwable) {
            _processingState.postValue(
                ProcessingState.Error("Nie udało się zainicjalizować procesora video")
            )
            null
        }
    }

    /**
     * Anuluje aktualnie trwające zadanie FFmpeg.
     */
    fun cancelProcessing() {
        workManager?.cancelUniqueWork(WORK_NAME)
        processor?.cancelAnyActiveSession()
        _processingState.value = ProcessingState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        processor?.cancelAnyActiveSession()
        processor?.clearTemporaryFiles()
        processor = null
    }
}
