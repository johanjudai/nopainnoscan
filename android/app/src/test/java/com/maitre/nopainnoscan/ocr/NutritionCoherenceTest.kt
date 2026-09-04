package com.maitre.nopainnoscan.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionCoherenceTest {

    private val biscuit = NutritionParse(
        kcal = 460.0, protein = 6.0, carbs = 65.0, sugars = 24.0, fat = 19.0, satFat = 8.5, fiber = 3.0, salt = 0.8,
    )

    @Test
    fun coherent_table_is_left_untouched() {
        val r = NutritionCoherence.reconcile(biscuit)
        assertEquals(biscuit, r.parse)
        assertTrue(r.corrections.isEmpty())
        assertTrue(NutritionCoherence.problems(biscuit).isEmpty())
    }

    @Test
    fun sugars_above_carbs_lost_a_comma() {
        val r = NutritionCoherence.reconcile(NutritionParse(kcal = 110.0, protein = 3.0, carbs = 24.0, sugars = 85.0, fat = 0.5))
        assertEquals(8.5, r.parse.sugars!!, 0.001)
        assertEquals(listOf(Correction(NutrientField.SUGARS, 85.0, 8.5)), r.corrections)
    }

    @Test
    fun saturated_fat_above_fat_lost_a_comma() {
        val r = NutritionCoherence.reconcile(NutritionParse(kcal = 250.0, protein = 11.0, carbs = 30.0, fat = 9.5, satFat = 32.0))
        assertEquals(3.2, r.parse.satFat!!, 0.001)
    }

    @Test
    fun label_rounding_is_not_an_error() {
        val p = NutritionParse(kcal = 20.0, protein = 0.5, carbs = 4.0, sugars = 4.1, fat = 0.1, satFat = 0.1)
        assertEquals(p, NutritionCoherence.reconcile(p).parse)
        assertTrue(NutritionCoherence.problems(p).isEmpty())
    }

    @Test
    fun fat_read_ten_times_too_big_is_caught_by_sum_and_energy() {
        val r = NutritionCoherence.reconcile(NutritionParse(kcal = 250.0, protein = 11.0, carbs = 30.0, fat = 95.0, satFat = 3.2))
        assertEquals(9.5, r.parse.fat!!, 0.001)
        assertEquals(1, r.corrections.size)
    }

    @Test
    fun energy_mismatch_alone_fixes_the_single_wrong_macro() {
        // Yaourt : 60 kcal mais 55 g de glucides lus (5,5) ; la somme reste < 100, seule l'énergie trahit l'erreur.
        val r = NutritionCoherence.reconcile(NutritionParse(kcal = 60.0, protein = 4.0, carbs = 55.0, sugars = 5.0, fat = 3.0))
        assertEquals(5.5, r.parse.carbs!!, 0.001)
        assertEquals(listOf(Correction(NutrientField.CARBS, 55.0, 5.5)), r.corrections)
    }

    @Test
    fun energy_mismatch_without_a_clear_culprit_is_left_alone() {
        // 4·P + 4·G + 9·L = 133 pour 250 annoncées : aucune division par 10 ne rétablit l'équation.
        val p = NutritionParse(kcal = 250.0, protein = 1.0, carbs = 30.0, fat = 1.0, sugars = 2.0)
        val r = NutritionCoherence.reconcile(p)
        assertEquals(p, r.parse)
        assertTrue(Problem.ENERGY_MISMATCH in NutritionCoherence.problems(p))
    }

    @Test
    fun values_over_100g_or_900kcal_are_scaled_down() {
        val r = NutritionCoherence.reconcile(NutritionParse(kcal = 2500.0, protein = 1200.0, carbs = 30.0, fat = 9.5))
        assertEquals(250.0, r.parse.kcal!!, 0.001)
        assertEquals(12.0, r.parse.protein!!, 0.001)
    }

    @Test
    fun alcohol_and_polyols_do_not_trigger_a_correction() {
        val beer = NutritionParse(kcal = 43.0, protein = 0.5, carbs = 3.5, sugars = 0.1, fat = 0.0)
        assertEquals(beer, NutritionCoherence.reconcile(beer).parse)
        val gum = NutritionParse(kcal = 160.0, protein = 0.0, carbs = 65.0, sugars = 0.0, fat = 0.5)
        assertEquals(gum, NutritionCoherence.reconcile(gum).parse)
    }

    @Test
    fun problems_report_what_could_not_be_fixed() {
        val p = NutritionParse(kcal = 100.0, protein = 60.0, carbs = 60.0, sugars = 70.0, fat = 1.0)
        val problems = NutritionCoherence.problems(p)
        assertTrue(Problem.SUGARS_OVER_CARBS in problems)
        assertTrue(Problem.SUM_OVER_100 in problems)
        assertTrue(Problem.ENERGY_MISMATCH in problems)
    }
}
