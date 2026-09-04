package com.maitre.nopainnoscan

import java.text.NumberFormat
import java.util.Locale

/** Formats d'affichage français (virgule décimale, séparateur de milliers). */
object Fmt {
    private val ints = NumberFormat.getIntegerInstance(Locale.FRANCE)

    fun int(value: Int): String = ints.format(value)

    fun dec1(value: Double): String = String.format(Locale.FRANCE, "%.1f", value)

    /** Valeur saisie (virgule ou point) -> Double. */
    fun parse(text: CharSequence?): Double? = text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()

    /** Valeur pour un champ de saisie : pas de séparateur de milliers, virgule si décimale. */
    fun field(value: Double?): String = when {
        value == null -> ""
        value % 1.0 == 0.0 -> value.toInt().toString()
        else -> dec1(value)
    }
}
