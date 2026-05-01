# mobile-videdit

Android application for video editing written in Kotlin.

## Features

- **Load video** – pick any video file from device storage
- **Change resolution** – scale to 4K, Full HD, HD, 480p, 360p, 240p or keep original
- **Change FPS** – set frame-rate to 60/50/30/25/24/15/10 fps or keep original
- **Change bitrate** – choose from 8 Mbps down to 400 kbps or keep original
- **Spatial crop** – crop the geometric dimensions of the video frame (width, height, X/Y offset)
- **Temporal trim** – cut the recording on the timeline by specifying start/end times in seconds
- **Export** – save the processed video to device storage and share it
- **Merge two videos** – concatenate two video files into one

## Technology stack

- Kotlin + Android SDK (min API 24)
- [ExoPlayer / Media3](https://developer.android.com/media/media3/exoplayer) for in-app playback
- [FFmpeg Kit](https://github.com/arthenica/ffmpeg-kit) (`ffmpeg-kit-full`) for all transcoding operations
- Jetpack ViewModel + LiveData + Coroutines
- Material 3 UI components

## Building

1. Open the project in Android Studio (Electric Eel or newer recommended).
2. Let Gradle sync and download dependencies.
3. Connect an Android device or start an emulator (API 24+).
4. Press **Run** (▶).

## Architecture

```
MainActivity          – UI, permission handling, player lifecycle
VideoEditorViewModel  – state holder, orchestrates processing via coroutines
VideoProcessor        – wraps FFmpeg Kit; builds command-line args, runs sessions
UriUtils              – copies content URIs to cache files for FFmpeg access
VideoProcessParams    – data class for all edit parameters
ProcessingState       – sealed class: Idle / Processing / Success / Error
```
