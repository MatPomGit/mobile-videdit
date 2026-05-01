package com.mobilevidedit.app

import android.content.Context
import android.net.Uri
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
            context.contentResolver.openInputStream(uri)?.use { input ->
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

    private fun getExtensionFromUri(context: Context, uri: Uri): String? {
        val mimeType = context.contentResolver.getType(uri) ?: return null
        return when (mimeType) {
            "video/mp4" -> "mp4"
            "video/x-matroska" -> "mkv"
            "video/webm" -> "webm"
            "video/3gpp" -> "3gp"
            "video/quicktime" -> "mov"
            "video/x-msvideo" -> "avi"
            else -> "mp4"
        }
    }
}
