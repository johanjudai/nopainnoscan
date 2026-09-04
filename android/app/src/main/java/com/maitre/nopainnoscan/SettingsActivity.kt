package com.maitre.nopainnoscan

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.maitre.nopainnoscan.api.ApiClient
import com.maitre.nopainnoscan.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        binding.fieldApiUrl.setText(prefs.apiBaseUrl)
        binding.fieldApiKey.setText(prefs.apiKey)

        binding.btnSave.setOnClickListener {
            if (save()) {
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        binding.btnTest.setOnClickListener {
            if (!save()) return@setOnClickListener
            lifecycleScope.launch {
                val started = SystemClock.elapsedRealtime()
                runCatching { ApiClient.get(this@SettingsActivity).me() }
                    .onSuccess {
                        showStatus(
                            ok = true,
                            title = getString(R.string.settings_test_ok, it.name.orEmpty()),
                            sub = getString(R.string.settings_test_ok_sub, SystemClock.elapsedRealtime() - started),
                        )
                    }
                    .onFailure { showStatus(ok = false, title = getString(R.string.settings_test_ko), sub = ApiErrors.describe(this@SettingsActivity, it)) }
            }
        }
    }

    /** Retrofit exige une base URL http(s) terminée par `/` ; http:// seulement vers le LAN. */
    private fun save(): Boolean {
        val url = binding.fieldApiUrl.text.toString().trim()
        val parsed = url.toHttpUrlOrNull()?.takeIf { url.endsWith("/") }
        val error = when {
            parsed == null -> getString(R.string.settings_invalid_url)
            parsed.scheme == "http" && !isPrivateHost(parsed.host) -> getString(R.string.settings_http_not_private)
            else -> null
        }
        binding.layoutApiUrl.error = error
        if (error != null) return false
        prefs.apiBaseUrl = url
        prefs.apiKey = binding.fieldApiKey.text.toString().trim()
        return true
    }

    /** 10/8, 172.16/12, 192.168/16, loopback et .local : le seul périmètre où la clé peut passer en clair. */
    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local")) return true
        val parts = host.split(".").map { it.toIntOrNull() ?: return false }
        if (parts.size != 4) return false
        return parts[0] == 10 || parts[0] == 127 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31)
    }

    private fun showStatus(ok: Boolean, title: String, sub: String) {
        val card = binding.cardStatus
        card.visibility = View.VISIBLE
        card.setCardBackgroundColor(ContextCompat.getColor(this, if (ok) R.color.cat_parfait_container else R.color.cat_a_ne_pas_manger_container))
        val fg = ContextCompat.getColor(this, if (ok) R.color.cat_parfait_on else R.color.cat_a_ne_pas_manger_on)
        binding.tvStatus.text = title
        binding.tvStatus.setTextColor(fg)
        binding.tvStatusSub.text = sub
        binding.tvStatusSub.setTextColor(fg)
        binding.ivStatus.setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_warning)
        binding.ivStatus.imageTintList = ContextCompat.getColorStateList(this, if (ok) R.color.cat_parfait_on else R.color.cat_a_ne_pas_manger_on)
    }
}
