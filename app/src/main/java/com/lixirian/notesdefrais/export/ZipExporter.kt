package com.lixirian.notesdefrais.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.lixirian.notesdefrais.data.Expense
import com.lixirian.notesdefrais.ui.components.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export complet en `.zip` : un dossier par ticket (`AAAA-MM-JJ_commerçant_montant/`) contenant
 * l'image du justificatif, `depense.json` (toutes les données, TVA par taux comprise) et
 * `depense.txt` (lisible), plus un `notes-de-frais.csv` récapitulatif à la racine.
 */
object ZipExporter {

    @Serializable
    private data class VatLineExport(val tauxPourcent: Double, val baseHtEuros: String?, val tvaEuros: String)

    @Serializable
    private data class ExpenseExport(
        val identifiant: String,
        val date: String,
        val commercant: String,
        val categorie: String,
        val montantTtcEuros: String,
        val montantHtEuros: String,
        val tvaEuros: String?,
        val lignesTva: List<VatLineExport>,
        val modePaiement: String?,
        val numeroTicket: String?,
        val note: String,
        val saisie: String,
        val justificatif: String?,
    )

    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE

    suspend fun export(context: Context, baseName: String, label: String, expenses: List<Expense>): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val zipFile = File(dir, "$baseName.zip")
        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zip ->
            // Récapitulatif CSV à la racine
            zip.putNextEntry(ZipEntry("notes-de-frais.csv"))
            zip.write(CsvExporter.buildCsv(expenses).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("LISEZMOI.txt"))
            zip.write(readme(label, expenses).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            val usedNames = mutableSetOf<String>()
            expenses.sortedBy { it.dateEpochDay }.forEach { e ->
                var folder = folderName(e)
                var n = 2
                while (!usedNames.add(folder)) { folder = "${folderName(e)}-$n"; n++ }

                zip.putNextEntry(ZipEntry("$folder/depense.json"))
                zip.write(json.encodeToString(ExpenseExport.serializer(), toExport(e)).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("$folder/depense.txt"))
                zip.write(toText(e).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                e.receiptPath?.let(::File)?.takeIf { it.exists() }?.let { image ->
                    zip.putNextEntry(ZipEntry("$folder/ticket.jpg"))
                    image.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
    }

    fun shareIntent(uri: Uri, subject: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Exporter l'archive ZIP")
    }

    private fun folderName(e: Expense): String {
        val merchant = slug(e.merchant.ifBlank { "sans-commercant" }).take(40)
        return "${e.date.format(dateFormat)}_${merchant}_${Formatters.csvAmount(e.amountCents).replace(',', '-')}"
    }

    private fun slug(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^A-Za-z0-9]+"), "-")
        .trim('-')
        .lowercase()

    private fun toExport(e: Expense) = ExpenseExport(
        identifiant = e.uid,
        date = e.date.format(dateFormat),
        commercant = e.merchant,
        categorie = e.category.label,
        montantTtcEuros = Formatters.csvAmount(e.amountCents),
        montantHtEuros = Formatters.csvAmount(e.amountHtCentsOrDerived),
        tvaEuros = e.vatCents?.let { Formatters.csvAmount(it) },
        lignesTva = e.vatLines.map { VatLineExport(it.ratePercent, it.baseCents?.let { b -> Formatters.csvAmount(b) }, Formatters.csvAmount(it.vatCents)) },
        modePaiement = e.paymentMethod?.label,
        numeroTicket = e.invoiceNumber,
        note = e.note,
        saisie = if (e.aiExtracted) "IA" else "manuelle",
        justificatif = if (e.receiptPath != null) "ticket.jpg" else null,
    )

    private fun toText(e: Expense): String = buildString {
        appendLine("Dépense du ${Formatters.fullDay(e.date)}")
        appendLine("Commerçant : ${e.merchant}")
        appendLine("Catégorie  : ${e.category.label}")
        appendLine("Montant TTC : ${Formatters.euros(e.amountCents)}")
        appendLine("Montant HT  : ${Formatters.euros(e.amountHtCentsOrDerived)}")
        appendLine("TVA totale  : ${e.vatCents?.let { Formatters.euros(it) } ?: "—"}")
        if (e.vatLines.isNotEmpty()) {
            appendLine("Détail TVA :")
            e.vatLines.forEach { l -> appendLine("  - ${l.rateLabel} : base HT ${l.baseCents?.let { Formatters.euros(it) } ?: "—"}, TVA ${Formatters.euros(l.vatCents)}") }
        }
        e.paymentMethod?.let { appendLine("Paiement   : ${it.label}") }
        e.invoiceNumber?.let { appendLine("N° ticket  : $it") }
        if (e.note.isNotBlank()) appendLine("Note       : ${e.note}")
        appendLine("Saisie     : ${if (e.aiExtracted) "pré-remplie par l'IA" else "manuelle"}")
        appendLine("Justificatif : ${if (e.receiptPath != null) "ticket.jpg" else "aucun"}")
    }

    private fun readme(label: String, expenses: List<Expense>): String = buildString {
        appendLine("Mes notes de frais — export « $label »")
        appendLine("${expenses.size} dépense(s), total TTC ${Formatters.euros(expenses.sumOf { it.amountCents })}, TVA ${Formatters.euros(expenses.sumOf { it.vatCents ?: 0L })}")
        appendLine()
        appendLine("notes-de-frais.csv : récapitulatif (séparateur ;, virgule décimale, ouvrable dans Excel).")
        appendLine("Un dossier par ticket : ticket.jpg (justificatif), depense.json (données), depense.txt (lisible).")
    }
}
