package com.lixirian.notesdefrais.ui.edit

import android.net.Uri

/**
 * Paramètres du volet d'édition, sérialisés en une chaîne (le navigateur list-detail ne sait
 * sauvegarder que des types simples). [nonce] garantit un ViewModel neuf à chaque ouverture.
 */
data class EditArgs(
    val expenseId: Long? = null,
    val image: Uri? = null,
    val nonce: Long = System.currentTimeMillis(),
) {
    val isExisting: Boolean get() = expenseId != null

    fun encode(): String = buildList {
        expenseId?.let { add("id=$it") }
        image?.let { add("image=${Uri.encode(it.toString())}") }
        add("n=$nonce")
    }.joinToString("&")

    companion object {
        fun decode(raw: String): EditArgs {
            val map = raw.split("&").filter { it.contains("=") }
                .associate { it.substringBefore("=") to it.substringAfter("=") }
            return EditArgs(
                expenseId = map["id"]?.toLongOrNull(),
                image = map["image"]?.let { Uri.parse(Uri.decode(it)) },
                nonce = map["n"]?.toLongOrNull() ?: 0L,
            )
        }
    }
}
