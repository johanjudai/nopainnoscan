package com.maitre.nopainnoscan.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.maitre.nopainnoscan.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Une release plus récente que l'application installée. */
data class UpdateInfo(val version: String, val apkUrl: String, val sizeBytes: Long, val sha256: String?, val notes: String)

/**
 * Mise à jour depuis les GitHub Releases du dépôt public : pas de serveur à maintenir, et
 * l'APK y est signé par la CI. Android refuse d'installer un APK signé avec une autre clé
 * par-dessus l'app : c'est la garantie d'authenticité ; l'empreinte publiée (`digest`)
 * est vérifiée en plus quand GitHub la fournit.
 */
class AppUpdater(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** null si à jour (ou si la release n'a pas d'APK) ; lève en cas d'erreur réseau. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "NoPainNoScan/${BuildConfig.VERSION_NAME}")
            .build()
        val release = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GitHub ${resp.code}")
            Gson().fromJson(resp.body!!.charStream(), Release::class.java)
        }
        val version = release.tag_name?.removePrefix("v") ?: return@withContext null
        if (!isNewer(version, BuildConfig.VERSION_NAME)) return@withContext null
        val apk = release.assets.orEmpty().firstOrNull { it.name?.endsWith(".apk") == true } ?: return@withContext null
        val url = apk.browser_download_url ?: return@withContext null
        if (!url.startsWith("https://github.com/")) return@withContext null // jamais d'APK hors du dépôt
        UpdateInfo(
            version = version,
            apkUrl = url,
            sizeBytes = apk.size ?: 0L,
            sha256 = apk.digest?.removePrefix("sha256:")?.lowercase()?.takeIf { it.length == 64 },
            notes = release.body.orEmpty().trim(),
        )
    }

    /** Télécharge dans le cache privé et vérifie l'empreinte ; lève [SecurityException] si elle diffère. */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // une seule mise à jour en attente à la fois
        val file = File(dir, "nopainnoscan-${info.version}.apk")
        val request = Request.Builder().url(info.apkUrl).header("User-Agent", "NoPainNoScan/${BuildConfig.VERSION_NAME}").build()
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("réponse vide")
            val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            if (total > MAX_APK_BYTES) throw IOException("APK trop volumineux")
            var read = 0L
            var lastPct = -1
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        if (read > MAX_APK_BYTES) throw IOException("APK trop volumineux")
                        val pct = if (total > 0) (read * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (info.sha256 != null && actual != info.sha256) {
            file.delete()
            throw SecurityException("empreinte SHA-256 différente")
        }
        file
    }

    /** L'installation est déléguée au système, qui vérifie la signature de l'APK. */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Android 8+ : l'utilisateur doit autoriser l'app à installer des paquets, une seule fois. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${BuildConfig.APPLICATION_ID}"))

    private data class Release(val tag_name: String?, val body: String?, val assets: List<Asset>?)
    private data class Asset(val name: String?, val browser_download_url: String?, val size: Long?, val digest: String?)

    companion object {
        private const val MAX_APK_BYTES = 150L * 1024 * 1024

        /** Comparaison semver sur le cœur `a.b.c` ; un suffixe (`-dev`) compte comme plus ancien. */
        fun isNewer(candidate: String, installed: String): Boolean {
            val a = core(candidate)
            val b = core(installed)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return installed.contains('-') && !candidate.contains('-')
        }

        private fun core(version: String): List<Int> =
            version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    }
}
