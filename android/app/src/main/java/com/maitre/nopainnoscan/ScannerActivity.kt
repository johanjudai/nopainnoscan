package com.maitre.nopainnoscan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.maitre.nopainnoscan.api.ApiClient
import com.maitre.nopainnoscan.api.ScoreDto
import com.maitre.nopainnoscan.databinding.ActivityScannerBinding
import com.maitre.nopainnoscan.databinding.ItemAlternativeBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

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
    private var goal: String? = null

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

        // Caméra sous la barre d'état : on décale les puces et la feuille des insets système.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val chipsTop = binding.storeScroll.paddingTop
        val sheetBottom = binding.sheet.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.storeScroll.updatePadding(top = chipsTop + bars.top)
            binding.sheet.updatePadding(bottom = sheetBottom + bars.bottom)
            insets
        }

        setupStoreChips()
        lifecycleScope.launch {
            goal = runCatching { ApiClient.get(this@ScannerActivity).getProfile().goal }.getOrNull()
        }

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else requestPermission.launch(Manifest.permission.CAMERA)
    }

    private fun setupStoreChips() {
        val group = binding.storeChips
        val options: List<Store?> = listOf(null) + Store.entries
        options.forEachIndexed { index, store ->
            val chip = layoutInflater.inflate(R.layout.view_chip_store, group, false) as Chip
            chip.id = View.generateViewId()
            chip.tag = index
            chip.text = store?.label ?: getString(R.string.store_none)
            chip.isChecked = store == prefs.store
            group.addView(chip)
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
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
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
            .addOnSuccessListener { barcodes -> onBarcode(barcodes.firstOrNull()?.rawValue) }
            .addOnCompleteListener { imageProxy.close() }
    }

    /** Exige le même code sur 2 frames consécutives : évite les lectures parasites. */
    private fun onBarcode(code: String?) {
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
        binding.tvHint.text = getString(R.string.scanner_loading, barcode)

        lifecycleScope.launch {
            runCatching { ApiClient.get(this@ScannerActivity).scanBarcode(barcode, prefs.store?.slug) }
                .onSuccess(::showScore)
                .onFailure(::showFailure)
            binding.tvHint.text = getString(R.string.scanner_hint)

            isBusy = false
            delay(RESCAN_DELAY_MS) // laisse le temps de lire avant d'autoriser le même code
            lastScanned = null
        }
    }

    private fun showScore(score: ScoreDto) {
        val category = Category.of(score.category)
        binding.tvWaiting.visibility = View.GONE
        binding.resultGroup.visibility = View.VISIBLE

        binding.ring.set(score.score, ContextCompat.getColor(this, category.color))
        binding.chipCategory.showPill(getString(category.label), category)
        binding.tvProduct.text = score.product_name
        val goalText = getString(goalLabelLower(goal))
        val store = Store.fromSlug(score.store)
        binding.tvMeta.text = if (store != null) getString(R.string.scanner_for_goal_store, goalText, store.label)
        else getString(R.string.scanner_for_goal, goalText)

        renderBreakdown(score.breakdown)
        renderAlternatives(score)
    }

    private fun renderBreakdown(breakdown: Map<String, Double>) {
        val group = binding.breakdownChips
        group.removeAllViews()
        val bonus = ContextCompat.getColor(this, R.color.cat_parfait_on)
        val malus = ContextCompat.getColor(this, R.color.cat_a_eviter_on)
        breakdown.filterValues { abs(it) >= 0.05 }.forEach { (key, value) ->
            val label = BREAKDOWN_LABELS[key] ?: return@forEach
            val amount = if (value > 0) "+${fmt(value)}" else "−${fmt(-value)}"
            val text = SpannableStringBuilder(getString(label)).append("  ").apply {
                val start = length
                append(amount)
                setSpan(ForegroundColorSpan(if (value > 0) bonus else malus), start, length, 0)
                setSpan(StyleSpan(Typeface.BOLD), start, length, 0)
            }
            val chip = layoutInflater.inflate(R.layout.view_chip_breakdown, group, false) as Chip
            chip.text = text
            group.addView(chip)
        }
    }

    private fun renderAlternatives(score: ScoreDto) {
        val container = binding.altContainer
        container.removeAllViews()
        val alternatives = score.alternatives.orEmpty()
        binding.tvAltTitle.visibility = if (alternatives.isEmpty()) View.GONE else View.VISIBLE
        if (alternatives.isEmpty()) return

        val store = Store.fromSlug(score.store)
        binding.tvAltTitle.text = if (store != null) getString(R.string.scanner_alternatives_store, store.label)
        else getString(R.string.scanner_alternatives_any)

        alternatives.forEach { alt ->
            val row = ItemAlternativeBinding.inflate(layoutInflater, container, false)
            row.tvName.text = alt.name
            row.tvScore.showScorePill(alt.score, Category.of(alt.category))
            container.addView(row.root)
        }
    }

    private fun showFailure(e: Throwable) {
        binding.tvWaiting.visibility = View.VISIBLE
        binding.resultGroup.visibility = View.GONE
        binding.tvWaiting.text = when ((e as? HttpException)?.code()) {
            404 -> getString(R.string.scanner_not_found)
            401 -> getString(R.string.scanner_unauthorized)
            else -> getString(R.string.scanner_error, e.message)
        }
    }

    private fun fmt(v: Double): String =
        if (v % 1.0 == 0.0 || v >= 10) v.roundToInt().toString() else Fmt.dec1(v)

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        scanner.close()
    }

    private companion object {
        const val RESCAN_DELAY_MS = 3000L
        const val CONFIRM_FRAMES = 2
        val BREAKDOWN_LABELS = mapOf(
            "bonus_proteines" to R.string.breakdown_bonus_proteines,
            "bonus_fibres" to R.string.breakdown_bonus_fibres,
            "malus_sucre" to R.string.breakdown_malus_sucre,
            "malus_gras_satures" to R.string.breakdown_malus_gras_satures,
            "malus_densite_calorique" to R.string.breakdown_malus_densite_calorique,
        )
    }
}
