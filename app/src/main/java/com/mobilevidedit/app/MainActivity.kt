package com.mobilevidedit.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.mobilevidedit.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: VideoEditorViewModel by viewModels()
    private var player: ExoPlayer? = null

    // Which slot is being loaded: 1 = primary video, 2 = secondary video for merge
    private var loadingSlot = 1

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                if (loadingSlot == 1) {
                    viewModel.setVideo1(it, this)
                } else {
                    viewModel.setVideo2(it, this)
                }
            }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val videoGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                permissions[Manifest.permission.READ_MEDIA_VIDEO] == true
            else
                permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
            
            if (videoGranted) {
                pickVideoLauncher.launch("video/*")
            } else {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }

        setupDropdowns()
        setupClickListeners()
        setupParameterListeners()
        observeViewModel()
    }

    // ── Toolbar menu ──────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_load -> {
                loadingSlot = 1
                requestVideoPermissionAndPick()
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Setup ─────────────────────────────────────────────────────────────────
    private fun setupDropdowns() {
        // Resolution options
        val resolutions = listOf(
            getString(R.string.original),
            "3840x2160 (4K Landscape)",
            "1920x1080 (Full HD Landscape)",
            "1280x720 (HD Landscape)",
            "854x480 (480p Landscape)",
            "640x360 (360p Landscape)",
            "426x240 (240p Landscape)",
            "2160x3840 (4K Portrait)",
            "1080x1920 (Full HD Portrait)",
            "720x1280 (HD Portrait)",
            "480x854 (480p Portrait)",
            "360x640 (360p Portrait)",
            "240x426 (240p Portrait)"
        )
        binding.spinnerResolution.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, resolutions)
        )
        binding.spinnerResolution.setText(resolutions[0], false)

        // FPS options
        val fpsList = listOf(
            getString(R.string.keep_original),
            "60", "50", "30", "25", "24", "15", "10"
        )
        binding.spinnerFps.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, fpsList)
        )
        binding.spinnerFps.setText(fpsList[0], false)

        // Bitrate options
        val bitrateList = listOf(
            getString(R.string.keep_original),
            "8000k (8 Mbps)",
            "5000k (5 Mbps)",
            "2500k (2.5 Mbps)",
            "1500k (1.5 Mbps)",
            "800k (800 kbps)",
            "400k (400 kbps)"
        )
        binding.spinnerBitrate.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, bitrateList)
        )
        binding.spinnerBitrate.setText(bitrateList[0], false)

        // Format options
        val formats = listOf("mp4", "mkv", "mov", "avi")
        binding.spinnerFormat.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, formats)
        )
        binding.spinnerFormat.setText(formats[0], false)
    }

    private fun setupParameterListeners() {
        binding.spinnerResolution.setOnItemClickListener { _, _, _, _ -> 
            updateEstimatedSize()
            triggerPreviewUpdate()
        }
        binding.spinnerFps.setOnItemClickListener { _, _, _, _ -> 
            updateEstimatedSize() 
        }
        binding.spinnerBitrate.setOnItemClickListener { _, _, _, _ -> 
            updateEstimatedSize() 
        }
        binding.spinnerFormat.setOnItemClickListener { _, _, _, _ -> 
            updateEstimatedSize() 
        }
        
        binding.rangeSliderTrim.addOnChangeListener { slider, _, fromUser ->
            if (fromUser) {
                val values = slider.values
                val start = values[0]
                val end = values[1]
                binding.etTrimStart.setText(String.format(Locale.US, "%.2f", start))
                binding.etTrimEnd.setText(String.format(Locale.US, "%.2f", end))
                updateEstimatedSize()
                triggerPreviewUpdate()
            }
        }

        binding.etTrimStart.doAfterTextChanged { 
            val start = it.toString().toDoubleOrNull() ?: 0.0
            val end = binding.etTrimEnd.text.toString().toDoubleOrNull() ?: (viewModel.videoDurationSec.value ?: 0.0)
            if (start < end) {
                binding.rangeSliderTrim.setValues(start.toFloat(), end.toFloat())
            }
            updateEstimatedSize() 
            triggerPreviewUpdate()
        }
        binding.etTrimEnd.doAfterTextChanged { 
            val start = binding.etTrimStart.text.toString().toDoubleOrNull() ?: 0.0
            val end = it.toString().toDoubleOrNull() ?: (viewModel.videoDurationSec.value ?: 0.0)
            if (start < end) {
                binding.rangeSliderTrim.setValues(start.toFloat(), end.toFloat())
            }
            updateEstimatedSize() 
        }
        binding.cbRemoveAudio.setOnCheckedChangeListener { _, _ -> updateEstimatedSize() }
        binding.cbGrayscale.setOnCheckedChangeListener { _, _ -> 
            updateEstimatedSize()
            triggerPreviewUpdate()
        }
        binding.sliderBrightness.addOnChangeListener { _, _, _ -> 
            updateEstimatedSize()
            triggerPreviewUpdate()
        }
        binding.sliderContrast.addOnChangeListener { _, _, _ -> 
            updateEstimatedSize()
            triggerPreviewUpdate()
        }
    }

    private fun setupClickListeners() {
        binding.btnLoadVideoPlaceholder.setOnClickListener {
            loadingSlot = 1
            requestVideoPermissionAndPick()
        }
        binding.btnLoadVideo1.setOnClickListener {
            loadingSlot = 1
            requestVideoPermissionAndPick()
        }
        binding.btnLoadVideo2.setOnClickListener {
            loadingSlot = 2
            requestVideoPermissionAndPick()
        }
        binding.btnProcess.setOnClickListener {
            processVideo()
        }
        binding.btnMerge.setOnClickListener {
            mergeVideos()
        }
        binding.btnQuickTrim.setOnClickListener {
            quickTrim()
        }
        binding.btnQuickCompress.setOnClickListener {
            quickCompress()
        }
        binding.btnCancel.setOnClickListener {
            viewModel.cancelProcessing()
        }
    }

    // ── ViewModel observation ─────────────────────────────────────────────────
    private fun observeViewModel() {
        val editorViews = with(binding) {
            listOf(
                playerView, cardVideoInfo, cardEditSettings, cardFilters,
                cardCropSettings, cardTrimSettings, cardMerge, btnProcess,
                cardQuickActions
            )
        }

        viewModel.video1Metadata.observe(this) { metadata ->
            val hasVideo = metadata != null
            if (metadata != null) {
                // We still need the URI for the player, but metadata is for UI
                viewModel.video1Uri.value?.let { showVideoInPlayer(it) }
            }

            binding.placeholderLayout.isVisible = !hasVideo
            editorViews.forEach { it.isVisible = hasVideo }
        }

        viewModel.videoInfo.observe(this) { info ->
            binding.tvVideoInfo.text = info
            updateEstimatedSize()
        }

        viewModel.video1Path.observe(this) { path ->
            binding.tvVideo1Path.text = path?.let {
                getString(R.string.video_1_label, it.substringAfterLast('/'))
            } ?: getString(R.string.video_1_not_loaded)
        }

        viewModel.video2Path.observe(this) { path ->
            binding.tvVideo2Path.text = path?.let {
                getString(R.string.video_2_label, it.substringAfterLast('/'))
            } ?: getString(R.string.video_2_not_loaded)
        }

        viewModel.videoDurationSec.observe(this) { duration ->
            if (duration > 0) {
                binding.tvDuration.text = getString(R.string.duration_label, duration)
                
                binding.rangeSliderTrim.valueFrom = 0.0f
                binding.rangeSliderTrim.valueTo = duration.toFloat()
                binding.rangeSliderTrim.setValues(0.0f, duration.toFloat())

                if (binding.etTrimEnd.text.isNullOrEmpty()) {
                    binding.etTrimEnd.setText(String.format(Locale.US, "%.2f", duration))
                }
                updateEstimatedSize()
                triggerPreviewUpdate()
            }
        }

        viewModel.previewFramePath.observe(this) { path ->
            if (path != null) {
                binding.ivPreviewFrame.setImageURI(Uri.fromFile(java.io.File(path)))
                binding.ivPreviewFrame.isVisible = true
                binding.playerView.isVisible = false
            } else {
                binding.ivPreviewFrame.isVisible = false
                binding.playerView.isVisible = true
            }
        }

        viewModel.processingState.observe(this) { state ->
            state is ProcessingState.Processing
            
            when (state) {
                is ProcessingState.Processing -> {
                    updateProcessingUi(true, state.progress)
                    binding.tvProgressStatus.text = if (state.progress >= 0) {
                        "${state.message} (${state.progress}%)"
                    } else {
                        state.message
                    }
                }
                is ProcessingState.Success -> {
                    updateProcessingUi(false)
                    showSuccessDialog(state.outputPath)
                }
                is ProcessingState.Error -> {
                    updateProcessingUi(false)
                    showErrorDialog(state.message)
                }
                is ProcessingState.Idle -> {
                    updateProcessingUi(false)
                }
            }
        }
    }

    private fun updateProcessingUi(isProcessing: Boolean, progress: Int = -1) {
        binding.layoutProgress.isVisible = isProcessing
        binding.btnProcess.isEnabled = !isProcessing
        binding.btnMerge.isEnabled = !isProcessing
        
        if (isProcessing) {
            // Apply processing status color
            binding.tvProgressStatus.setTextColor(ContextCompat.getColor(this, R.color.status_processing))
            binding.layoutProgress.strokeColor = ContextCompat.getColor(this, R.color.status_processing)
            binding.progressBar.setIndicatorColor(ContextCompat.getColor(this, R.color.status_processing))

            if (progress >= 0) {
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = progress
            } else {
                binding.progressBar.isIndeterminate = true
            }
        }
    }

    private fun updateEstimatedSize() {
        // ... (omitted for brevity, assume unchanged logic for size calculation)
    }

    private var previewJob: kotlinx.coroutines.Job? = null
    private fun triggerPreviewUpdate() {
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(300) // Debounce
            
            val resolution = binding.spinnerResolution.text.toString()
            val cropWidth = binding.etCropWidth.text.toString().trim().toIntOrNull()
            val cropHeight = binding.etCropHeight.text.toString().trim().toIntOrNull()
            val cropX = binding.etCropX.text.toString().trim().toIntOrNull() ?: 0
            val cropY = binding.etCropY.text.toString().trim().toIntOrNull() ?: 0
            val grayscale = binding.cbGrayscale.isChecked
            val brightness = binding.sliderBrightness.value
            val contrast = binding.sliderContrast.value
            val atTimeSec = binding.etTrimStart.text.toString().toDoubleOrNull() ?: 0.0

            val params = VideoProcessParams(
                resolution = resolution,
                fps = "Keep original",
                bitrate = "Keep original",
                cropWidth = cropWidth,
                cropHeight = cropHeight,
                cropX = cropX,
                cropY = cropY,
                grayscale = grayscale,
                brightness = brightness,
                contrast = contrast
            )
            viewModel.updatePreview(params, atTimeSec)
        }
    }

    // ── Player ────────────────────────────────────────────────────────────────

    private fun showVideoInPlayer(uri: Uri) {
        player?.release()
        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.prepare()
        }
    }

    // ── Processing ────────────────────────────────────────────────────────────
    private fun processVideo() {
        val exportMode = if (binding.rbExportReplace.isChecked) {
            ExportMode.REPLACE_ORIGINAL
        } else {
            ExportMode.NEW_FILE
        }

        if (exportMode == ExportMode.REPLACE_ORIGINAL) {
            AlertDialog.Builder(this)
                .setTitle(R.string.confirm_replace_title)
                .setMessage(R.string.confirm_replace_message)
                .setPositiveButton(R.string.ok) { _, _ -> executeProcessVideo(exportMode) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            executeProcessVideo(exportMode)
        }
    }

    private fun executeProcessVideo(exportMode: ExportMode) {
        val resolution = binding.spinnerResolution.text.toString()
        val fps = binding.spinnerFps.text.toString()
        val bitrate = binding.spinnerBitrate.text.toString()
        val format = binding.spinnerFormat.text.toString()
        val removeAudio = binding.cbRemoveAudio.isChecked
        val cropWidth = binding.etCropWidth.text.toString().trim().toIntOrNull()
        val cropHeight = binding.etCropHeight.text.toString().trim().toIntOrNull()
        val cropX = binding.etCropX.text.toString().trim().toIntOrNull() ?: 0
        val cropY = binding.etCropY.text.toString().trim().toIntOrNull() ?: 0
        val trimStart = binding.etTrimStart.text.toString().trim().toDoubleOrNull() ?: 0.0
        val trimEnd = binding.etTrimEnd.text.toString().trim().toDoubleOrNull()
        
        val grayscale = binding.cbGrayscale.isChecked
        val brightness = binding.sliderBrightness.value
        val contrast = binding.sliderContrast.value

        // Walidacja musi być wykonana przed budową argumentów FFmpeg, aby uniknąć
        // przekazania niepoprawnego zakresu czasu i błędów wykonania komendy.
        if (trimStart < 0.0) {
            showErrorDialog(getString(R.string.error_trim_negative))
            return
        }
        if (trimEnd != null && trimEnd <= trimStart) {
            showErrorDialog(getString(R.string.error_trim_invalid_range))
            return
        }

        val params = VideoProcessParams(
            resolution = resolution,
            fps = fps,
            bitrate = bitrate,
            cropWidth = cropWidth,
            cropHeight = cropHeight,
            cropX = cropX,
            cropY = cropY,
            trimStart = trimStart,
            trimEnd = trimEnd,
            format = format,
            removeAudio = removeAudio,
            exportMode = exportMode,
            grayscale = grayscale,
            brightness = brightness,
            contrast = contrast
        )

        lifecycleScope.launch {
            viewModel.processVideo(params)
        }
    }

    private fun quickTrim() {
        val duration = viewModel.videoDurationSec.value ?: 0.0
        if (duration <= 4.0) {
            showErrorDialog("Wideo jest zbyt krótkie (poniżej 4s)")
            return
        }

        val params = VideoProcessParams(
            resolution = getString(R.string.original),
            fps = getString(R.string.keep_original),
            bitrate = getString(R.string.keep_original),
            cropWidth = null,
            cropHeight = null,
            trimStart = 0.0,
            trimEnd = duration - 4.0,
            exportMode = ExportMode.NEW_FILE
        )

        lifecycleScope.launch {
            viewModel.processVideo(params)
        }
    }

    private fun quickCompress() {
        val params = VideoProcessParams(
            resolution = "854x480 (480p Landscape)",
            fps = "25",
            bitrate = "800k (800 kbps)",
            cropWidth = null,
            cropHeight = null,
            exportMode = ExportMode.NEW_FILE
        )

        lifecycleScope.launch {
            viewModel.processVideo(params)
        }
    }

    private fun mergeVideos() {
        lifecycleScope.launch {
            viewModel.mergeVideos()
        }
    }

    // ── Permissions & picking ─────────────────────────────────────────────────
    private fun requestVideoPermissionAndPick() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        when {
            ContextCompat.checkSelfPermission(this, permission) ==
                    PackageManager.PERMISSION_GRANTED -> {
                pickVideoLauncher.launch("video/*")
            }
            else -> {
                requestPermissionLauncher.launch(arrayOf(permission))
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────
    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about)
            .setMessage(R.string.about_text)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showSuccessDialog(outputPath: String) {
        // Powiadomienie systemu o nowym pliku, aby pojawił się w galerii
        if (!outputPath.startsWith("content://")) {
            MediaScannerConnection.scanFile(this, arrayOf(outputPath), null, null)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.success_export, outputPath))
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.share_video) { _, _ ->
                shareFile(outputPath)
            }
            .show()
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.error)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun shareFile(path: String) {
        val file = java.io.File(path)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "${packageName}.provider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_chooser_title)))
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
