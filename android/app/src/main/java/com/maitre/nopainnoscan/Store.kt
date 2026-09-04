package com.maitre.nopainnoscan

/** Miroir de `schemas.Store` côté backend : en dur pour éviter un aller-retour réseau au démarrage. */
enum class Store(val slug: String, val label: String) {
    LECLERC("leclerc", "E.Leclerc"),
    LIDL("lidl", "Lidl"),
    GRAND_FRAIS("grand_frais", "Grand Frais"),
    AUCHAN("auchan", "Auchan"),
    CARREFOUR("carrefour", "Carrefour"),
    THIRIET("thiriet", "Thiriet");

    companion object {
        fun fromSlug(slug: String?): Store? = entries.firstOrNull { it.slug == slug }
    }
}
