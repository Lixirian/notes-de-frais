package com.lixirian.notesdefrais.ui.edit

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lixirian.notesdefrais.ai.ReceiptAnalysisException
import com.lixirian.notesdefrais.ai.ReceiptAnalyzerFactory
import com.lixirian.notesdefrais.ai.ReceiptImages
import com.lixirian.notesdefrais.data.AiProvider
import com.lixirian.notesdefrais.data.Category
import com.lixirian.notesdefrais.data.Expense
import com.lixirian.notesdefrais.data.ExpenseRepository
import com.lixirian.notesdefrais.data.PaymentMethod
import com.lixirian.notesdefrais.data.SettingsRepository
import com.lixirian.notesdefrais.data.VatLine
import com.lixirian.notesdefrais.ui.components.Formatters
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/** Une ligne de TVA en cours de saisie (taux en %, base HT et TVA en texte libre). */
data class VatLineForm(
    val rate: String = "20",
    val base: String = "",
    val vat: String = "",
) {
    val rateValue: Double? get() = rate.trim().replace(',', '.').toDoubleOrNull()
    val baseCents: Long? get() = if (base.isBlank()) null else Formatters.parseCents(base)
    val vatCents: Long? get() = Formatters.parseCents(vat)
    val isValid: Boolean get() = rateValue != null && vatCents != null && (base.isBlank() || baseCents != null)

    fun toVatLine(): VatLine? = if (isValid) VatLine(rateValue!!, baseCents, vatCents!!) else null

    companion object {
        fun rateText(rate: Double): String = if (rate % 1.0 == 0.0) rate.toInt().toString() else rate.toString().replace('.', ',')
        fun from(line: VatLine) = VatLineForm(
            rate = rateText(line.ratePercent),
            base = line.baseCents?.let { Formatters.csvAmount(it) } ?: "",
            vat = Formatters.csvAmount(line.vatCents),
        )
    }
}

data class EditForm(
    val amount: String = "",
    val vat: String = "",
    val amountHt: String = "",
    val vatLines: List<VatLineForm> = emptyList(),
    val date: LocalDate = LocalDate.now(),
    val merchant: String = "",
    val category: Category = Category.AUTRE,
    val paymentMethod: PaymentMethod? = null,
    val invoiceNumber: String = "",
    val note: String = "",
) {
    val amountCents: Long? get() = Formatters.parseCents(amount)
    val amountHtCents: Long? get() = if (amountHt.isBlank()) null else Formatters.parseCents(amountHt)

    /** TVA totale : somme des lignes si elles existent, sinon le champ TVA. */
    val vatCents: Long?
        get() = if (vatLines.isNotEmpty()) vatLines.mapNotNull { it.vatCents }.takeIf { it.size == vatLines.size }?.sum()
        else if (vat.isBlank()) null else Formatters.parseCents(vat)

    val amountError: String? get() = when {
        amount.isBlank() -> "Montant obligatoire"
        amountCents == null -> "Montant invalide"
        else -> null
    }
    val vatError: String? get() = when {
        vatLines.isNotEmpty() && vatLines.any { !it.isValid } -> "Une ligne de TVA est incomplète"
        vatLines.isEmpty() && vat.isNotBlank() && Formatters.parseCents(vat) == null -> "TVA invalide"
        else -> null
    }
    val amountHtError: String? get() = if (amountHt.isNotBlank() && amountHtCents == null) "HT invalide" else null
    val merchantError: String? get() = if (merchant.isBlank()) "Commerçant obligatoire" else null
    val isValid: Boolean get() = amountError == null && vatError == null && amountHtError == null && merchantError == null

    /** HT affiché : celui saisi, sinon TTC − TVA. */
    val derivedHtCents: Long? get() = amountHtCents ?: amountCents?.let { it - (vatCents ?: 0L) }
}

sealed interface AnalysisState {
    data object Idle : AnalysisState
    data object Importing : AnalysisState
    data object NoKey : AnalysisState
    data class Running(val provider: AiProvider) : AnalysisState
    data class Success(val provider: AiProvider, val filledFields: Int) : AnalysisState
    data class Failed(val message: String) : AnalysisState
}

