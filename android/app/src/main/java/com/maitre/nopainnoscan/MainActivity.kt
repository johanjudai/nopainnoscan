package com.maitre.nopainnoscan

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.maitre.nopainnoscan.api.ApiClient
import com.maitre.nopainnoscan.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnScan.setOnClickListener { open(ScannerActivity::class.java) }
        binding.btnProfile.setOnClickListener { open(ProfileActivity::class.java) }
        binding.btnSettings.setOnClickListener { open(SettingsActivity::class.java) }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() = lifecycleScope.launch {
        val prefs = AppPrefs(this@MainActivity)
        val user = if (prefs.isConfigured) {
            runCatching { ApiClient.get(this@MainActivity).me() }.getOrNull()
        } else {
            null
        }
        binding.statusText.text = user?.let { getString(R.string.main_connected_as, it.name) }
            ?: getString(R.string.main_not_configured)
    }

    private fun open(activity: Class<out AppCompatActivity>) = startActivity(Intent(this, activity))
}
