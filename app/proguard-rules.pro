# Add project specific ProGuard rules here.
# By default, the flags in this file are applied, plus the flags from the
# SDK's default proguard-android-optimize.txt.

# FFmpeg Kit
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }

# Keep our own classes
-keep class com.mobilevidedit.app.** { *; }
