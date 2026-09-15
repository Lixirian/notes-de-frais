package com.lixirian.notesdefrais.ui.list

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lixirian.notesdefrais.data.AiProvider
import com.lixirian.notesdefrais.data.Expense
import com.lixirian.notesdefrais.data.ExpenseRepository
import com.lixirian.notesdefrais.data.SettingsRepository
import com.lixirian.notesdefrais.export.CsvExporter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

data class MonthGroup(
    val month: YearMonth,
    val expenses: List<Expense>,
) {
    val totalCents: Long = expenses.sumOf { it.amountCents }
    val vatCents: Long = expenses.sumOf { it.vatCents ?: 0L }
}

data class ListUiState(
    val loaded: Boolean = false,
    val groups: List<MonthGroup> = emptyList(),
    val aiProvider: AiProvider? = null,
) {
    val currentMonth: YearMonth = YearMonth.now()
    val currentGroup: MonthGroup? get() = groups.firstOrNull { it.month == currentMonth }
    val isEmpty: Boolean get() = loaded && groups.isEmpty()
}

class ExpenseListViewModel(
    private val repository: ExpenseRepository,
    settings: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<ListUiState> = combine(repository.observeAll(), settings.config) { expenses, config ->
        ListUiState(
            loaded = true,
            groups = expenses.groupBy { it.yearMonth }
                .toSortedMap(compareByDescending { it })
                .map { (month, list) -> MonthGroup(month, list) },
            aiProvider = config.provider,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListUiState())

    fun delete(expense: Expense, onDeleted: (Expense) -> Unit) {
        viewModelScope.launch {
            repository.moveToTrash(expense)
            onDeleted(expense)
        }
    }

    /** Sort la dépense de la corbeille (justificatif conservé). */
    fun restore(expense: Expense) {
        viewModelScope.launch { repository.restore(expense.uid) }
    }

    suspend fun exportMonth(context: Context, group: MonthGroup): Intent {
        val uri = CsvExporter.exportMonth(context, group.month, group.expenses.sortedBy { it.dateEpochDay })
        return CsvExporter.shareIntent(uri, group.month)
    }
}
