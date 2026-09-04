package com.maitre.nopainnoscan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.maitre.nopainnoscan.api.ApiClient
import com.maitre.nopainnoscan.api.ScoreDto
import com.maitre.nopainnoscan.databinding.ActivityScannerBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Scan live sans photo : CameraX pousse chaque frame à ML Kit sur un thread dédié.
 * Un seul BarcodeScanner pour toute l'activité (en créer un par frame fuit des ressources natives).
 */
class ScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScannerBinding
    private lateinit var prefs: AppPrefs
    private lateinit var cameraExecutor: ExecutorService

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8)
            .build()
    )

    // Accédés depuis le thread caméra et le thread UI.
    @Volatile private var lastScanned: String? = null
    @Volatile private var isBusy = false
    private var candidate: String? = null
    private var candidateHits = 0

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupStoreChips()

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else requestPermission.launch(Manifest.permission.CAMERA)
    }

    private fun setupStoreChips() {
        val group = binding.storeChips
        val options: List<Store?> = listOf(null) + Store.entries
        options.forEachIndexed { index, store ->
            group.addView(Chip(this).apply {
                id = View.generateViewId()
                tag = index
                text = store?.label ?: getString(R.string.store_none)
                isCheckable = true
                isChecked = store == prefs.store
            })
        }
        if (group.checkedChipId == View.NO_ID) (group.getChildAt(0) as Chip).isChecked = true

        group.setOnCheckedStateChangeListener { g, ids ->
            val chip = ids.firstOrNull()?.let { g.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            prefs.store = options[chip.tag as Int]
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            // 720p suffit largement pour un EAN et divise le coût ML Kit par frame.
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyzeFrame) }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    @ExperimentalGetImage
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || isBusy) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes -> onBarcodes(barcodes.firstOrNull()?.rawValue) }
            .addOnCompleteListener { imageProxy.close() }
    }

    /** Exige le même code sur 2 frames consécutives : évite les lectures parasites. */
    private fun onBarcodes(code: String?) {
        if (code == null || code == lastScanned) return
        if (code != candidate) {
            candidate = code
            candidateHits = 1
            return
        }
        if (++candidateHits < CONFIRM_FRAMES) return

        lastScanned = code
        candidate = null
        handleBarcode(code)
    }

    private fun handleBarcode(barcode: String) {
        isBusy = true
        binding.resultText.text = getString(R.string.scanner_loading, barcode)
        binding.alternativesText.visibility = View.GONE

        lifecycleScope.launch {
            runCatching { ApiClient.get(this@ScannerActivity).scanBarcode(barcode, prefs.store?.slug) }
                .onSuccess(::showScore)
                .onFailure { binding.resultText.text = describeFailure(it) }

            isBusy = false
            delay(RESCAN_DELAY_MS) // laisse le temps de lire avant d'autoriser le même code
            lastScanned = null
        }
    }

    private fun showScore(score: ScoreDto) {
        binding.resultText.text = getString(
            R.string.scanner_result, score.product_name, categoryLabel(score.category), score.score
        )
        if (score.alternatives.isEmpty()) return

        val title = if (score.store != null) R.string.scanner_alternatives_title
        else R.string.scanner_alternatives_title_any
        val lines = score.alternatives.map {
            getString(R.string.scanner_alternative_line, it.name, it.score)
        }
        binding.alternativesText.text = (listOf(getString(title)) + lines).joinToString("\n")
        binding.alternativesText.visibility = View.VISIBLE
    }

    private fun describeFailure(e: Throwable): String =
        if (e is retrofit2.HttpException && e.code() == 404) getString(R.string.scanner_not_found)
        else getString(R.string.scanner_error, e.message)

    private fun categoryLabel(slug: String): String = when (slug) {
        "parfait" -> getString(R.string.category_parfait)
        "pas_mal" -> getString(R.string.category_pas_mal)
        "a_eviter" -> getString(R.string.category_a_eviter)
        else -> getString(R.string.category_a_ne_pas_manger)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        scanner.close()
    }

    private companion object {
        const val RESCAN_DELAY_MS = 3000L
        const val CONFIRM_FRAMES = 2
    }
}
