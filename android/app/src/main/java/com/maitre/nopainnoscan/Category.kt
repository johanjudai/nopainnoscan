package com.maitre.nopainnoscan

import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/** Miroir de `schemas.Category` côté backend, avec la palette de la maquette. */
enum class Category(
    val slug: String,
    @StringRes val label: Int,
    @ColorRes val color: Int,
    @ColorRes val container: Int,
    @ColorRes val onContainer: Int,
) {
    PARFAIT("parfait", R.string.category_parfait, R.color.cat_parfait, R.color.cat_parfait_container, R.color.cat_parfait_on),
    PAS_MAL("pas_mal", R.string.category_pas_mal, R.color.cat_pas_mal, R.color.cat_pas_mal_container, R.color.cat_pas_mal_on),
    A_EVITER("a_eviter", R.string.category_a_eviter, R.color.cat_a_eviter, R.color.cat_a_eviter_container, R.color.cat_a_eviter_on),
    A_NE_PAS_MANGER("a_ne_pas_manger", R.string.category_a_ne_pas_manger, R.color.cat_a_ne_pas_manger, R.color.cat_a_ne_pas_manger_container, R.color.cat_a_ne_pas_manger_on);

    companion object {
        fun of(slug: String?): Category = entries.firstOrNull { it.slug == slug } ?: A_NE_PAS_MANGER
    }
}

/** Pastille colorée : fond = container de la catégorie, texte = score entier ou libellé. */
fun TextView.showPill(text: String, category: Category) {
    this.text = text
    background.mutate().setTint(ContextCompat.getColor(context, category.container))
    setTextColor(ContextCompat.getColor(context, category.onContainer))
}

fun TextView.showScorePill(score: Double, category: Category) =
    showPill(score.roundToInt().toString(), category)

@StringRes
fun goalLabel(slug: String?): Int = when (slug) {
    "cut" -> R.string.goal_cut
    "bulk" -> R.string.goal_bulk
    else -> R.string.goal_maintenance
}

@StringRes
fun goalLabelLower(slug: String?): Int = when (slug) {
    "cut" -> R.string.goal_cut_lower
    "bulk" -> R.string.goal_bulk_lower
    else -> R.string.goal_maintenance_lower
}
