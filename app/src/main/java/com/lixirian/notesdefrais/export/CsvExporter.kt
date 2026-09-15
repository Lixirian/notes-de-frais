package com.lixirian.notesdefrais.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.lixirian.notesdefrais.data.Expense
import com.lixirian.notesdefrais.ui.components.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Export CSV pensé pour Excel/Numbers en français : séparateur `;`, virgule décimale,
 * BOM UTF-8 (sinon Excel affiche les accents en vrac), ligne TOTAL en fin de fichier.
 * La TVA est ventilée par taux (une colonne par taux rencontré dans l'export).
 */
object CsvExporter {

    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    suspend fun exportMonth(context: Context, month: YearMonth, expenses: List<Expense>): Uri =
        export(context, "notes-de-frais-$month", expenses)

    /** Écrit `<baseName>.csv` dans le cache et renvoie son URI partageable. */
    suspend fun export(context: Context, baseName: String, expenses: List<Expense>): Uri =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "$baseName.csv")
            file.writeText(buildCsv(expenses), Charsets.UTF_8)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

    /** Contenu CSV complet (BOM inclus). */
    fun buildCsv(expenses: List<Expense>): String {
        val rates = expenses.flatMap { e -> e.vatLines.map { it.ratePercent } }.distinct().sortedDescending()
        val rateHeaders = rates.map { r -> "TVA ${rateLabel(r)}" }
        val sb = StringBuilder()
        sb.append("﻿")
        sb.append(
            (listOf("Date", "Commerçant", "Catégorie", "Montant TTC", "Montant HT", "TVA totale") + rateHeaders +
                listOf("Mode de paiement", "N° ticket", "Note", "Justificatif", "Saisie")).joinToString(";"),
        ).append("\r\n")
        expenses.forEach { e ->
            val byRate = e.vatLines.groupBy { it.ratePercent }.mapValues { (_, lines) -> lines.sumOf { it.vatCents } }
            val cells = listOf(
                e.date.format(dateFormat),
                neutralizeFormula(e.merchant),
                e.category.label,
                Formatters.csvAmount(e.amountCents),
                Formatters.csvAmount(e.amountHtCentsOrDerived),
                e.vatCents?.let { Formatters.csvAmount(it) } ?: "",
            ) + rates.map { r -> byRate[r]?.let { Formatters.csvAmount(it) } ?: "" } + listOf(
                e.paymentMethod?.label ?: "",
                neutralizeFormula(e.invoiceNumber.orEmpty()),
                neutralizeFormula(e.note),
                if (e.receiptPath != null) "oui" else "non",
                if (e.aiExtracted) "IA" else "manuelle",
            )
            sb.append(cells.joinToString(";") { escape(it) }).append("\r\n")
        }
        val total = expenses.sumOf { it.amountCents }
        val totalHt = expenses.sumOf { it.amountHtCentsOrDerived }
        val vat = expenses.sumOf { it.vatCents ?: 0L }
        val totalByRate = rates.map { r -> expenses.sumOf { e -> e.vatLines.filter { it.ratePercent == r }.sumOf { it.vatCents } } }
        sb.append(
            (listOf("TOTAL", "", "", Formatters.csvAmount(total), Formatters.csvAmount(totalHt), Formatters.csvAmount(vat)) +
                totalByRate.map { Formatters.csvAmount(it) } + listOf("", "", "", "", "")).joinToString(";"),
        ).append("\r\n")
        return sb.toString()
    }

    private fun rateLabel(rate: Double): String =
        if (rate % 1.0 == 0.0) "${rate.toInt()} %" else "${rate.toString().replace('.', ',')} %"

    fun shareIntent(uri: Uri, month: YearMonth): Intent = shareIntent(uri, "Notes de frais ${Formatters.monthLabel(month)}")

    fun shareIntent(uri: Uri, subject: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Exporter le CSV")
    }

    /**
     * Anti « CSV injection » : un texte commençant par = + - @ ou une tabulation serait exécuté
     * comme formule par Excel/LibreOffice. Un commerçant ou une note (potentiellement dictés par
     * un ticket malveillant via l'IA) sont donc préfixés d'une apostrophe.
     */
    private fun neutralizeFormula(cell: String): String =
        if (cell.isNotEmpty() && cell[0] in FORMULA_STARTERS) "'" + cell else cell

    private val FORMULA_STARTERS = setOf('=', '+', '-', '@', '\t', '\r')

    private fun escape(cell: String): String {
        val needsQuotes = cell.any { it == ';' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
    }
}
