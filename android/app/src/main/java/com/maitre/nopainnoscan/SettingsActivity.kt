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
                            title = getString(R.string.settings_test_ok, it.name),
                            sub = getString(R.string.settings_test_ok_sub, SystemClock.elapsedRealtime() - started),
                        )
                    }
                    .onFailure { showStatus(ok = false, title = getString(R.string.settings_test_ko), sub = it.message.orEmpty()) }
            }
        }
    }

    /** Retrofit exige une base URL http(s) terminée par `/`. */
    private fun save(): Boolean {
        val url = binding.fieldApiUrl.text.toString().trim()
        val valid = (url.startsWith("http://") || url.startsWith("https://")) && url.endsWith("/")
        binding.layoutApiUrl.error = if (valid) null else getString(R.string.settings_invalid_url)
        if (!valid) return false
        prefs.apiBaseUrl = url
        prefs.apiKey = binding.fieldApiKey.text.toString().trim()
        return true
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
