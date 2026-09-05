package com.maitre.nopainnoscan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
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
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
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
import com.maitre.nopainnoscan.ocr.Correction
import com.maitre.nopainnoscan.ocr.NutrientField
import com.maitre.nopainnoscan.ocr.NutritionCoherence
import com.maitre.nopainnoscan.ocr.NutritionParse
import com.maitre.nopainnoscan.ocr.NutritionParser
import com.maitre.nopainnoscan.ocr.OcrLine
import com.maitre.nopainnoscan.ocr.Problem
import com.maitre.nopainnoscan.ui.ResultRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private var camera: Camera? = null
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
    // Formulaire ouvert à la main (pas de lecture OCR derrière) ; incohérence déjà signalée une fois.
    private var manualEntry = false
    private var softProblemsAcknowledged = false

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
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_MANUAL, false)) {
            binding.modeGroup.check(R.id.btnModeOcr)
            showManualForm()
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
        binding.btnManual.setOnClickListener { showManualForm() }
        // Toute retouche d'un champ invalide l'avertissement déjà affiché.
        formFields().forEach { it.doAfterTextChanged { softProblemsAcknowledged = false } }
    }

    private fun formFields(): List<EditText> = with(binding.ocrForm) {
        listOf(fieldKcal, fieldProtein, fieldCarbs, fieldSugars, fieldFat, fieldSatFat, fieldFiber, fieldSalt)
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
        binding.btnManual.visibility = View.VISIBLE
        binding.tvWaiting.text = getString(if (mode == Mode.OCR) R.string.ocr_waiting else R.string.scanner_waiting)
        binding.tvHint.text = getString(if (mode == Mode.OCR) R.string.ocr_hint else R.string.scanner_hint)
        manualEntry = false
        lastParse = null
        candidate = null
        lastScanned = null
        isBusy = false
        collapseTo(binding.tvWaiting)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            // L'utilisateur a pu quitter avant que la caméra soit prête : binder planterait.
            if (lifecycle.currentState == Lifecycle.State.DESTROYED) return@addListener
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
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            setupTapToFocus()
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * L'autofocus continu hésite sur un code-barres ou une étiquette brillante : un appui sur
     * l'aperçu force la mise au point et l'exposition à cet endroit, puis l'AF continu reprend.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        binding.previewView.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                focusAt(event.x, event.y)
                view.performClick()
            }
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val control = camera?.cameraControl ?: return
        val point = binding.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(FOCUS_HOLD_S, TimeUnit.SECONDS)
            .build()
        control.startFocusAndMetering(action)
        showFocusRing(x, y)
    }

    private fun showFocusRing(x: Float, y: Float) {
        val ring = binding.focusRing
        ring.animate().cancel()
        ring.translationX = x - ring.width / 2f
        ring.translationY = y - ring.height / 2f
        ring.alpha = 1f
        ring.scaleX = 1.3f
        ring.scaleY = 1.3f
        ring.visibility = View.VISIBLE
        ring.animate().scaleX(1f).scaleY(1f).setDuration(150).withEndAction {
            ring.animate().alpha(0f).setStartDelay(500).setDuration(250).withEndAction { ring.visibility = View.INVISIBLE }
        }
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
        if (mode != Mode.BARCODE || code == null || code == lastScanned) return
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
        val result = NutritionParser.parseDetailed(lines)
        val parse = result.parse
        if (!parse.isUsable) return
        val previous = lastParse
        lastParse = parse
        if (previous == null || !previous.matches(parse)) return

        isBusy = true
        showOcrForm(parse, result.corrections)
    }

    /** Formulaire vide, analyse en pause : pour un produit sans code-barres ni tableau lisible. */
    private fun showManualForm() {
        isBusy = true
        manualEntry = true
        showOcrForm(NutritionParse(), emptyList())
    }

    private fun showOcrForm(parse: NutritionParse, corrections: List<Correction>) {
        binding.tvWaiting.visibility = View.GONE
        binding.btnManual.visibility = View.GONE
        binding.result.root.visibility = View.GONE
        softProblemsAcknowledged = false
        with(binding.ocrForm) {
            root.visibility = View.VISIBLE
            tvFormTitle.setText(if (manualEntry) R.string.manual_title else R.string.ocr_review_title)
            tvFormSub.setText(if (manualEntry) R.string.manual_sub else R.string.ocr_review_sub)
            btnRetry.setText(if (manualEntry) R.string.manual_cancel else R.string.ocr_retry)
            fieldName.setText(getString(R.string.ocr_name_default))
            fieldKcal.setText(Fmt.field(parse.kcal))
            fieldProtein.setText(Fmt.field(parse.protein))
            fieldCarbs.setText(Fmt.field(parse.carbs))
            fieldSugars.setText(Fmt.field(parse.sugars))
            fieldFat.setText(Fmt.field(parse.fat))
            fieldSatFat.setText(Fmt.field(parse.satFat))
            fieldFiber.setText(Fmt.field(parse.fiber))
            fieldSalt.setText(Fmt.field(parse.salt))
            showNotice(corrections.takeIf { it.isNotEmpty() }?.let(::describe), warning = false)
        }
        collapseTo(binding.ocrForm.root)
    }

    /** Bandeau sous le sous-titre : corrections appliquées (info) ou incohérences (alerte). */
    private fun showNotice(text: String?, warning: Boolean) = with(binding.ocrForm.tvCorrections) {
        visibility = if (text == null) View.GONE else View.VISIBLE
        this.text = text
        backgroundTintList = ContextCompat.getColorStateList(context, if (warning) R.color.warn_bg else R.color.info_bg)
        setTextColor(ContextCompat.getColor(context, if (warning) R.color.warn_fg else R.color.info_fg))
    }

    private fun describe(corrections: List<Correction>): String =
        getString(R.string.ocr_corrected, corrections.joinToString(" · ") {
            "${getString(FIELD_LABELS.getValue(it.field))} ${Fmt.field(it.from)} → ${Fmt.field(it.to)}"
        })

    private fun describe(problem: Problem): String = getString(
        when (problem) {
            Problem.SUGARS_OVER_CARBS -> R.string.problem_sugars
            Problem.SAT_FAT_OVER_FAT -> R.string.problem_sat_fat
            Problem.SUM_OVER_100 -> R.string.problem_sum
            Problem.ENERGY_MISMATCH -> R.string.problem_energy
        }
    )

    private fun submitOcr() {
        val form = binding.ocrForm
        val kcal = form.fieldKcal.num()
        if (kcal == null) {
            Toast.makeText(this, R.string.ocr_missing_kcal, Toast.LENGTH_SHORT).show()
            return
        }
        val entered = NutritionParse(
            kcal = kcal, protein = form.fieldProtein.num(), carbs = form.fieldCarbs.num(),
            sugars = form.fieldSugars.num(), fat = form.fieldFat.num(), satFat = form.fieldSatFat.num(),
            fiber = form.fieldFiber.num(), salt = form.fieldSalt.num(),
        )
        // Impossible physiquement : on bloque. Énergie discordante : on prévient, le 2e appui envoie.
        val problems = NutritionCoherence.problems(entered)
        val hard = problems.filter { it != Problem.ENERGY_MISMATCH }
        if (hard.isNotEmpty()) {
            showNotice(hard.joinToString("\n") { describe(it) }, warning = true)
            return
        }
        if (problems.isNotEmpty() && !softProblemsAcknowledged) {
            softProblemsAcknowledged = true
            showNotice(problems.joinToString("\n") { describe(it) } + "\n" + getString(R.string.problem_send_anyway), warning = true)
            return
        }
        val dto = NutrientsDto(
            name = form.fieldName.text.toString().trim().ifBlank { getString(R.string.ocr_name_default) },
            kcal_100g = kcal,
            protein_100g = entered.protein ?: 0.0,
            carbs_100g = entered.carbs ?: 0.0,
            sugars_100g = entered.sugars ?: 0.0,
            fat_100g = entered.fat ?: 0.0,
            saturated_fat_100g = entered.satFat ?: 0.0,
            fiber_100g = entered.fiber ?: 0.0,
            salt_100g = entered.salt ?: 0.0,
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
        binding.btnManual.visibility = View.GONE
        binding.result.root.visibility = View.VISIBLE
        renderer.render(score, goal)
        // En OCR l'analyse reste en pause (isBusy) jusqu'à « Rescanner » : la carte reste lisible.
        binding.btnOcrAgain.visibility = if (mode == Mode.OCR) View.VISIBLE else View.GONE
        binding.result.tvSwipeHint.visibility = View.VISIBLE
        collapseTo(binding.result.tvSwipeHint)
    }

    private fun showFailure(e: Throwable) {
        binding.tvWaiting.visibility = View.VISIBLE
        binding.btnManual.visibility = View.VISIBLE
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

    companion object {
        private const val RESCAN_DELAY_MS = 3000L
        private const val CONFIRM_FRAMES = 2
        private const val FOCUS_HOLD_S = 4L
        private const val EXTRA_MANUAL = "manual"
        private val FIELD_LABELS = mapOf(
            NutrientField.KCAL to R.string.ocr_kcal,
            NutrientField.PROTEIN to R.string.ocr_protein,
            NutrientField.CARBS to R.string.ocr_carbs,
            NutrientField.SUGARS to R.string.ocr_sugars,
            NutrientField.FAT to R.string.ocr_fat,
            NutrientField.SAT_FAT to R.string.ocr_satfat,
            NutrientField.FIBER to R.string.ocr_fiber,
            NutrientField.SALT to R.string.ocr_salt,
        )

        /** Ouvre directement le formulaire de saisie manuelle (depuis l'accueil). */
        fun openManual(context: Context) {
            context.startActivity(Intent(context, ScannerActivity::class.java).putExtra(EXTRA_MANUAL, true))
        }
    }
}
