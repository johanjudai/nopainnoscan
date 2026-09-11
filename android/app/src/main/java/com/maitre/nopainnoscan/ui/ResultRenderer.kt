package com.maitre.nopainnoscan.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import coil.load
import com.google.android.material.chip.Chip
import com.maitre.nopainnoscan.ApiErrors
import com.maitre.nopainnoscan.Category
import com.maitre.nopainnoscan.Fmt
import com.maitre.nopainnoscan.R
import com.maitre.nopainnoscan.Store
import com.maitre.nopainnoscan.api.MealDto
import com.maitre.nopainnoscan.api.ScoreDto
import com.maitre.nopainnoscan.databinding.ItemAlternativeBinding
import com.maitre.nopainnoscan.databinding.ViewResultBinding
import com.maitre.nopainnoscan.goalLabelLower
import com.maitre.nopainnoscan.showPill
import com.maitre.nopainnoscan.showScorePill
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Carte de résultat partagée entre le scanner et la fiche produit. Avec [mealLoader], la
 * quantité devient éditable : le repas est rechargé (débounce, une requête en vol) et
 * n'est affiché que si le produit à l'écran est toujours celui de la requête.
 */
class ResultRenderer(
    private val context: Context,
    private val binding: ViewResultBinding,
    private val inflater: LayoutInflater,
    private val onAlternativeClick: ((Int) -> Unit)? = null,
    private val scope: CoroutineScope? = null,
    private val mealLoader: (suspend (productId: Int, grams: Int) -> MealDto)? = null,
) {

    private var productId = 0
    private var suggestedPortion = 0
    private var fillingField = false
    private var pendingPortion: Runnable? = null
    private var mealJob: Job? = null

    init {
        binding.fieldPortion.doAfterTextChanged { text ->
            if (fillingField) return@doAfterTextChanged
            val grams = text?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: return@doAfterTextChanged
            pendingPortion?.let(binding.fieldPortion::removeCallbacks)
            pendingPortion = Runnable { reloadMeal(grams.coerceAtMost(MAX_PORTION_G)) }
                .also { binding.fieldPortion.postDelayed(it, PORTION_DEBOUNCE_MS) }
        }
    }

    private fun reloadMeal(grams: Int) {
        val loader = mealLoader ?: return
        val scope = scope ?: return
        val forProduct = productId
        mealJob?.cancel()
        mealJob = scope.launch {
            runCatching { loader(forProduct, grams) }
                .onSuccess { if (productId == forProduct) renderMeal(it) }
                .onFailure {
                    if (it !is CancellationException) {
                        Toast.makeText(context, ApiErrors.describe(context, it), Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    fun render(score: ScoreDto, goal: String?) {
        // Nouveau produit : rien de ce qui était en attente pour l'ancien ne doit s'afficher sous son nom.
        pendingPortion?.let(binding.fieldPortion::removeCallbacks)
        mealJob?.cancel()
        // Même produit re-scanné pendant que l'utilisateur ajuste la quantité : on garde sa saisie.
        val keepPortion = score.product_id == productId && customPortion() != null
        productId = score.product_id
        val category = Category.of(score.category)
        binding.ring.set(score.score, ContextCompat.getColor(context, category.color))
        binding.chipCategory.showPill(context.getString(category.label), category)
        binding.tvProduct.text = score.product_name
        val image = score.image_url?.takeIf { it.isNotBlank() }
        binding.ivImage.visibility = if (image == null) View.GONE else View.VISIBLE
        image?.let { url ->
            binding.ivImage.load(url) { crossfade(true) }
            binding.ivImage.setOnClickListener { PhotoDialog.show(context, url, binding.ivImage.drawable) }
        }

        val goalText = context.getString(goalLabelLower(goal))
        val store = Store.fromSlug(score.store)
        binding.tvMeta.text = if (store != null) context.getString(R.string.scanner_for_goal_store, goalText, store.label)
        else context.getString(R.string.scanner_for_goal, goalText)

        renderBreakdown(score.breakdown.orEmpty())
        suggestedPortion = score.meal?.portion_g ?: 0
        if (!keepPortion) {
            fillField(suggestedPortion)
            renderMeal(score.meal)
        }
        binding.layoutPortion.visibility = if (score.meal == null || mealLoader == null) View.GONE else View.VISIBLE
        renderAlternatives(score, store)
    }

    /** Quantité tapée par l'utilisateur, ou null si le champ montre encore la portion conseillée. */
    private fun customPortion(): Int? =
        binding.fieldPortion.text?.toString()?.toIntOrNull()?.takeIf { it > 0 && it != suggestedPortion }

    private fun fillField(grams: Int) {
        fillingField = true
        binding.fieldPortion.setText(if (grams > 0) grams.toString() else "")
        fillingField = false
    }

    private fun renderMeal(meal: MealDto?) {
        binding.cardMeal.visibility = if (meal == null) View.GONE else View.VISIBLE
        if (meal == null) return
        val custom = meal.portion_g != suggestedPortion
        val unit = context.getString(if (meal.role == "drink") R.string.unit_ml else R.string.unit_g)
        binding.layoutPortion.suffixText = unit
        binding.tvPortion.text = context.getString(
            if (custom) R.string.meal_portion_custom else R.string.meal_portion, meal.portion_g, unit
        )
        binding.tvPortionSub.text = context.getString(
            R.string.meal_portion_sub, meal.portion_kcal, Fmt.dec1(meal.portion_protein_g),
            Fmt.dec1(meal.portion_carbs_g ?: 0.0), Fmt.dec1(meal.portion_fat_g ?: 0.0),
        )

        val complement = meal.complement
        binding.tvComplement.visibility = if (complement == null) View.GONE else View.VISIBLE
        if (complement != null) {
            binding.tvComplement.text = context.getString(
                R.string.meal_complement, complement.grams, complement.name.orEmpty().lowercase(),
                complement.kcal, Fmt.dec1(complement.protein_g),
            )
        }
        val extras = meal.extras.orEmpty()
        binding.tvExtras.visibility = if (extras.isEmpty()) View.GONE else View.VISIBLE
        binding.tvExtras.text = extras.joinToString("\n") { context.getString(R.string.meal_extra, it.lowercase()) }

        binding.tvMealTotal.text = context.getString(R.string.meal_total, Fmt.int(meal.meal_kcal), meal.share_of_day_pct)
        binding.tvMealTargets.text = context.getString(
            R.string.meal_targets, Fmt.int(meal.daily_kcal_target), Fmt.int(meal.weekly_kcal_target),
            meal.meal_protein_target_g,
        )
        binding.tvMealNote.text = meal.note
    }

    private fun renderBreakdown(breakdown: Map<String, Double>) {
        val group = binding.breakdownChips
        group.removeAllViews()
        val bonus = ContextCompat.getColor(context, R.color.cat_parfait_on)
        val malus = ContextCompat.getColor(context, R.color.cat_a_eviter_on)
        breakdown.filterValues { abs(it) >= 0.05 }.forEach { (key, value) ->
            val label = BREAKDOWN_LABELS[key] ?: return@forEach
            val amount = if (value > 0) "+${fmt(value)}" else "−${fmt(-value)}"
            val text = SpannableStringBuilder(context.getString(label)).append("  ").apply {
                val start = length
                append(amount)
                setSpan(ForegroundColorSpan(if (value > 0) bonus else malus), start, length, 0)
                setSpan(StyleSpan(Typeface.BOLD), start, length, 0)
            }
            val chip = inflater.inflate(R.layout.view_chip_breakdown, group, false) as Chip
            chip.text = text
            group.addView(chip)
        }
    }

    private fun renderAlternatives(score: ScoreDto, store: Store?) {
        val here = score.alternatives.orEmpty()
        val elsewhere = score.alternatives_elsewhere.orEmpty()

        // 1) En tête : ce qui est connu dans l'enseigne (ou tout, sans enseigne).
        binding.tvAltTitle.text = if (store != null) context.getString(R.string.alt_in_store, store.label)
        else context.getString(R.string.alt_any)
        fillRows(binding.altContainer, here)
        binding.tvAltEmpty.visibility = if (here.isEmpty()) View.VISIBLE else View.GONE
        binding.tvAltEmpty.text = if (store != null) context.getString(R.string.alt_none_store, store.label)
        else context.getString(R.string.alt_none_any)

        // 2) Sous un séparateur : les options vues dans d'autres enseignes.
        binding.elsewhereGroup.visibility = if (elsewhere.isEmpty()) View.GONE else View.VISIBLE
        fillRows(binding.elsewhereContainer, elsewhere)
    }

    private fun fillRows(container: android.widget.LinearLayout, items: List<com.maitre.nopainnoscan.api.AlternativeDto>) {
        container.removeAllViews()
        items.forEach { alt ->
            val row = ItemAlternativeBinding.inflate(inflater, container, false)
            row.tvName.text = alt.name
            row.tvScore.showScorePill(alt.score, Category.of(alt.category))
            onAlternativeClick?.let { click -> row.root.setOnClickListener { click(alt.product_id) } }
            container.addView(row.root)
        }
    }

    private fun fmt(v: Double): String =
        if (v % 1.0 == 0.0 || v >= 10) v.roundToInt().toString() else Fmt.dec1(v)

    private companion object {
        const val PORTION_DEBOUNCE_MS = 400L
        const val MAX_PORTION_G = 2000 // borne de l'API
        val BREAKDOWN_LABELS = mapOf(
            "bonus_proteines" to R.string.breakdown_bonus_proteines,
            "bonus_fibres" to R.string.breakdown_bonus_fibres,
            "malus_sucre" to R.string.breakdown_malus_sucre,
            "malus_gras_satures" to R.string.breakdown_malus_gras_satures,
            "malus_densite_calorique" to R.string.breakdown_malus_densite_calorique,
        )
    }
}
