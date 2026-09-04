package com.maitre.nopainnoscan.update

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.maitre.nopainnoscan.AppPrefs
import com.maitre.nopainnoscan.BuildConfig
import com.maitre.nopainnoscan.R
import com.maitre.nopainnoscan.databinding.DialogUpdateProgressBinding
import kotlinx.coroutines.launch
import java.io.File

/**
 * Dialogue « nouvelle version » → téléchargement avec progression → installateur système.
 * Silencieux en vérification automatique ; parlant quand l'utilisateur la demande.
 */
class UpdatePrompt(private val activity: AppCompatActivity) {

    private val updater = AppUpdater(activity)
    private val prefs = AppPrefs(activity)
    private var pendingApk: File? = null

    /** Au plus une vérification par [AUTO_INTERVAL_MS] ; jamais en build debug (version 0.1.0). */
    fun checkAutomatically() {
        if (BuildConfig.DEBUG) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < AUTO_INTERVAL_MS) return
        prefs.lastUpdateCheck = now
        activity.lifecycleScope.launch {
            val info = runCatching { updater.check() }.getOrNull() ?: return@launch
            if (info.version == prefs.skippedUpdate) return@launch
            propose(info)
        }
    }

    fun checkNow() {
        Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            runCatching { updater.check() }
                .onSuccess { info ->
                    prefs.lastUpdateCheck = System.currentTimeMillis()
                    if (info == null) Toast.makeText(activity, R.string.update_none, Toast.LENGTH_SHORT).show()
                    else propose(info, allowSkip = false)
                }
                .onFailure { Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    /** Après retour de l'écran d'autorisation : installe l'APK déjà téléchargé. */
    fun resumePendingInstall() {
        val apk = pendingApk ?: return
        if (updater.canInstall()) {
            pendingApk = null
            updater.install(apk)
        }
    }

    private fun propose(info: UpdateInfo, allowSkip: Boolean = true) {
        val size = String.format(java.util.Locale.FRANCE, "%.1f", info.sizeBytes / 1_048_576.0)
        val message = buildString {
            append(activity.getString(R.string.update_body, BuildConfig.VERSION_NAME, size))
            if (info.notes.isNotBlank()) append("\n\n").append(info.notes.take(600))
        }
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_title, info.version))
            .setMessage(message)
            .setPositiveButton(R.string.update_install) { _, _ -> download(info) }
            .setNegativeButton(R.string.update_later, null)
        if (allowSkip) builder.setNeutralButton(R.string.update_skip) { _, _ -> prefs.skippedUpdate = info.version }
        builder.show()
    }

    private fun download(info: UpdateInfo) {
        val binding = DialogUpdateProgressBinding.inflate(activity.layoutInflater)
        binding.tvProgress.text = activity.getString(R.string.update_downloading, info.version)
        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity).setView(binding.root).setCancelable(false).show()
        activity.lifecycleScope.launch {
            runCatching {
                updater.download(info) { pct -> binding.progress.setProgressCompat(pct, true) }
            }.onSuccess { apk ->
                dialog.dismiss()
                if (updater.canInstall()) {
                    updater.install(apk)
                } else {
                    pendingApk = apk
                    Toast.makeText(activity, R.string.update_need_permission, Toast.LENGTH_LONG).show()
                    activity.startActivity(updater.permissionIntent())
                }
            }.onFailure { e ->
                dialog.dismiss()
                val text = if (e is SecurityException) activity.getString(R.string.update_corrupt)
                else activity.getString(R.string.update_download_failed, e.message)
                Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val AUTO_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
