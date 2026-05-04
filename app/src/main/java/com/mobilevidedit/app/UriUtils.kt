package com.mobilevidedit.app

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream

/**
 * Utility functions for working with Android content URIs.
 */
object UriUtils {

    /**
     * Copy the content at [uri] into the app's private cache directory under
     * the name `<baseName>_<timestamp>.mp4` and return the absolute path.
     *
     * Returns `null` on any IO error.
     */
    fun copyUriToCache(context: Context, uri: Uri, baseName: String): String? {
        return try {
            val ext = getExtensionFromUri(context, uri) ?: "mp4"
            val dest = File(context.cacheDir, "${baseName}_${System.currentTimeMillis()}.$ext")
            // Jeśli provider nie zwróci strumienia, zwracamy błąd zamiast pustej ścieżki.
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            dest.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Copy the content of the file at [filePath] back to the content [uri].
     * Returns true on success, false on error.
     */
    fun saveFileToUri(context: Context, filePath: String, uri: Uri): Boolean {
        return try {
            val srcFile = File(filePath)
            context.contentResolver.openOutputStream(uri, "rwt")?.use { output ->
                srcFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun getExtensionFromUri(context: Context, uri: Uri): String? {
        // Najpierw próbujemy MIME type z provider'a, bo bywa dokładniejszy.
        val mimeType = context.contentResolver.getType(uri)
        val mimeFromType = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        if (!mimeFromType.isNullOrBlank()) return mimeFromType

        // Fallback: spróbujmy odczytać rozszerzenie z samego URI (np. file://.../clip.m4v).
        val uriExt = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        if (!uriExt.isNullOrBlank()) return uriExt.lowercase()

        // Ostateczny fallback dla providerów zwracających niestandardowe MIME.
        if (mimeType == null) return null
        return when (mimeType) {
            "video/mp4" -> "mp4"
            "video/x-matroska" -> "mkv"
            "video/webm" -> "webm"
            "video/3gpp" -> "3gp"
            "video/quicktime" -> "mov"
            "video/x-msvideo" -> "avi"
            "video/x-m4v" -> "m4v"
            "video/mp2t" -> "ts"
            else -> "mp4"
        }
    }
}
