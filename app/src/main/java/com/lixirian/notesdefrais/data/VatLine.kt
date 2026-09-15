package com.lixirian.notesdefrais.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Une ligne de TVA d'un ticket : taux en pour cent, base HT et montant de TVA en centimes. */
@Serializable
data class VatLine(
    val ratePercent: Double,
    val baseCents: Long? = null,
    val vatCents: Long,
) {
    val rateLabel: String
        get() = if (ratePercent % 1.0 == 0.0) "${ratePercent.toInt()} %" else "${ratePercent.toString().replace('.', ',')} %"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        val USUAL_RATES = listOf(20.0, 10.0, 5.5, 2.1)

        fun encode(lines: List<VatLine>): String = if (lines.isEmpty()) "[]" else json.encodeToString(ListSerializer, lines)
        fun decode(raw: String?): List<VatLine> = if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(ListSerializer, raw) }.getOrDefault(emptyList())

        private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(serializer())
    }
}

/** Modes de paiement courants ; stockés par nom, libellé affiché. */
enum class PaymentMethod(val label: String) {
    CB("Carte bancaire"),
    ESPECES("Espèces"),
    CHEQUE("Chèque"),
    VIREMENT("Virement"),
    PRELEVEMENT("Prélèvement"),
    AUTRE("Autre");

    companion object {
        fun fromNameOrNull(name: String?): PaymentMethod? = name?.trim()?.uppercase()?.let { n -> entries.firstOrNull { it.name == n } }
    }
}
