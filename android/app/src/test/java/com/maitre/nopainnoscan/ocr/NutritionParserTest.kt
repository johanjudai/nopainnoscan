package com.maitre.nopainnoscan.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionParserTest {

    /** Une rangée du tableau, découpée en cellules comme le fait ML Kit. */
    private fun row(y: Int, vararg cells: String): List<OcrLine> =
        cells.mapIndexed { i, text -> OcrLine(text, left = i * 300, top = y, right = i * 300 + 250, bottom = y + 24) }

    @Test
    fun parses_french_table_split_in_cells() {
        val lines = row(0, "Valeurs nutritionnelles", "Pour 100 g", "Par portion (50 g)") +
            row(40, "Énergie", "1046 kJ / 250 kcal", "523 kJ / 125 kcal") +
            row(80, "Matières grasses", "9,5 g", "4,8 g") +
            row(120, "dont acides gras saturés", "3,2 g", "1,6 g") +
            row(160, "Glucides", "30 g", "15 g") +
            row(200, "dont sucres", "4,1 g", "2 g") +
            row(240, "Fibres alimentaires", "2,5 g", "1,3 g") +
            row(280, "Protéines", "11 g", "5,5 g") +
            row(320, "Sel", "1,2 g", "0,6 g")

        val p = NutritionParser.parse(lines.shuffled())
        assertEquals(250.0, p.kcal!!, 0.01)
        assertEquals(9.5, p.fat!!, 0.01)
        assertEquals(3.2, p.satFat!!, 0.01)
        assertEquals(30.0, p.carbs!!, 0.01)
        assertEquals(4.1, p.sugars!!, 0.01)
        assertEquals(2.5, p.fiber!!, 0.01)
        assertEquals(11.0, p.protein!!, 0.01)
        assertEquals(1.2, p.salt!!, 0.01)
        assertTrue(p.isUsable)
    }

    @Test
    fun parses_english_single_lines_and_ignores_percentages() {
        val lines = listOf(
            OcrLine("Energy 420 kJ / 100 kcal 5%", 0, 0, 500, 20),
            OcrLine("Fat 0.2 g <1%", 0, 30, 500, 50),
            OcrLine("of which saturates 0.1 g", 0, 60, 500, 80),
            OcrLine("Carbohydrate 4.0 g 2%", 0, 90, 500, 110),
            OcrLine("of which sugars 4.0 g", 0, 120, 500, 140),
            OcrLine("Protein 10.5 g 21%", 0, 150, 500, 170),
            OcrLine("Salt 0.10 g", 0, 180, 500, 200),
        )
        val p = NutritionParser.parse(lines)
        assertEquals(100.0, p.kcal!!, 0.01)
        assertEquals(0.2, p.fat!!, 0.01)
        assertEquals(0.1, p.satFat!!, 0.01)
        assertEquals(4.0, p.carbs!!, 0.01)
        assertEquals(4.0, p.sugars!!, 0.01)
        assertEquals(10.5, p.protein!!, 0.01)
        assertEquals(0.1, p.salt!!, 0.01)
        assertNull(p.fiber)
    }

    @Test
    fun handles_less_than_and_milligrams() {
        val lines = listOf(
            OcrLine("Énergie 63 kcal", 0, 0, 500, 20),
            OcrLine("Sucres <0,5 g", 0, 30, 500, 50),
            OcrLine("Sel 250 mg", 0, 60, 500, 80),
            OcrLine("Protéines 10 g", 0, 90, 500, 110),
        )
        val p = NutritionParser.parse(lines)
        assertEquals(0.5, p.sugars!!, 0.01)
        assertEquals(0.25, p.salt!!, 0.001)
        assertEquals(10.0, p.protein!!, 0.01)
    }

    @Test
    fun not_usable_without_kcal_or_enough_fields() {
        val noKcal = NutritionParser.parse(listOf(OcrLine("Protéines 10 g", 0, 0, 100, 20)))
        assertFalse(noKcal.isUsable)
        val tooFew = NutritionParser.parse(listOf(OcrLine("Énergie 100 kcal", 0, 0, 100, 20)))
        assertFalse(tooFew.isUsable)
    }

    @Test
    fun header_with_portion_before_100g_reads_the_second_column() {
        val lines = row(0, "Valeurs moyennes", "Par portion (30 g)", "Pour 100 g") +
            row(40, "Énergie", "138 kcal", "460 kcal") +
            row(80, "Matières grasses", "5,7 g", "19 g") +
            row(120, "Glucides", "19,5 g", "65 g") +
            row(160, "Protéines", "1,8 g", "6 g")
        val p = NutritionParser.parse(lines)
        assertEquals(19.0, p.fat!!, 0.01)
        assertEquals(65.0, p.carbs!!, 0.01)
        assertEquals(6.0, p.protein!!, 0.01)
    }

    @Test
    fun sodium_is_converted_to_salt_unless_salt_is_printed() {
        val sodiumOnly = NutritionParser.parse(listOf(OcrLine("Sodium 0,4 g", 0, 0, 300, 20)))
        assertEquals(1.0, sodiumOnly.salt!!, 0.01)
        val both = NutritionParser.parse(
            listOf(OcrLine("Sel 1,2 g", 0, 0, 300, 20), OcrLine("Sodium 0,48 g", 0, 30, 300, 50))
        )
        assertEquals(1.2, both.salt!!, 0.01)
    }

    @Test
    fun lost_comma_and_letters_for_digits_are_repaired() {
        val lines = listOf(
            OcrLine("Énergie 25O kcal", 0, 0, 400, 20),
            OcrLine("Matières grasses 9 5 g", 0, 30, 400, 50),
            OcrLine("Glucides 3O g", 0, 60, 400, 80),
            OcrLine("Protéines l1 g", 0, 90, 400, 110),
        )
        val p = NutritionParser.parse(lines)
        assertEquals(250.0, p.kcal!!, 0.01)
        assertEquals(9.5, p.fat!!, 0.01)
        assertEquals(30.0, p.carbs!!, 0.01)
        assertEquals(11.0, p.protein!!, 0.01)
    }

    @Test
    fun parse_applies_coherence_and_reports_corrections() {
        val lines = row(0, "Énergie", "110 kcal") +
            row(40, "Matières grasses", "0,5 g") +
            row(80, "Glucides", "24 g") +
            row(120, "dont sucres", "85 g") + // virgule perdue : 8,5 g
            row(160, "Protéines", "3 g")
        val r = NutritionParser.parseDetailed(lines)
        assertEquals(8.5, r.parse.sugars!!, 0.01)
        assertEquals(listOf(Correction(NutrientField.SUGARS, 85.0, 8.5)), r.corrections)
    }

    @Test
    fun stability_compares_kcal_and_protein_within_5_percent() {
        val a = NutritionParse(kcal = 250.0, protein = 11.0, fat = 9.5, carbs = 30.0)
        val b = NutritionParse(kcal = 258.0, protein = 11.2, fat = 9.0, carbs = 31.0)
        val c = NutritionParse(kcal = 300.0, protein = 11.0, fat = 9.5, carbs = 30.0)
        assertTrue(a.matches(b))
        assertFalse(a.matches(c))
    }
}
