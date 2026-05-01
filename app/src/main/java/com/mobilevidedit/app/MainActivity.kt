package com.mobilevidedit.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
            "3840x2160 (4K)",
            "1920x1080 (Full HD)",
            "1280x720 (HD)",
            "854x480 (480p)",
            "640x360 (360p)",
            "426x240 (240p)"
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
        viewModel.video1Uri.observe(this) { uri ->
            if (uri != null) {
                showVideoInPlayer(uri)
                binding.placeholderLayout.visibility = View.GONE
                binding.playerView.visibility = View.VISIBLE
                binding.cardVideoInfo.visibility = View.VISIBLE
                binding.cardEditSettings.visibility = View.VISIBLE
                binding.cardCropSettings.visibility = View.VISIBLE
                binding.cardTrimSettings.visibility = View.VISIBLE
                binding.cardMerge.visibility = View.VISIBLE
                binding.btnProcess.visibility = View.VISIBLE
            } else {
                binding.placeholderLayout.visibility = View.VISIBLE
                binding.playerView.visibility = View.GONE
                binding.cardVideoInfo.visibility = View.GONE
                binding.cardEditSettings.visibility = View.GONE
                binding.cardCropSettings.visibility = View.GONE
                binding.cardTrimSettings.visibility = View.GONE
                binding.cardMerge.visibility = View.GONE
                binding.btnProcess.visibility = View.GONE
            }
        }

        viewModel.videoInfo.observe(this) { info ->
            binding.tvVideoInfo.text = info
        }

        viewModel.video1Path.observe(this) { path ->
            binding.tvVideo1Path.text = if (path != null)
                "Video 1: ${path.substringAfterLast('/')}"
            else
                getString(R.string.video_1_not_loaded)
        }

        viewModel.video2Path.observe(this) { path ->
            binding.tvVideo2Path.text = if (path != null)
                "Video 2: ${path.substringAfterLast('/')}"
            else
                getString(R.string.video_2_not_loaded)
        }

        viewModel.videoDurationSec.observe(this) { duration ->
            if (duration > 0) {
                binding.tvDuration.text = getString(R.string.duration_label, duration)
                if (binding.etTrimEnd.text.isNullOrEmpty()) {
                    binding.etTrimEnd.setText(String.format("%.2f", duration))
                }
            }
        }

        viewModel.processingState.observe(this) { state ->
            when (state) {
                is ProcessingState.Idle -> {
                    binding.layoutProgress.visibility = View.GONE
                    binding.btnProcess.isEnabled = true
                    binding.btnMerge.isEnabled = true
                }
                is ProcessingState.Processing -> {
                    binding.layoutProgress.visibility = View.VISIBLE
                    binding.tvProgressStatus.text = state.message
                    binding.btnProcess.isEnabled = false
                    binding.btnMerge.isEnabled = false
                }
                is ProcessingState.Success -> {
                    binding.layoutProgress.visibility = View.GONE
                    binding.btnProcess.isEnabled = true
                    binding.btnMerge.isEnabled = true
                    showSuccessDialog(state.outputPath)
                }
                is ProcessingState.Error -> {
                    binding.layoutProgress.visibility = View.GONE
                    binding.btnProcess.isEnabled = true
                    binding.btnMerge.isEnabled = true
                    showErrorDialog(state.message)
                }
            }
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
        val cropWidth = binding.etCropWidth.text.toString().trim().toIntOrNull()
        val cropHeight = binding.etCropHeight.text.toString().trim().toIntOrNull()
        val cropX = binding.etCropX.text.toString().trim().toIntOrNull() ?: 0
        val cropY = binding.etCropY.text.toString().trim().toIntOrNull() ?: 0
        val trimStart = binding.etTrimStart.text.toString().trim().toDoubleOrNull() ?: 0.0
        val trimEnd = binding.etTrimEnd.text.toString().trim().toDoubleOrNull()

        val params = VideoProcessParams(
            resolution = resolution,
            fps = fps,
            bitrate = bitrate,
            cropWidth = cropWidth,
            cropHeight = cropHeight,
            cropX = cropX,
            cropY = cropY,
            trimStart = trimStart,
            trimEnd = trimEnd
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
            .setNeutralButton("Udostępnij") { _, _ ->
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
        startActivity(Intent.createChooser(intent, "Udostępnij video"))
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
