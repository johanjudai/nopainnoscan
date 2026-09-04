package com.maitre.nopainnoscan

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.maitre.nopainnoscan.api.ApiClient
import com.maitre.nopainnoscan.api.ProfileDto
import com.maitre.nopainnoscan.databinding.ActivityProfileBinding
import kotlinx.coroutines.launch

/**
 * Profil propre à chaque utilisateur (clé API) : il pilote les pondérations du scoring
 * côté backend (scoring.py -> GOAL_WEIGHTS).
 */
class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.fieldGoal.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, GOALS)
        )

        loadProfile()
        binding.btnSave.setOnClickListener { saveProfile() }
    }

    private fun loadProfile() = lifecycleScope.launch {
        val profile = runCatching { ApiClient.get(this@ProfileActivity).getProfile() }.getOrNull()
            ?: return@launch // 404 tant qu'aucun profil n'existe : champs vides
        with(binding) {
            fieldWeight.setText(profile.weight_kg.toString())
            fieldHeight.setText(profile.height_cm.toString())
            fieldCurrentBf.setText(profile.current_body_fat_pct?.toString().orEmpty())
            fieldTargetBf.setText(profile.target_body_fat_pct?.toString().orEmpty())
            fieldGoal.setText(profile.goal, false)
            fieldKcal.setText(profile.daily_kcal_target?.toString().orEmpty())
            fieldProtein.setText(profile.daily_protein_target_g?.toString().orEmpty())
        }
    }

    private fun saveProfile() {
        val weight = binding.fieldWeight.num()
        val height = binding.fieldHeight.num()
        if (weight == null || height == null) {
            toast(getString(R.string.profile_invalid))
            return
        }
        val dto = with(binding) {
            ProfileDto(
                weight_kg = weight,
                height_cm = height,
                current_body_fat_pct = fieldCurrentBf.num(),
                target_body_fat_pct = fieldTargetBf.num(),
                goal = fieldGoal.text.toString().takeIf { it in GOALS } ?: "maintenance",
                daily_kcal_target = fieldKcal.num(),
                daily_protein_target_g = fieldProtein.num(),
            )
        }

        lifecycleScope.launch {
            runCatching { ApiClient.get(this@ProfileActivity).setProfile(dto) }
                .onSuccess {
                    toast(getString(R.string.profile_saved))
                    finish()
                }
                .onFailure { toast(getString(R.string.error_generic, it.message)) }
        }
    }

    private fun EditText.num() = text.toString().replace(',', '.').toDoubleOrNull()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        val GOALS = listOf("cut", "maintenance", "bulk")
    }
}