class ExpenseEditViewModel(
    args: EditArgs,
    private val appContext: Context,
    private val repository: ExpenseRepository,
    private val settings: SettingsRepository,
    private val analyzerFactory: ReceiptAnalyzerFactory,
) : ViewModel() {

    private val expenseId: Long = args.expenseId ?: -1L
    private val incomingImage: String? = args.image?.toString()

    val isExisting: Boolean get() = expenseId >= 0

    var form by mutableStateOf(EditForm())
        private set
    var receiptPath by mutableStateOf<String?>(null)
        private set
    var analysis by mutableStateOf<AnalysisState>(AnalysisState.Idle)
        private set
    var loaded by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set

    private var original: Expense? = null
    private var aiExtracted = false
    private var started = false

    init {
        viewModelScope.launch {
            if (isExisting) {
                repository.byId(expenseId)?.let { e ->
                    original = e
                    aiExtracted = e.aiExtracted
                    receiptPath = e.receiptPath
                    form = EditForm(
                        amount = Formatters.csvAmount(e.amountCents),
                        vat = e.vatCents?.let { Formatters.csvAmount(it) } ?: "",
                        amountHt = e.amountHtCents?.let { Formatters.csvAmount(it) } ?: "",
                        vatLines = e.vatLines.map(VatLineForm::from),
                        date = e.date,
                        merchant = e.merchant,
                        category = e.category,
                        paymentMethod = e.paymentMethod,
                        invoiceNumber = e.invoiceNumber.orEmpty(),
                        note = e.note,
                    )
                }
                loaded = true
            } else {
                loaded = true
                if (incomingImage != null && !started) {
                    started = true
                    importAndAnalyze(Uri.parse(incomingImage))
                }
            }
        }
    }

    fun update(transform: EditForm.() -> EditForm) {
        form = form.transform()
    }

    fun addVatLine() = update {
        val used = vatLines.map { it.rate }
        val next = VatLine.USUAL_RATES.map(VatLineForm::rateText).firstOrNull { it !in used } ?: "20"
        copy(vatLines = vatLines + VatLineForm(rate = next))
    }

    fun updateVatLine(index: Int, transform: VatLineForm.() -> VatLineForm) =
        update { copy(vatLines = vatLines.mapIndexed { i, line -> if (i == index) line.transform() else line }) }

    fun removeVatLine(index: Int) = update { copy(vatLines = vatLines.filterIndexed { i, _ -> i != index }) }

    /** La dépense telle qu'enregistrée (pour l'export ZIP d'une dépense existante). */
    fun currentExpense(): Expense? = original

    private suspend fun importAndAnalyze(uri: Uri) {
        analysis = AnalysisState.Importing
        val file = try {
            ReceiptImages.import(appContext, uri)
        } catch (e: Exception) {
            analysis = AnalysisState.Failed("Impossible de lire la photo : ${e.message ?: "erreur inconnue"}")
            return
        }
        receiptPath = file.absolutePath
        analyzeCurrentReceipt()
    }

    fun retryAnalysis() {
        viewModelScope.launch { analyzeCurrentReceipt() }
    }

    private suspend fun analyzeCurrentReceipt() {
        val path = receiptPath ?: return
        val config = settings.current()
        if (!config.isAiAvailable) {
            analysis = AnalysisState.NoKey
            return
        }
        analysis = AnalysisState.Running(config.provider!!)
        try {
            val analyzer = analyzerFactory.create(config) ?: run { analysis = AnalysisState.NoKey; return }
            val result = analyzer.analyze(File(path).readBytes())
            var filled = 0
            val lines = result.vatLines.mapNotNull { l ->
                val rate = l.rate ?: return@mapNotNull null
                val vat = l.vat ?: return@mapNotNull null
                VatLineForm.from(VatLine(rate, l.baseHt?.let { Math.round(it * 100) }, Math.round(vat * 100)))
            }
            form = form.copy(
                amount = result.amountTtc?.let { filled++; Formatters.csvAmount(Math.round(it * 100)) } ?: form.amount,
                vat = result.vat?.let { filled++; Formatters.csvAmount(Math.round(it * 100)) } ?: form.vat,
                amountHt = result.amountHt?.let { filled++; Formatters.csvAmount(Math.round(it * 100)) } ?: form.amountHt,
                vatLines = if (lines.isNotEmpty()) { filled++; lines } else form.vatLines,
                date = result.parsedDate?.also { filled++ } ?: form.date,
                merchant = result.merchant?.takeIf { it.isNotBlank() }?.also { filled++ } ?: form.merchant,
                category = result.parsedCategory?.also { filled++ } ?: form.category,
                paymentMethod = result.parsedPaymentMethod?.also { filled++ } ?: form.paymentMethod,
                invoiceNumber = result.invoiceNumber?.takeIf { it.isNotBlank() }?.also { filled++ } ?: form.invoiceNumber,
            )
            aiExtracted = filled > 0
            analysis = AnalysisState.Success(analyzer.provider, filled)
        } catch (e: ReceiptAnalysisException) {
            analysis = AnalysisState.Failed(e.message ?: "Analyse impossible")
        } catch (e: Exception) {
            analysis = AnalysisState.Failed("Erreur inattendue : ${e.message ?: e::class.simpleName}")
        }
    }

    fun save(onDone: () -> Unit) {
        val f = form
        if (!f.isValid || saving) return
        saving = true
        viewModelScope.launch {
            val base = original
            // On part de la ligne existante (uid, createdAt, état de synchro) pour ne pas créer de doublon sur le NAS.
            val skeleton = base ?: Expense(amountCents = 0L, vatCents = null, dateEpochDay = f.date.toEpochDay(), merchant = "", category = f.category)
            val expense = skeleton.copy(
                amountCents = f.amountCents ?: 0L,
                vatCents = f.vatCents,
                amountHtCents = f.amountHtCents,
                vatLinesJson = VatLine.encode(f.vatLines.mapNotNull { it.toVatLine() }),
                dateEpochDay = f.date.toEpochDay(),
                merchant = f.merchant.trim(),
                category = f.category,
                paymentMethod = f.paymentMethod,
                invoiceNumber = f.invoiceNumber.trim().ifBlank { null },
                note = f.note.trim(),
                receiptPath = receiptPath,
                aiExtracted = aiExtracted,
            )
            repository.save(expense)
            saving = false
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        val e = original ?: return
        viewModelScope.launch {
            repository.moveToTrash(e)
            onDone()
        }
    }

    /** Si l'utilisateur abandonne une nouvelle dépense, on efface le justificatif importé. */
    fun discardIfNew() {
        if (!isExisting) receiptPath?.let { runCatching { File(it).delete() } }
    }
}
