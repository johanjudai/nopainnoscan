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
 * bande horizontale, on lit ensuite chaque rangée de gauche à droite. La colonne
 * « pour 100 g » est la première après le libellé, sauf si l'en-tête annonce la portion avant.
 */
object NutritionParser {

    private val NUMBER = Regex("""<?\s*(\d+(?:[.,]\d+)?)\s*(mg|g|kcal|kj|%)?""")
    private val KCAL = Regex("""(\d+(?:[.,]\d+)?)\s*kcal""")
    private val DIACRITICS = Regex("\\p{Mn}+")
    // « 8 5 g » : la virgule a sauté à l'OCR ; un chiffre seul devant l'unité n'existe pas sinon.
    private val LOST_COMMA = Regex("""(\d+) (\d)(?=\s*(?:g|mg)\b)""")
    private val LETTER_DIGIT = Regex(
        """(?<=\d)[oli](?=[\d.,])|(?<![a-z])[oli](?=\d)|(?<=\d[.,]?)[oli](?=\s*(?:g|mg|kcal|kj)\b)"""
    )
    private val HEADER_100 = Regex("""\b100\s*(?:g|ml)\b""")
    private val HEADER_PORTION = Regex("""portion|part\b|unite|piece|sachet|barre|verre|tranche|pot\b""")

    // Ordre important : les libellés « dont … » doivent gagner sur leur parent.
    private val LABELS: List<Pair<Regex, (NutritionParse, Double) -> NutritionParse>> = listOf(
        Regex("""satur""") to { p, v -> if (p.satFat == null) p.copy(satFat = v) else p },
        Regex("""sucre|sugar""") to { p, v -> if (p.sugars == null) p.copy(sugars = v) else p },
        Regex("""fibre|fiber""") to { p, v -> if (p.fiber == null) p.copy(fiber = v) else p },
        Regex("""prot""") to { p, v -> if (p.protein == null) p.copy(protein = v) else p },
        Regex("""matiere|lipide|\bfat\b|graisse""") to { p, v -> if (p.fat == null) p.copy(fat = v) else p },
        Regex("""glucide|carbohydrate""") to { p, v -> if (p.carbs == null) p.copy(carbs = v) else p },
        Regex("""\b(sel|salt)\b""") to { p, v -> if (p.salt == null) p.copy(salt = v) else p },
        // Étiquettes anciennes ou hors UE : sel = sodium × 2,5. Le sel explicite garde la priorité.
        Regex("""sodium""") to { p, v -> if (p.salt == null) p.copy(salt = v * 2.5) else p },
    )

    /** Lecture brute puis mise en cohérence : voir [NutritionCoherence]. */
    fun parse(lines: List<OcrLine>): NutritionParse = parseDetailed(lines).parse

    /** Comme [parse], avec la liste des corrections appliquées (à montrer à l'utilisateur). */
    fun parseDetailed(lines: List<OcrLine>): Reconciled = NutritionCoherence.reconcile(parseRaw(lines))

    internal fun parseRaw(lines: List<OcrLine>): NutritionParse {
        val rows = rows(lines).map(::normalize)
        val column = if (rows.any(::portionBefore100)) 1 else 0
        var parse = NutritionParse()
        for (text in rows) {
            if (parse.kcal == null) {
                KCAL.find(text)?.let { parse = parse.copy(kcal = it.groupValues[1].toNum()) }
            }
            for ((label, apply) in LABELS) {
                val match = label.find(text) ?: continue
                val values = valuesAfter(text, match.range.last + 1)
                // Colonne demandée absente (portion non imprimée sur cette rangée) : on prend ce qu'il y a.
                val value = values.getOrNull(column) ?: values.firstOrNull() ?: break
                parse = apply(parse, value)
                break // une rangée = un nutriment
            }
        }
        return parse
    }

    /** En-tête « par portion … pour 100 g » : la colonne 100 g est la deuxième. */
    private fun portionBefore100(row: String): Boolean {
        val at100 = HEADER_100.find(row)?.range?.first ?: return false
        val atPortion = HEADER_PORTION.find(row)?.range?.first ?: return false
        return atPortion < at100
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

    /** Toutes les quantités en g après le libellé, dans l'ordre des colonnes (énergie et % AR ignorés). */
    private fun valuesAfter(text: String, from: Int): List<Double> {
        val values = mutableListOf<Double>()
        var index = from
        while (index < text.length) {
            val match = NUMBER.find(text, index) ?: break
            val unit = match.groupValues[2]
            index = match.range.last + 1
            if (unit == "kj" || unit == "kcal" || unit == "%") continue
            val value = match.groupValues[1].toNum()
            values += if (unit == "mg") value / 1000 else value
        }
        return values
    }

    /** Minuscules sans accents ; lettres lues à la place de chiffres (o→0, l/i→1) ; virgule perdue. */
    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase()
            .replace(LETTER_DIGIT) { if (it.value == "o") "0" else "1" }
            .replace(LOST_COMMA) { "${it.groupValues[1]}.${it.groupValues[2]}" }

    private fun String.toNum(): Double = replace(',', '.').toDouble()
}
