package com.lixirian.notesdefrais.data

/**
 * Catégories de dépenses courantes pour un indépendant français.
 * Le nom de l'enum est stocké en base et échangé avec le LLM ; le libellé est affiché.
 */
enum class Category(val label: String) {
    REPAS("Repas"),
    TRANSPORT("Transport"),
    HEBERGEMENT("Hébergement"),
    CARBURANT("Carburant"),
    PARKING("Parking & péages"),
    FOURNITURES("Fournitures"),
    LOGICIELS("Logiciels & abonnements"),
    TELEPHONIE("Téléphonie & internet"),
    FORMATION("Formation"),
    AUTRE("Autre");

    companion object {
        fun fromNameOrNull(name: String?): Category? =
            name?.trim()?.uppercase()?.let { n -> entries.firstOrNull { it.name == n } }
    }
}
