package com.maitre.nopainnoscan.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.ContextCompat
import coil.load
import com.google.android.material.chip.Chip
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
import kotlin.math.abs
import kotlin.math.roundToInt

/** Carte de résultat partagée entre le scanner et la fiche produit. */
class ResultRenderer(
    private val context: Context,
    private val binding: ViewResultBinding,
    private val inflater: LayoutInflater,
    private val onAlternativeClick: ((Int) -> Unit)? = null,
) {

    fun render(score: ScoreDto, goal: String?) {
        val category = Category.of(score.category)
        binding.ring.set(score.score, ContextCompat.getColor(context, category.color))
        binding.chipCategory.showPill(context.getString(category.label), category)
        binding.tvProduct.text = score.product_name
        binding.ivImage.visibility = if (score.image_url.isNullOrBlank()) View.GONE else View.VISIBLE
        score.image_url?.let { binding.ivImage.load(it) { crossfade(true) } }

        val goalText = context.getString(goalLabelLower(goal))
        val store = Store.fromSlug(score.store)
        binding.tvMeta.text = if (store != null) context.getString(R.string.scanner_for_goal_store, goalText, store.label)
        else context.getString(R.string.scanner_for_goal, goalText)

        renderBreakdown(score.breakdown.orEmpty())
        renderMeal(score.meal)
        renderAlternatives(score, store)
    }

    private fun renderMeal(meal: MealDto?) {
        binding.cardMeal.visibility = if (meal == null) View.GONE else View.VISIBLE
        if (meal == null) return
        val portionRes = if (meal.role == "drink") R.string.meal_portion_ml else R.string.meal_portion
        binding.tvPortion.text = context.getString(portionRes, meal.portion_g)
        binding.tvPortionSub.text = context.getString(
            R.string.meal_portion_sub, meal.portion_kcal, Fmt.dec1(meal.portion_protein_g)
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
        val BREAKDOWN_LABELS = mapOf(
            "bonus_proteines" to R.string.breakdown_bonus_proteines,
            "bonus_fibres" to R.string.breakdown_bonus_fibres,
            "malus_sucre" to R.string.breakdown_malus_sucre,
            "malus_gras_satures" to R.string.breakdown_malus_gras_satures,
            "malus_densite_calorique" to R.string.breakdown_malus_densite_calorique,
        )
    }
}
