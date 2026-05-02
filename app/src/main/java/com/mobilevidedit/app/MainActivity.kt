package com.mobilevidedit.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
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
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
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
        binding.spinnerResolution.setOnItemClickListener { _, _, _, _ -> updateEstimatedSize() }
        binding.spinnerFps.setOnItemClickListener { _, _, _, _ -> updateEstimatedSize() }
        binding.spinnerBitrate.setOnItemClickListener { _, _, _, _ -> updateEstimatedSize() }
        binding.spinnerFormat.setOnItemClickListener { _, _, _, _ -> updateEstimatedSize() }
        binding.etTrimStart.doAfterTextChanged { updateEstimatedSize() }
        binding.etTrimEnd.doAfterTextChanged { updateEstimatedSize() }
        binding.cbRemoveAudio.setOnCheckedChangeListener { _, _ -> updateEstimatedSize() }
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
    }

    // ── ViewModel observation ─────────────────────────────────────────────────
    private fun observeViewModel() {
        val editorViews = with(binding) {
            listOf(
                playerView, cardVideoInfo, cardEditSettings,
                cardCropSettings, cardTrimSettings, cardMerge, btnProcess
            )
        }

        viewModel.video1Uri.observe(this) { uri ->
            val hasVideo = uri != null
            if (uri != null) showVideoInPlayer(uri)

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
                if (binding.etTrimEnd.text.isNullOrEmpty()) {
                    binding.etTrimEnd.setText("%.2f".format(duration))
                }
                updateEstimatedSize()
            }
        }

        viewModel.processingState.observe(this) { state ->
            val isProcessing = state is ProcessingState.Processing
            updateProcessingUi(isProcessing)

            when (state) {
                is ProcessingState.Processing -> {
                    binding.tvProgressStatus.text = state.message
                }
                is ProcessingState.Success -> {
                    showSuccessDialog(state.outputPath)
                }
                is ProcessingState.Error -> {
                    showErrorDialog(state.message)
                }
                is ProcessingState.Idle -> {}
            }
        }
    }

    private fun updateProcessingUi(isProcessing: Boolean) {
        binding.layoutProgress.isVisible = isProcessing
        binding.btnProcess.isEnabled = !isProcessing
        binding.btnMerge.isEnabled = !isProcessing
    }

    private fun updateEstimatedSize() {
        val duration = try {
            val start = binding.etTrimStart.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            val totalDuration = viewModel.videoDurationSec.value ?: 0.0
            val endStr = binding.etTrimEnd.text.toString().replace(',', '.')
            val end = if (endStr.isEmpty()) totalDuration else endStr.toDoubleOrNull() ?: totalDuration
            (end - start).coerceAtLeast(0.0)
        } catch (e: Exception) {
            0.0
        }

        val bitrateStr = binding.spinnerBitrate.text.toString()
        val bitrateKbps = if (bitrateStr == getString(R.string.keep_original)) {
            val info = viewModel.videoInfo.value ?: ""
            // Parse "Bitrate: 5000 kbps"
            val match = Regex("""Bitrate:\s*(\d+)\s*kbps""").find(info)
            match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        } else {
            // Parse "5000k (5 Mbps)"
            Regex("""^(\d+)k""").find(bitrateStr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        }

        if (bitrateKbps > 0 && duration > 0) {
            val videoSizeBits = bitrateKbps * 1000.0 * duration
            var totalSizeBytes = (videoSizeBits / 8.0).toLong()
            
            // If keeping audio, add a rough estimate for AAC (e.g., 128 kbps)
            if (!binding.cbRemoveAudio.isChecked) {
                totalSizeBytes += (128 * 1000.0 * duration / 8.0).toLong()
            }

            val sizeMb = totalSizeBytes / (1024.0 * 1024.0)
            binding.tvEstimatedSize.text = getString(
                R.string.estimated_size, 
                String.format(java.util.Locale.US, "%.1f MB", sizeMb)
            )
            binding.tvEstimatedSize.isVisible = true
        } else {
            binding.tvEstimatedSize.isVisible = false
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
            removeAudio = removeAudio
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
                requestPermissionLauncher.launch(permission)
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
