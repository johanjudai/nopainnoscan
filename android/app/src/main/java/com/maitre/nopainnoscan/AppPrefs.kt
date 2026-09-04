package com.maitre.nopainnoscan

import android.content.Context
import android.content.SharedPreferences

/**
 * Réglages locaux (stockage privé de l'app, non sauvegardé dans le cloud : allowBackup=false).
 * La clé API n'a de valeur que sur ton LAN ; elle reste révocable côté serveur (rotate-key).
 */
class AppPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("nopainnoscan", Context.MODE_PRIVATE)

    var apiBaseUrl: String
        get() = prefs.getString(KEY_API_URL, null) ?: BuildConfig.API_BASE_URL
        set(value) = prefs.edit().putString(KEY_API_URL, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    /** Enseigne courante (slug backend), ou null hors magasin. */
    var store: Store?
        get() = Store.fromSlug(prefs.getString(KEY_STORE, null))
        set(value) = prefs.edit().putString(KEY_STORE, value?.slug).apply()

    /** Dernière famille consultée dans les recommandations (slug backend). */
    var lastCategory: String?
        get() = prefs.getString(KEY_CATEGORY, null)
        set(value) = prefs.edit().putString(KEY_CATEGORY, value).apply()

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /** Horodatage (ms) de la dernière vérification de mise à jour. */
    var lastUpdateCheck: Long
        get() = prefs.getLong(KEY_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_UPDATE_CHECK, value).apply()

    /** Version que l'utilisateur a choisi d'ignorer (« Ignorer cette version »). */
    var skippedUpdate: String?
        get() = prefs.getString(KEY_UPDATE_SKIPPED, null)
        set(value) = prefs.edit().putString(KEY_UPDATE_SKIPPED, value).apply()

    private companion object {
        const val KEY_API_URL = "api_base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_STORE = "store"
        const val KEY_CATEGORY = "last_category"
        const val KEY_UPDATE_CHECK = "update_last_check"
        const val KEY_UPDATE_SKIPPED = "update_skipped"
    }
}
