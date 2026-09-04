package com.maitre.nopainnoscan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.EditText
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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.maitre.nopainnoscan.api.ApiClient
import com.maitre.nopainnoscan.api.NutrientsDto
import com.maitre.nopainnoscan.api.ScoreDto
import com.maitre.nopainnoscan.databinding.ActivityScannerBinding
import com.maitre.nopainnoscan.ocr.NutritionParse
import com.maitre.nopainnoscan.ocr.NutritionParser
import com.maitre.nopainnoscan.ocr.OcrLine
import com.maitre.nopainnoscan.ui.ResultRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Scan live sans photo : CameraX pousse chaque frame à ML Kit sur un thread dédié.
 * Deux modes : code-barres (par défaut) ou OCR du tableau nutritionnel. Un seul client
 * ML Kit par mode pour toute l'activité (en créer un par frame fuit des ressources natives).
 */
class ScannerActivity : AppCompatActivity() {

    private enum class Mode { BARCODE, OCR }

    private lateinit var binding: ActivityScannerBinding
    private lateinit var prefs: AppPrefs
    private lateinit var renderer: ResultRenderer
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var sheet: BottomSheetBehavior<View>

    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8)
            .build()
    )
    private val textRecognizerLazy = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val textRecognizer by textRecognizerLazy

    // Accédés depuis le thread caméra et le thread UI.
    @Volatile private var mode = Mode.BARCODE
    @Volatile private var lastScanned: String? = null
    @Volatile private var isBusy = false
    private var candidate: String? = null
    private var candidateHits = 0
    private var lastParse: NutritionParse? = null
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
        renderer = ResultRenderer(this, binding.result, layoutInflater) { ProductActivity.open(this, it) }

        // Caméra sous la barre d'état : on décale les puces et la feuille des insets système.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val chipsTop = binding.storeScroll.paddingTop
        val sheetBottom = binding.sheetContent.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.storeScroll.updatePadding(top = chipsTop + bars.top)
            binding.sheetContent.updatePadding(bottom = sheetBottom + bars.bottom)
            insets
        }
        sheet = BottomSheetBehavior.from(binding.sheet)
        sheet.isFitToContents = true
        sheet.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                // L'indice disparaît dès que la feuille a été tirée une fois.
                if (newState == BottomSheetBehavior.STATE_EXPANDED) binding.result.tvSwipeHint.visibility = View.GONE
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
        })

        setupStoreChips()
        setupModes()
        collapseTo(binding.tvWaiting)
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

    private fun setupModes() {
        binding.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            mode = if (id == R.id.btnModeOcr) Mode.OCR else Mode.BARCODE
            resetSheet()
        }
        binding.ocrForm.btnRetry.setOnClickListener { resetSheet() }
        binding.ocrForm.btnSubmit.setOnClickListener { submitOcr() }
        binding.btnOcrAgain.setOnClickListener { resetSheet() }
    }

    /**
     * Hauteur repliée = jusqu'au bas de la vue donnée (rangée du score, formulaire ou attente),
     * pour que le reste se découvre en tirant vers le haut.
     */
    private fun collapseTo(anchor: View) {
        binding.sheet.post {
            var y = anchor.bottom
            var v = anchor.parent as View
            while (v !== binding.sheetContent) {
                y += v.top
                v = v.parent as View
            }
            sheet.setPeekHeight(y + binding.sheetContent.paddingBottom, true)
            sheet.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    /** Retour à l'état d'attente du mode courant ; relance l'analyse. */
    private fun resetSheet() {
        binding.result.root.visibility = View.GONE
        binding.ocrForm.root.visibility = View.GONE
        binding.btnOcrAgain.visibility = View.GONE
        binding.tvWaiting.visibility = View.VISIBLE
        binding.tvWaiting.text = getString(if (mode == Mode.OCR) R.string.ocr_waiting else R.string.scanner_waiting)
        binding.tvHint.text = getString(if (mode == Mode.OCR) R.string.ocr_hint else R.string.scanner_hint)
        lastParse = null
        candidate = null
        lastScanned = null
        isBusy = false
        collapseTo(binding.tvWaiting)
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
        when (mode) {
            Mode.BARCODE -> barcodeScanner.process(image)
                .addOnSuccessListener { barcodes -> onBarcode(barcodes.firstOrNull()?.rawValue) }
                .addOnCompleteListener { imageProxy.close() }
            Mode.OCR -> textRecognizer.process(image)
                .addOnSuccessListener(::onText)
                .addOnCompleteListener { imageProxy.close() }
        }
    }

    // ---------- code-barres ----------

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

    // ---------- tableau nutritionnel ----------

    /** Deux lectures stables d'affilée avant de proposer le formulaire de vérification. */
    private fun onText(text: Text) {
        if (mode != Mode.OCR || isBusy) return
        val lines = text.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                line.boundingBox?.let { OcrLine(line.text, it.left, it.top, it.right, it.bottom) }
            }
        }
        val parse = NutritionParser.parse(lines)
        if (!parse.isUsable) return
        val previous = lastParse
        lastParse = parse
        if (previous == null || !previous.matches(parse)) return

        isBusy = true
        showOcrForm(parse)
    }

    private fun showOcrForm(parse: NutritionParse) {
        binding.tvWaiting.visibility = View.GONE
        binding.result.root.visibility = View.GONE
        with(binding.ocrForm) {
            root.visibility = View.VISIBLE
            fieldName.setText(getString(R.string.ocr_name_default))
            fieldKcal.setText(Fmt.field(parse.kcal))
            fieldProtein.setText(Fmt.field(parse.protein))
            fieldCarbs.setText(Fmt.field(parse.carbs))
            fieldSugars.setText(Fmt.field(parse.sugars))
            fieldFat.setText(Fmt.field(parse.fat))
            fieldSatFat.setText(Fmt.field(parse.satFat))
            fieldFiber.setText(Fmt.field(parse.fiber))
            fieldSalt.setText(Fmt.field(parse.salt))
        }
        collapseTo(binding.ocrForm.root)
    }

    private fun submitOcr() {
        val form = binding.ocrForm
        val kcal = form.fieldKcal.num()
        if (kcal == null) {
            Toast.makeText(this, R.string.ocr_missing_kcal, Toast.LENGTH_SHORT).show()
            return
        }
        val dto = NutrientsDto(
            name = form.fieldName.text.toString().trim().ifBlank { getString(R.string.ocr_name_default) },
            kcal_100g = kcal,
            protein_100g = form.fieldProtein.num() ?: 0.0,
            carbs_100g = form.fieldCarbs.num() ?: 0.0,
            sugars_100g = form.fieldSugars.num() ?: 0.0,
            fat_100g = form.fieldFat.num() ?: 0.0,
            saturated_fat_100g = form.fieldSatFat.num() ?: 0.0,
            fiber_100g = form.fieldFiber.num() ?: 0.0,
            salt_100g = form.fieldSalt.num() ?: 0.0,
        )
        form.btnSubmit.isEnabled = false
        lifecycleScope.launch {
            runCatching { ApiClient.get(this@ScannerActivity).scanManual(dto, prefs.store?.slug) }
                .onSuccess {
                    form.root.visibility = View.GONE
                    showScore(it)
                }
                .onFailure(::showFailure)
            form.btnSubmit.isEnabled = true
            // Le résultat reste affiché ; l'utilisateur repasse par « Rescanner » ou change de mode.
        }
    }

    private fun EditText.num(): Double? = Fmt.parse(text)

    // ---------- résultat ----------

    private fun showScore(score: ScoreDto) {
        binding.tvWaiting.visibility = View.GONE
        binding.result.root.visibility = View.VISIBLE
        renderer.render(score, goal)
        // En OCR l'analyse reste en pause (isBusy) jusqu'à « Rescanner » : la carte reste lisible.
        binding.btnOcrAgain.visibility = if (mode == Mode.OCR) View.VISIBLE else View.GONE
        binding.result.tvSwipeHint.visibility = View.VISIBLE
        collapseTo(binding.result.tvSwipeHint)
    }

    private fun showFailure(e: Throwable) {
        binding.tvWaiting.visibility = View.VISIBLE
        binding.result.root.visibility = View.GONE
        binding.tvWaiting.text = when ((e as? HttpException)?.code()) {
            404 -> getString(R.string.scanner_not_found)
            else -> ApiErrors.describe(this, e)
        }
        collapseTo(binding.tvWaiting)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner.close()
        if (textRecognizerLazy.isInitialized()) textRecognizer.close()
    }

    private companion object {
        const val RESCAN_DELAY_MS = 3000L
        const val CONFIRM_FRAMES = 2
    }
}
