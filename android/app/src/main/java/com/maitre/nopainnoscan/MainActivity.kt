package com.maitre.nopainnoscan

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.maitre.nopainnoscan.api.ApiClient
import com.maitre.nopainnoscan.api.ProfileOutDto
import com.maitre.nopainnoscan.api.ScanDto
import com.maitre.nopainnoscan.databinding.ActivityMainBinding
import com.maitre.nopainnoscan.update.UpdatePrompt
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = ScanAdapter { ProductActivity.open(this, it.product_id) }
    private val updates by lazy { UpdatePrompt(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvScans.layoutManager = LinearLayoutManager(this)
        binding.rvScans.adapter = adapter

        binding.btnScan.setOnClickListener { open(ScannerActivity::class.java) }
        binding.cardScan.setOnClickListener { open(ScannerActivity::class.java) }
        binding.btnManual.setOnClickListener { ScannerActivity.openManual(this) }
        binding.tileProfile.setOnClickListener { open(ProfileActivity::class.java) }
        binding.tileTargets.setOnClickListener { open(ProfileActivity::class.java) }
        binding.btnSettings.setOnClickListener { open(SettingsActivity::class.java) }
        binding.cardReco.setOnClickListener { open(RecommendationsActivity::class.java) }
        binding.btnMoreHistory.setOnClickListener { loadMoreHistory() }
    }

    // Historique : 5 au départ, puis par paliers jusqu'au maximum servi par l'API.
    private var historyLimit = HISTORY_STEPS.first()
    private var historyJob: Job? = null

    /** Palier suivant, historique seul ; en échec la liste reste et le palier n'avance pas. */
    private fun loadMoreHistory() {
        val next = HISTORY_STEPS.firstOrNull { it > historyLimit } ?: return
        binding.btnMoreHistory.isEnabled = false
        historyJob?.cancel()
        historyJob = lifecycleScope.launch {
            val api = runCatching { ApiClient.get(this@MainActivity) }.getOrNull()
            val scans = api?.let { runCatching { it.history(next) }.getOrNull() }
            binding.btnMoreHistory.isEnabled = true
            if (scans == null) {
                Toast.makeText(this@MainActivity, R.string.error_offline, Toast.LENGTH_SHORT).show()
                return@launch
            }
            historyLimit = next
            renderHistory(scans)
        }
    }

    private fun renderHistory(scans: List<ScanDto>?) {
        adapter.submitList(scans.orEmpty())
        binding.tvEmpty.visibility = if (scans.isNullOrEmpty()) View.VISIBLE else View.GONE
        // Une page pleine laisse supposer une suite ; une page courte ou le plafond ferme le bouton.
        val more = (scans?.size ?: 0) >= historyLimit && historyLimit < HISTORY_STEPS.last()
        binding.btnMoreHistory.visibility = if (more) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        refresh()
        updates.resumePendingInstall()
        updates.checkAutomatically()
    }

    private fun refresh() = lifecycleScope.launch {
        val prefs = AppPrefs(this@MainActivity)
        binding.chipStore.text = prefs.store?.label ?: getString(R.string.store_none)

        if (!prefs.isConfigured) {
            showStatus(connected = false)
            renderDate(goal = null)
            return@launch
        }

        // Les trois appels partent en parallèle : l'écran s'affiche en un aller-retour.
        val api = runCatching { ApiClient.get(this@MainActivity) }.getOrNull() ?: run {
            showStatus(connected = false)  // URL enregistrée inutilisable : on ne plante pas
            renderDate(goal = null)
            return@launch
        }
        val (me, profile, scans) = coroutineScope {
            val me = async { runCatching { api.me() }.getOrNull() }
            val profile = async { runCatching { api.getProfile() }.getOrNull() }
            val scans = async { runCatching { api.history(historyLimit) }.getOrNull() }
            Triple(me.await(), profile.await(), scans.await())
        }

        showStatus(connected = me != null)
        binding.tvGreeting.text = me?.name?.let {
            getString(R.string.main_greeting, it.replaceFirstChar { c -> c.titlecase(Locale.FRENCH) })
        } ?: getString(R.string.main_greeting_anonymous)
        renderDate(profile?.goal)
        renderTiles(profile)

        renderHistory(scans)
    }

    private fun renderDate(goal: String?) {
        val day = LocalDate.now().dayOfWeek.getDisplayName(TextStyle.FULL, Locale.FRENCH)
            .replaceFirstChar { it.titlecase(Locale.FRENCH) }
        binding.tvDate.text = if (goal != null) getString(R.string.main_date_goal, day, getString(goalLabel(goal))) else day
    }

    private fun renderTiles(profile: ProfileOutDto?) {
        val est = profile?.estimate
        val fat = est?.body_fat_pct
        if (fat != null) {
            binding.tvBodyFat.text = getString(R.string.main_tile_bf, Fmt.dec1(fat))
            binding.tvBodyFatSub.text = profile.target_body_fat_pct
                ?.let { getString(R.string.main_tile_bf_sub, Fmt.dec1(it)) }
                ?: getString(R.string.profile_section_you)
        } else {
            binding.tvBodyFat.text = getString(R.string.main_tile_bf_missing)
            binding.tvBodyFatSub.text = getString(R.string.main_tile_bf_missing_sub)
        }
        if (est != null) {
            binding.tvKcal.text = getString(R.string.main_tile_kcal, Fmt.int(est.kcal_target))
            binding.tvKcalSub.text = getString(R.string.main_tile_kcal_sub, Fmt.int(est.protein_target_g))
        } else {
            binding.tvKcal.text = getString(R.string.main_tile_kcal_missing)
            binding.tvKcalSub.text = getString(R.string.main_tile_kcal_missing_sub)
        }
    }

    private fun showStatus(connected: Boolean) {
        val chip = binding.chipStatus
        chip.text = getString(if (connected) R.string.main_connected else R.string.main_disconnected)
        chip.background.mutate().setTint(
            ContextCompat.getColor(this, if (connected) R.color.cat_parfait_container else R.color.tonal)
        )
        chip.setTextColor(ContextCompat.getColor(this, if (connected) R.color.cat_parfait_on else R.color.muted))
    }

    private fun open(activity: Class<out AppCompatActivity>) = startActivity(Intent(this, activity))

    private companion object {
        val HISTORY_STEPS = listOf(5, 25, 100, 200)
    }
}
