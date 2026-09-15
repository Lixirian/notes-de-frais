package com.lixirian.notesdefrais.ui.components

import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatters {

    private val currency: NumberFormat = NumberFormat.getCurrencyInstance(Locale.FRANCE)
    private val monthFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.FRENCH)
    private val dayFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)
    private val fullDayFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)

    fun euros(cents: Long): String = currency.format(cents / 100.0)

    /** "2,8 Mo" / "512 Ko" pour la taille d'un fichier. */
    fun fileSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(Locale.FRENCH, "%.1f Mo", bytes / 1_000_000.0)
        bytes >= 1_000 -> "${bytes / 1_000} Ko"
        else -> "$bytes o"
    }

    fun monthLabel(month: YearMonth): String =
        month.format(monthFormatter).replaceFirstChar { it.titlecase(Locale.FRENCH) }

    fun shortDay(date: LocalDate): String = date.format(dayFormatter).replace(".", "")

    fun fullDay(date: LocalDate): String =
        date.format(fullDayFormatter).replaceFirstChar { it.titlecase(Locale.FRENCH) }

    /** "12,50" pour la saisie / le CSV (virgule décimale, sans symbole). */
    fun csvAmount(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        return "$sign${abs / 100},${(abs % 100).toString().padStart(2, '0')}"
    }

    /** Accepte "12,50", "12.50", "12", " 1 234,5 " ; null si invalide. */
    fun parseCents(input: String): Long? {
        val cleaned = input.trim().replace(" ", "").replace(" ", "").replace("€", "").replace(",", ".")
        if (cleaned.isEmpty()) return null
        val value = cleaned.toDoubleOrNull() ?: return null
        return Math.round(value * 100)
    }
}
