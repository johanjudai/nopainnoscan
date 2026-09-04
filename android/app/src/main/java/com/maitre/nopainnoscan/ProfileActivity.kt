package com.maitre.nopainnoscan

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.maitre.nopainnoscan.api.ApiClient
import com.maitre.nopainnoscan.api.EstimateDto
import com.maitre.nopainnoscan.api.MessageDto
import com.maitre.nopainnoscan.api.ProfileDto
import com.maitre.nopainnoscan.databinding.ActivityProfileBinding
import com.maitre.nopainnoscan.databinding.ViewMessageBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Le profil est saisi ici, mais tout ce qui en dérive (masse grasse, dépense, cibles,
 * alertes) est calculé par le serveur : une seule source de vérité, `POST /profile/estimate`
 * en aperçu pendant la frappe (débounce), puis `PUT /profile` à l'enregistrement.
 */
class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var activityValues: Array<String>
    private lateinit var activityLabels: Array<String>

    private var estimateJob: Job? = null
    private var programmatic = false
    private var kcalOverride = false
    private var proteinOverride = false
    private var lastEstimate: EstimateDto? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        activityValues = resources.getStringArray(R.array.activity_values)
        activityLabels = resources.getStringArray(R.array.activity_labels)

        setQuiet(binding.fieldActivity, activityLabels[activityValues.indexOf("moderate")])

        listOf(
            binding.fieldAge, binding.fieldHeight, binding.fieldWeight,
            binding.fieldNeck, binding.fieldWaist, binding.fieldHips, binding.fieldTargetBf,
        ).forEach { it.doAfterTextChanged { if (!programmatic) scheduleEstimate() } }

        binding.fieldKcal.doAfterTextChanged {
            if (programmatic) return@doAfterTextChanged
            kcalOverride = Fmt.parse(it)?.toInt() != lastEstimate?.kcal_target_auto
            scheduleEstimate()
        }
        binding.fieldProtein.doAfterTextChanged {
            if (programmatic) return@doAfterTextChanged
            proteinOverride = Fmt.parse(it)?.toInt() != lastEstimate?.protein_target_auto
            scheduleEstimate()
        }
        binding.btnResetKcal.setOnClickListener {
            kcalOverride = false
            lastEstimate?.let { setQuiet(binding.fieldKcal, it.kcal_target_auto.toString()) }
            scheduleEstimate()
        }
        binding.btnResetProtein.setOnClickListener {
            proteinOverride = false
            lastEstimate?.let { setQuiet(binding.fieldProtein, it.protein_target_auto.toString()) }
            scheduleEstimate()
        }

        binding.groupSex.addOnButtonCheckedListener { _, _, checked ->
            if (checked) {
                updateHipsVisibility()
                if (!programmatic) scheduleEstimate()
            }
        }
        binding.groupGoal.addOnButtonCheckedListener { _, _, checked -> if (checked && !programmatic) scheduleEstimate() }
        binding.fieldActivity.setOnItemClickListener { _, _, _, _ -> scheduleEstimate() }

        updateHipsVisibility()
        binding.btnSave.setOnClickListener { save() }
        if (savedInstanceState == null) {
            load()
        } else {
            // Rotation : les champs sont restaurés par le système, on garde la saisie en cours.
            kcalOverride = savedInstanceState.getBoolean(KEY_KCAL_OVERRIDE)
            proteinOverride = savedInstanceState.getBoolean(KEY_PROTEIN_OVERRIDE)
            scheduleEstimate()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_KCAL_OVERRIDE, kcalOverride)
        outState.putBoolean(KEY_PROTEIN_OVERRIDE, proteinOverride)
    }

    // ---------- lecture / écriture des champs ----------

    private val sex: String get() = if (binding.groupSex.checkedButtonId == R.id.btnFemale) "female" else "male"

    private val goal: String
        get() = when (binding.groupGoal.checkedButtonId) {
            R.id.btnCut -> "cut"
            R.id.btnBulk -> "bulk"
            else -> "maintenance"
        }

    private val activity: String
        get() = activityValues.getOrElse(activityLabels.indexOf(binding.fieldActivity.text.toString())) { "moderate" }

    private fun buildDto(): ProfileDto? {
        val age = Fmt.parse(binding.fieldAge.text)?.toInt() ?: return null
        val height = Fmt.parse(binding.fieldHeight.text) ?: return null
        val weight = Fmt.parse(binding.fieldWeight.text) ?: return null
        return ProfileDto(
            sex = sex,
            age = age,
            height_cm = height,
            weight_kg = weight,
            neck_cm = Fmt.parse(binding.fieldNeck.text),
            waist_cm = Fmt.parse(binding.fieldWaist.text),
            hips_cm = if (sex == "female") Fmt.parse(binding.fieldHips.text) else null,
            activity = activity,
            goal = goal,
            target_body_fat_pct = Fmt.parse(binding.fieldTargetBf.text),
            daily_kcal_target = if (kcalOverride) Fmt.parse(binding.fieldKcal.text) else null,
            daily_protein_target_g = if (proteinOverride) Fmt.parse(binding.fieldProtein.text) else null,
        )
    }

    private fun setQuiet(field: EditText, value: String) {
        programmatic = true
        field.setText(value)
        programmatic = false
    }

    private fun updateHipsVisibility() {
        binding.layoutHips.visibility = if (sex == "female") View.VISIBLE else View.INVISIBLE
    }

    // ---------- serveur ----------

    private fun load() = lifecycleScope.launch {
        val profile = runCatching { ApiClient.get(this@ProfileActivity).getProfile() }.getOrNull()
            ?: return@launch // 404 tant qu'aucun profil n'existe : champs vides
        programmatic = true
        with(binding) {
            groupSex.check(if (profile.sex == "female") R.id.btnFemale else R.id.btnMale)
            fieldAge.setText(profile.age.toString())
            fieldHeight.setText(Fmt.field(profile.height_cm))
            fieldWeight.setText(Fmt.field(profile.weight_kg))
            fieldNeck.setText(Fmt.field(profile.neck_cm))
            fieldWaist.setText(Fmt.field(profile.waist_cm))
            fieldHips.setText(Fmt.field(profile.hips_cm))
            fieldTargetBf.setText(Fmt.field(profile.target_body_fat_pct))
            groupGoal.check(
                when (profile.goal) {
                    "cut" -> R.id.btnCut
                    "bulk" -> R.id.btnBulk
                    else -> R.id.btnMaintenance
                }
            )
            fieldActivity.setText(activityLabels.getOrElse(activityValues.indexOf(profile.activity)) { activityLabels[2] }, false)
        }
        programmatic = false
        kcalOverride = profile.daily_kcal_target != null
        proteinOverride = profile.daily_protein_target_g != null
        updateHipsVisibility()
        profile.estimate?.let(::render) ?: scheduleEstimate()
    }

    private fun scheduleEstimate() {
        estimateJob?.cancel()
        estimateJob = lifecycleScope.launch {
            delay(DEBOUNCE_MS)
            val dto = buildDto() ?: return@launch
            val estimate = runCatching { ApiClient.get(this@ProfileActivity).estimate(dto) }.getOrNull()
                ?: return@launch
            render(estimate)
        }
    }

    private fun save() {
        val dto = buildDto()
        if (dto == null) {
            toast(getString(R.string.profile_invalid))
            return
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

    // ---------- rendu ----------

    private fun render(est: EstimateDto) {
        lastEstimate = est
        renderBodyFat(est)

        if (!kcalOverride) setQuiet(binding.fieldKcal, est.kcal_target_auto.toString())
        if (!proteinOverride) setQuiet(binding.fieldProtein, est.protein_target_auto.toString())

        binding.layoutKcal.helperText = getString(R.string.profile_kcal_helper, Fmt.int(est.tdee_kcal))
        val weight = Fmt.parse(binding.fieldWeight.text) ?: 1.0
        binding.layoutProtein.helperText =
            getString(R.string.profile_protein_helper, Fmt.dec1(est.protein_target_g / weight))

        binding.btnResetKcal.visibility = if (kcalOverride) View.VISIBLE else View.GONE
        binding.btnResetKcal.text = getString(R.string.profile_reset_to, Fmt.int(est.kcal_target_auto))
        binding.btnResetProtein.visibility = if (proteinOverride) View.VISIBLE else View.GONE
        binding.btnResetProtein.text = getString(R.string.profile_reset_to, Fmt.int(est.protein_target_auto))

        val messages = est.messages.orEmpty()
        renderMessage(binding.msgKcal, messages.firstOrNull { it.field == "kcal" })
        renderMessage(binding.msgProtein, messages.firstOrNull { it.field == "protein" })
    }

    private fun renderBodyFat(est: EstimateDto) {
        val fat = est.body_fat_pct
        val lean = est.lean_mass_kg
        if (fat == null || lean == null) {
            binding.tvBodyFat.text = getString(R.string.profile_bf_missing)
            binding.tvBodyFatSub.text = getString(R.string.profile_bf_sub_missing)
            binding.progressBf.setProgressCompat(0, true)
            binding.tvBfHint.text = ""
            return
        }
        binding.tvBodyFat.text = getString(R.string.profile_bf_value, Fmt.dec1(fat))
        binding.tvBodyFatSub.text = getString(R.string.profile_bf_sub, Fmt.dec1(lean))

        val target = Fmt.parse(binding.fieldTargetBf.text)
        if (target == null || target <= 0) {
            binding.progressBf.setProgressCompat(0, true)
            binding.tvBfHint.text = ""
            return
        }
        if (fat <= target) {
            binding.progressBf.setProgressCompat(100, true)
            binding.tvBfHint.text = getString(R.string.profile_bf_hint_reached)
            return
        }
        // Gras à perdre à masse maigre constante : poids cible = maigre / (1 - objectif).
        val weight = Fmt.parse(binding.fieldWeight.text) ?: return
        val toLose = weight - lean / (1 - target / 100)
        binding.progressBf.setProgressCompat((target / fat * 100).toInt(), true)
        binding.tvBfHint.text = getString(R.string.profile_bf_hint_to_lose, Fmt.dec1(toLose), Fmt.dec1(target))
    }

    private fun renderMessage(view: ViewMessageBinding, message: MessageDto?) {
        if (message == null) {
            view.root.visibility = View.GONE
            return
        }
        val warning = message.level == "warning"
        view.root.visibility = View.VISIBLE
        view.tvText.text = message.text
        view.root.background.mutate().setTint(ContextCompat.getColor(this, if (warning) R.color.warn_bg else R.color.info_bg))
        view.tvText.setTextColor(ContextCompat.getColor(this, if (warning) R.color.warn_fg else R.color.info_fg))
        view.ivIcon.setImageResource(if (warning) R.drawable.ic_warning else R.drawable.ic_info)
        view.ivIcon.imageTintList = ContextCompat.getColorStateList(this, if (warning) R.color.warn_icon else R.color.muted)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val DEBOUNCE_MS = 350L
        const val KEY_KCAL_OVERRIDE = "kcal_override"
        const val KEY_PROTEIN_OVERRIDE = "protein_override"
    }
}
