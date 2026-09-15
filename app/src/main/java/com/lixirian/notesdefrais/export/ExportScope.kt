package com.lixirian.notesdefrais.export

import com.lixirian.notesdefrais.data.Expense
import com.lixirian.notesdefrais.ui.components.Formatters
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** Périodes d'export proposées dans l'app. */
sealed interface ExportScope {
    val label: String
    val fileSuffix: String
    fun matches(e: Expense): Boolean

    data class Week(val start: LocalDate) : ExportScope {
        private val end: LocalDate = start.plusDays(6)
        override val label: String get() = "Semaine du ${start.format(DateTimeFormatter.ofPattern("d MMM", java.util.Locale.FRENCH))} au ${end.format(DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.FRENCH))}"
        override val fileSuffix: String get() = "semaine-${start.format(DateTimeFormatter.ISO_LOCAL_DATE)}"
        override fun matches(e: Expense): Boolean = !e.date.isBefore(start) && !e.date.isAfter(end)
    }

    data class Month(val month: YearMonth) : ExportScope {
        override val label: String get() = Formatters.monthLabel(month)
        override val fileSuffix: String get() = month.toString()
        override fun matches(e: Expense): Boolean = e.yearMonth == month
    }

    data object All : ExportScope {
        override val label: String get() = "Toutes les dépenses"
        override val fileSuffix: String get() = "tout"
        override fun matches(e: Expense): Boolean = true
    }

    data class Single(val uid: String, val title: String) : ExportScope {
        override val label: String get() = title
        override val fileSuffix: String get() = "ticket-${uid.take(8)}"
        override fun matches(e: Expense): Boolean = e.uid == uid
    }

    data class Selection(val uids: Set<String>, override val label: String, override val fileSuffix: String) : ExportScope {
        override fun matches(e: Expense): Boolean = e.uid in uids
    }

    companion object {
        fun thisWeek(today: LocalDate = LocalDate.now()) = Week(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
        fun lastWeek(today: LocalDate = LocalDate.now()) = Week(thisWeek(today).start.minusWeeks(1))
        fun thisMonth(today: LocalDate = LocalDate.now()) = Month(YearMonth.from(today))
        fun lastMonth(today: LocalDate = LocalDate.now()) = Month(YearMonth.from(today).minusMonths(1))
    }
}
