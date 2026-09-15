package com.lixirian.notesdefrais.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Une dépense. Les montants sont stockés en centimes (Long) pour éviter toute erreur
 * d'arrondi flottant ; la date est stockée en jours epoch (tri et regroupement triviaux).
 *
 * Détail du ticket (quand il est disponible) : lignes de TVA par taux ([vatLinesJson]),
 * total HT lu sur le ticket ([amountHtCents]), mode de paiement, numéro de ticket/facture.
 *
 * Synchronisation NAS : [uid] identifie la dépense sur tous les appareils, [updatedAt] arbitre
 * les conflits (dernière écriture gagne), [dirty] / [receiptDirty] marquent ce qui reste à
 * envoyer au NAS.
 *
 * Corbeille : une suppression pose [deleted] + [deletedAt] mais conserve la ligne et le
 * justificatif pendant [RETENTION_DAYS] jours (restaurable), puis tout est purgé.
 */
@Entity(tableName = "expenses", indices = [Index(value = ["uid"], unique = true)])
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String = newUid(),
    val amountCents: Long,
    /** TVA totale en centimes ; null si inconnue / non applicable. */
    val vatCents: Long?,
    val dateEpochDay: Long,
    val merchant: String,
    val category: Category,
    val note: String = "",
    /** Chemin absolu du justificatif (JPEG) dans le stockage interne, s'il existe. */
    val receiptPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Vrai si les champs ont été pré-remplis par l'IA (même s'ils ont été retouchés ensuite). */
    val aiExtracted: Boolean = false,
    /** Lignes de TVA par taux (JSON de [VatLine]), "[]" si le ticket ne les détaille pas. */
    val vatLinesJson: String = "[]",
    /** Total HT tel que lu sur le ticket ; null si absent (on le déduit alors : TTC − TVA). */
    val amountHtCents: Long? = null,
    val paymentMethod: PaymentMethod? = null,
    val invoiceNumber: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    /** Date de mise à la corbeille (ms epoch), 0 si non supprimée. */
    val deletedAt: Long = 0,
    val dirty: Boolean = true,
    val receiptDirty: Boolean = false,
    /** Le NAS détient un justificatif pour cette dépense (même si le fichier local manque encore). */
    val remoteHasReceipt: Boolean = false,
) {
    /** Jamais d'exception : une valeur aberrante (donnée distante corrompue) devient le 1er janvier 1970. */
    val date: LocalDate get() = runCatching { LocalDate.ofEpochDay(dateEpochDay) }.getOrDefault(LocalDate.EPOCH)
    val yearMonth: YearMonth get() = YearMonth.from(date)

    /** HT : celui du ticket s'il est connu, sinon TTC − TVA. */
    val amountHtCentsOrDerived: Long get() = amountHtCents ?: (amountCents - (vatCents ?: 0L))

    val vatLines: List<VatLine> get() = VatLine.decode(vatLinesJson)

    /** Date de purge définitive si la dépense est dans la corbeille. */
    val purgeAt: Long get() = deletedAt + RETENTION_MS

    companion object {
        const val RETENTION_DAYS = 30
        const val RETENTION_MS = RETENTION_DAYS * 24L * 60L * 60L * 1000L
        fun newUid(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
