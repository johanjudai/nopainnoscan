package com.maitre.nopainnoscan.ocr

import kotlin.math.abs

/** Accès uniforme aux champs d'une lecture, pour corriger sans huit copies de la même logique. */
enum class NutrientField(val get: (NutritionParse) -> Double?, val set: (NutritionParse, Double) -> NutritionParse) {
    KCAL({ it.kcal }, { p, v -> p.copy(kcal = v) }),
    PROTEIN({ it.protein }, { p, v -> p.copy(protein = v) }),
    CARBS({ it.carbs }, { p, v -> p.copy(carbs = v) }),
    SUGARS({ it.sugars }, { p, v -> p.copy(sugars = v) }),
    FAT({ it.fat }, { p, v -> p.copy(fat = v) }),
    SAT_FAT({ it.satFat }, { p, v -> p.copy(satFat = v) }),
    FIBER({ it.fiber }, { p, v -> p.copy(fiber = v) }),
    SALT({ it.salt }, { p, v -> p.copy(salt = v) }),
}

data class Correction(val field: NutrientField, val from: Double, val to: Double)

data class Reconciled(val parse: NutritionParse, val corrections: List<Correction>)

/** Incohérences restantes ; les deux premières et la somme sont physiquement impossibles. */
enum class Problem { SUGARS_OVER_CARBS, SAT_FAT_OVER_FAT, SUM_OVER_100, ENERGY_MISMATCH }

/**
 * Mise en cohérence d'un tableau lu par OCR. L'erreur type est une virgule perdue
 * (« 8,5 g » lu « 85 g ») : on ne corrige donc qu'en divisant une valeur par 10 ou 100,
 * et seulement quand une règle physique l'impose (sucres ≤ glucides, saturés ≤ lipides,
 * somme ≤ 100 g, énergie ≈ 4·P + 4·G + 9·L + 2·fibres).
 */
object NutritionCoherence {

    private val GRAMS = NutrientField.entries - NutrientField.KCAL
    private val MACROS = listOf(NutrientField.PROTEIN, NutrientField.CARBS, NutrientField.FAT)
    private const val MAX_KCAL = 900.0 // matière grasse pure
    private const val EPS = 0.051 // arrondis d'étiquette
    private const val ENERGY_TOLERANCE = 0.25

    fun reconcile(raw: NutritionParse): Reconciled {
        var p = raw
        val fixes = mutableListOf<Correction>()
        fun fix(field: NutrientField, to: Double) {
            fixes += Correction(field, field.get(p)!!, to)
            p = field.set(p, to)
        }

        // 1. Bornes absolues : plus de 100 g pour 100 g, ou plus de 900 kcal, n'existent pas.
        for (f in GRAMS) {
            val original = f.get(p) ?: continue
            var v = original
            while (v > 100) v /= 10
            if (v != original) fix(f, v)
        }
        p.kcal?.let { original ->
            var v = original
            while (v > MAX_KCAL) v /= 10
            if (v != original) fix(NutrientField.KCAL, v)
        }

        // 2. Un « dont » ne dépasse pas son parent.
        fun child(childF: NutrientField, parentF: NutrientField) {
            val c = childF.get(p) ?: return
            val parent = parentF.get(p) ?: return
            if (!over(c, parent)) return
            listOf(c / 10, c / 100).firstOrNull { !over(it, parent) }?.let { fix(childF, it) }
        }
        child(NutrientField.SUGARS, NutrientField.CARBS)
        child(NutrientField.SAT_FAT, NutrientField.FAT)

        // 3. La somme des nutriments tient dans 100 g : une seule valeur fautive, celle qui
        //    ramène la somme sous 100 en respectant le mieux l'énergie annoncée.
        if (sum(p) > 100 + EPS) {
            GRAMS.filter { f -> (f.get(p) ?: 0.0) > 0 }
                .map { f -> f to f.set(p, f.get(p)!! / 10) }
                .filter { (_, q) -> sum(q) <= 100 + EPS && hierarchyOk(q) }
                .minByOrNull { (_, q) -> energyError(q) ?: 0.0 }
                ?.let { (f, q) -> fix(f, f.get(q)!!) }
        }

        // 4. Énergie : si l'écart est net, le seul macro dont la division par 10 rétablit
        //    l'équation est corrigé (deux passes, une erreur à la fois). Les kcal ne sont jamais
        //    touchées : entières sur l'étiquette, elles ne perdent pas de virgule, et l'alcool ou
        //    les polyols les éloignent légitimement de l'estimation.
        repeat(2) {
            val err = energyError(p) ?: return@repeat
            if (err <= ENERGY_TOLERANCE) return@repeat
            val best = MACROS
                .filter { f -> (f.get(p) ?: 0.0) > 0 }
                .map { f -> f to f.set(p, f.get(p)!! / 10) }
                .filter { (_, q) -> hierarchyOk(q) }
                .minByOrNull { (_, q) -> energyError(q)!! } ?: return@repeat
            val bestErr = energyError(best.second)!!
            if (bestErr <= ENERGY_TOLERANCE && bestErr < err / 2) fix(best.first, best.first.get(best.second)!!)
        }
        return Reconciled(p, fixes)
    }

    /** Ce qui reste incohérent après saisie ou correction ; vide = rien à signaler. */
    fun problems(p: NutritionParse): List<Problem> = buildList {
        if (over(p.sugars, p.carbs)) add(Problem.SUGARS_OVER_CARBS)
        if (over(p.satFat, p.fat)) add(Problem.SAT_FAT_OVER_FAT)
        if (sum(p) > 100 + EPS) add(Problem.SUM_OVER_100)
        if ((energyError(p) ?: 0.0) > ENERGY_TOLERANCE) add(Problem.ENERGY_MISMATCH)
    }

    /** Estimation Atwater des kcal ; null tant que kcal et les trois macros ne sont pas connus. */
    fun estimatedKcal(p: NutritionParse): Double? {
        val protein = p.protein ?: return null
        val carbs = p.carbs ?: return null
        val fat = p.fat ?: return null
        return 4 * protein + 4 * carbs + 9 * fat + 2 * (p.fiber ?: 0.0)
    }

    /** Écart relatif entre kcal lues et estimées (au moins 15 kcal de marge pour les produits légers). */
    private fun energyError(p: NutritionParse): Double? {
        val kcal = p.kcal ?: return null
        val estimated = estimatedKcal(p) ?: return null
        return abs(estimated - kcal) / maxOf(kcal, 60.0)
    }

    private fun sum(p: NutritionParse): Double =
        (p.protein ?: 0.0) + (p.carbs ?: 0.0) + (p.fat ?: 0.0) + (p.fiber ?: 0.0) + (p.salt ?: 0.0)

    /** Tolérance relative : les arrondis d'étiquette donnent parfois « sucres 4,1 g » pour « glucides 4 g ». */
    private fun over(child: Double?, parent: Double?): Boolean =
        child != null && parent != null && child > parent + maxOf(0.1, parent * 0.05)

    private fun hierarchyOk(p: NutritionParse): Boolean = !over(p.sugars, p.carbs) && !over(p.satFat, p.fat)
}
