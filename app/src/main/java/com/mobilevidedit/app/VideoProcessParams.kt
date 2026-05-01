package com.mobilevidedit.app

/**
 * Parameters for a video processing operation.
 *
 * @param resolution  Human-readable string such as "1920x1080 (Full HD)" or the
 *                    "Original" sentinel that means no scaling.
 * @param fps         Human-readable string such as "30" or the "Keep original"
 *                    sentinel meaning no re-sampling.
 * @param bitrate     Human-readable string such as "5000k (5 Mbps)" or the
 *                    "Keep original" sentinel.
 * @param cropWidth   Output width in pixels for the crop filter, or null to skip.
 * @param cropHeight  Output height in pixels for the crop filter, or null to skip.
 * @param cropX       Horizontal offset for the crop filter (default 0).
 * @param cropY       Vertical offset for the crop filter (default 0).
 * @param trimStart   Start time in seconds (default 0.0).
 * @param trimEnd     End time in seconds, or null to keep until the end.
 */
data class VideoProcessParams(
    val resolution: String,
    val fps: String,
    val bitrate: String,
    val cropWidth: Int?,
    val cropHeight: Int?,
    val cropX: Int = 0,
    val cropY: Int = 0,
    val trimStart: Double = 0.0,
    val trimEnd: Double? = null
)
