package com.lixirian.notesdefrais.ui.archive

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lixirian.notesdefrais.data.Category
import com.lixirian.notesdefrais.data.Expense
import com.lixirian.notesdefrais.data.ExpenseRepository
import com.lixirian.notesdefrais.export.CsvExporter
import com.lixirian.notesdefrais.ui.components.Formatters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Filtres de l'écran Justificatifs. `null` = pas de filtre sur ce critère. */
data class ArchiveFilter(
    val year: Int? = null,
    val month: Month? = null,
    val category: Category? = null,
    val withReceiptOnly: Boolean = false,
) {
    fun matches(e: Expense): Boolean =
        (year == null || e.date.year == year) &&
            (month == null || e.date.month == month) &&
            (category == null || e.category == category) &&
            (!withReceiptOnly || e.receiptPath != null)

    /** Libellé humain du filtre, réutilisé pour le nom du CSV et le titre du partage. */
    val label: String
        get() {
            val parts = buildList {
                if (month != null && year != null) add(Formatters.monthLabel(YearMonth.of(year, month)))
                else if (year != null) add(year.toString())
                else if (month != null) add(month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.FRENCH).replaceFirstChar { it.titlecase(Locale.FRENCH) })
                if (category != null) add(category.label)
                if (withReceiptOnly) add("avec justificatif")
            }
            return if (parts.isEmpty()) "Toutes les dépenses" else parts.joinToString(" · ")
        }

    val fileSuffix: String
        get() = buildList {
            year?.let { add(it.toString()) }
            month?.let { add(it.value.toString().padStart(2, '0')) }
            category?.let { add(it.name.lowercase()) }
        }.ifEmpty { listOf("tout") }.joinToString("-")
}

data class ArchiveUiState(
    val loaded: Boolean = false,
    val filter: ArchiveFilter = ArchiveFilter(),
    val availableYears: List<Int> = emptyList(),
    val expenses: List<Expense> = emptyList(),
) {
    val totalCents: Long get() = expenses.sumOf { it.amountCents }
    val vatCents: Long get() = expenses.sumOf { it.vatCents ?: 0L }
    val receiptCount: Int get() = expenses.count { it.receiptPath != null }
}

class ArchiveViewModel(private val repository: ExpenseRepository) : ViewModel() {

    private val filter = MutableStateFlow(ArchiveFilter())

    val uiState: StateFlow<ArchiveUiState> = combine(repository.observeAll(), filter) { all, f ->
        ArchiveUiState(
            loaded = true,
            filter = f,
            availableYears = all.map { it.date.year }.distinct().sortedDescending(),
            expenses = all.filter(f::matches).sortedWith(compareByDescending<Expense> { it.dateEpochDay }.thenByDescending { it.createdAt }),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArchiveUiState())

    fun setYear(year: Int?) = filter.update { it.copy(year = year) }
    fun setMonth(month: Month?) = filter.update { it.copy(month = month) }
    fun setCategory(category: Category?) = filter.update { it.copy(category = category) }
    fun setWithReceiptOnly(value: Boolean) = filter.update { it.copy(withReceiptOnly = value) }
    fun reset() = filter.update { ArchiveFilter() }

    suspend fun exportFiltered(context: Context): Intent {
        val state = uiState.value
        val uri = CsvExporter.export(context, "notes-de-frais-${state.filter.fileSuffix}", state.expenses.sortedBy { it.dateEpochDay })
        return CsvExporter.shareIntent(uri, "Notes de frais — ${state.filter.label}")
    }
}
