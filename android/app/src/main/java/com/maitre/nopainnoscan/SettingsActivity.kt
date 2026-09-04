package com.maitre.nopainnoscan

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
                toast(getString(R.string.settings_saved))
                finish()
            }
        }
        binding.btnTest.setOnClickListener {
            if (!save()) return@setOnClickListener
            lifecycleScope.launch {
                runCatching { ApiClient.get(this@SettingsActivity).me() }
                    .onSuccess { toast(getString(R.string.settings_test_ok, it.name)) }
                    .onFailure { toast(getString(R.string.settings_test_ko, it.message)) }
            }
        }
    }

    /** Retrofit exige une base URL http(s) terminée par `/`. */
    private fun save(): Boolean {
        val url = binding.fieldApiUrl.text.toString().trim()
        val valid = (url.startsWith("http://") || url.startsWith("https://")) && url.endsWith("/")
        if (!valid) {
            toast(getString(R.string.settings_invalid_url))
            return false
        }
        prefs.apiBaseUrl = url
        prefs.apiKey = binding.fieldApiKey.text.toString().trim()
        return true
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
