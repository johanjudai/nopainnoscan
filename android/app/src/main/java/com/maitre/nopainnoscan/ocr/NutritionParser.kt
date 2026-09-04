package com.maitre.nopainnoscan.ocr

import java.text.Normalizer

/** Une ligne reconnue par l'OCR avec sa boîte englobante (coordonnées image). */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = bottom - top
}

/** Valeurs pour 100 g lues sur l'étiquette ; null = non trouvée. */
data class NutritionParse(
    val kcal: Double? = null,
    val protein: Double? = null,
    val carbs: Double? = null,
    val sugars: Double? = null,
    val fat: Double? = null,
    val satFat: Double? = null,
    val fiber: Double? = null,
    val salt: Double? = null,
) {
    val fieldCount: Int
        get() = listOf(kcal, protein, carbs, sugars, fat, satFat, fiber, salt).count { it != null }

    /** Assez d'informations pour proposer une note : kcal + trois autres champs. */
    val isUsable: Boolean get() = kcal != null && fieldCount >= 4

    /** Deux lectures « identiques » à 5 % près sur kcal et protéines : la lecture est stable. */
    fun matches(other: NutritionParse): Boolean =
        close(kcal, other.kcal) && close(protein, other.protein) && fieldCount == other.fieldCount

    private fun close(a: Double?, b: Double?): Boolean =
        a != null && b != null && kotlin.math.abs(a - b) <= 0.05 * maxOf(a, b, 1.0)
}

/**
 * Tableau nutritionnel -> valeurs. ML Kit découpe une ligne du tableau en plusieurs
 * blocs (libellé, colonne 100 g, colonne portion) : on regroupe d'abord les lignes par
 * bande horizontale, on lit ensuite chaque rangée de gauche à droite. La première valeur
 * après le libellé est la colonne « pour 100 g », qui précède la portion sur les étiquettes UE.
 */
object NutritionParser {

    private val NUMBER = Regex("""<?\s*(\d+(?:[.,]\d+)?)\s*(mg|g|kcal|kj|%)?""")
    private val KCAL = Regex("""(\d+(?:[.,]\d+)?)\s*kcal""")
    private val DIACRITICS = Regex("\\p{Mn}+")

    // Ordre important : les libellés « dont … » doivent gagner sur leur parent.
    private val LABELS: List<Pair<Regex, (NutritionParse, Double) -> NutritionParse>> = listOf(
        Regex("""satur""") to { p, v -> if (p.satFat == null) p.copy(satFat = v) else p },
        Regex("""sucre|sugar""") to { p, v -> if (p.sugars == null) p.copy(sugars = v) else p },
        Regex("""fibre|fiber""") to { p, v -> if (p.fiber == null) p.copy(fiber = v) else p },
        Regex("""prot""") to { p, v -> if (p.protein == null) p.copy(protein = v) else p },
        Regex("""matiere|lipide|\bfat\b|graisse""") to { p, v -> if (p.fat == null) p.copy(fat = v) else p },
        Regex("""glucide|carbohydrate""") to { p, v -> if (p.carbs == null) p.copy(carbs = v) else p },
        Regex("""\b(sel|salt)\b""") to { p, v -> if (p.salt == null) p.copy(salt = v) else p },
    )

    fun parse(lines: List<OcrLine>): NutritionParse {
        var parse = NutritionParse()
        for (row in rows(lines)) {
            val text = normalize(row)
            if (parse.kcal == null) {
                KCAL.find(text)?.let { parse = parse.copy(kcal = it.groupValues[1].toNum()) }
            }
            for ((label, apply) in LABELS) {
                val match = label.find(text) ?: continue
                val value = firstValueAfter(text, match.range.last + 1) ?: break
                parse = apply(parse, value)
                break // une rangée = un nutriment
            }
        }
        return parse
    }

    /** Regroupe les lignes par bande horizontale (tolérance : 60 % de la hauteur médiane). */
    internal fun rows(lines: List<OcrLine>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val tolerance = (lines.map { it.height }.sorted()[lines.size / 2] * 0.6).toInt().coerceAtLeast(1)
        val groups = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.sortedBy { it.centerY }) {
            val group = groups.lastOrNull()
            if (group != null && kotlin.math.abs(group.last().centerY - line.centerY) <= tolerance) group.add(line)
            else groups.add(mutableListOf(line))
        }
        return groups.map { g -> g.sortedBy { it.left }.joinToString(" ") { it.text } }
    }

    private fun firstValueAfter(text: String, from: Int): Double? {
        var index = from
        while (index < text.length) {
            val match = NUMBER.find(text, index) ?: return null
            val unit = match.groupValues[2]
            index = match.range.last + 1
            if (unit == "kj" || unit == "kcal" || unit == "%") continue // énergie ou % des AR
            val value = match.groupValues[1].toNum()
            return if (unit == "mg") value / 1000 else value
        }
        return null
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase()

    private fun String.toNum(): Double = replace(',', '.').toDouble()
}
