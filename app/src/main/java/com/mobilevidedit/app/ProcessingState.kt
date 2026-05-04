package com.mobilevidedit.app

/**
 * Represents the current state of a video processing operation shown to the UI.
 */
sealed class ProcessingState {
    /** No processing is happening right now. */
    object Idle : ProcessingState()

    /** Processing is in progress; [message] is a short human-readable status, [progress] is 0-100. */
    data class Processing(val message: String, val progress: Int = -1) : ProcessingState()

    /** Processing finished successfully; [outputPath] is the file path of the result. */
    data class Success(val outputPath: String) : ProcessingState()

    /** Processing failed; [message] describes the error. */
    data class Error(val message: String) : ProcessingState()
}
