package com.mobilevidedit.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel that owns all mutable video-editor state and delegates heavy
 * FFmpeg work to [VideoProcessor].
 */
class VideoEditorViewModel(
    private val processorFactory: (Context) -> VideoProcessor = { context -> VideoProcessor(context) }
) : ViewModel() {

    // ── LiveData exposed to the UI ────────────────────────────────────────────

    private val _video1Uri = MutableLiveData<Uri?>(null)
    val video1Uri: LiveData<Uri?> = _video1Uri

    private val _video1Path = MutableLiveData<String?>(null)
    val video1Path: LiveData<String?> = _video1Path

    private val _video2Path = MutableLiveData<String?>(null)
    val video2Path: LiveData<String?> = _video2Path

    private val _videoInfo = MutableLiveData<String>("")
    val videoInfo: LiveData<String> = _videoInfo

    private val _videoDurationSec = MutableLiveData<Double>(0.0)
    val videoDurationSec: LiveData<Double> = _videoDurationSec

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
        viewModelScope.launch(Dispatchers.IO) {
            val path = UriUtils.copyUriToCache(context, uri, "video1_src")
            if (path != null) {
                appContext = context.applicationContext
                val proc = ensureProcessor(context) ?: return@launch
                val info = proc.probeVideo(path)
                val duration = parseDuration(info)
                withContext(Dispatchers.Main) {
                    _video1Uri.value = uri
                    _video1Path.value = path
                    _videoInfo.value = info
                    _videoDurationSec.value = duration
                }
            } else {
                withContext(Dispatchers.Main) {
                    // Brak jawnego błędu utrudnia diagnozę użytkownikowi.
                    _processingState.value =
                        ProcessingState.Error("Nie udało się odczytać lub skopiować pierwszego pliku video")
                }
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
                withContext(Dispatchers.Main) {
                    // Brak jawnego błędu utrudnia diagnozę użytkownikowi.
                    _processingState.value =
                        ProcessingState.Error("Nie udało się odczytać lub skopiować drugiego pliku video")
                }
            }
        }
    }

    /**
     * Apply [params] to the primary video using FFmpeg and emit a
     * [ProcessingState.Success] or [ProcessingState.Error] when done.
     */
    suspend fun processVideo(params: VideoProcessParams) {
        val srcPath = _video1Path.value
        if (srcPath == null) {
            _processingState.value = ProcessingState.Error("Brak wczytanego pliku video")
            return
        }
        val proc = ensureProcessor() ?: return
        _processingState.value = ProcessingState.Processing("Przetwarzanie…")
        withContext(Dispatchers.IO) {
            val result = proc.processVideo(srcPath, params)
            withContext(Dispatchers.Main) {
                _processingState.value = result
            }
        }
    }

    /**
     * Concatenate [video1Path] and [video2Path] with FFmpeg and emit the result.
     */
    suspend fun mergeVideos() {
        val path1 = _video1Path.value
        val path2 = _video2Path.value
        if (path1 == null || path2 == null) {
            _processingState.value =
                ProcessingState.Error("Wymagane są dwa pliki video do połączenia")
            return
        }
        val proc = ensureProcessor() ?: return
        _processingState.value = ProcessingState.Processing("Łączenie plików video…")
        withContext(Dispatchers.IO) {
            val result = proc.mergeVideos(path1, path2)
            withContext(Dispatchers.Main) {
                _processingState.value = result
            }
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
            _processingState.value =
                ProcessingState.Error("Nie można zainicjalizować procesora video: brak kontekstu aplikacji")
            return null
        }

        return try {
            processorFactory(safeContext).also { processor = it }
        } catch (error: Throwable) {
            _processingState.value =
                ProcessingState.Error("Nie udało się zainicjalizować procesora video")
            null
        }
    }

    /**
     * Parse a duration value (seconds) from the FFprobe-style info string
     * returned by [VideoProcessor.probeVideo].
     */
    private fun parseDuration(info: String): Double {
        val regex = Regex("""Duration:\s*(\d+):(\d+):([\d.]+)""")
        val match = regex.find(info) ?: return 0.0
        val (h, m, s) = match.destructured
        return h.toDouble() * 3600 + m.toDouble() * 60 + s.toDouble()
    }

    override fun onCleared() {
        super.onCleared()
        processor = null
    }
}
