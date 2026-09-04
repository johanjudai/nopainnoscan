package com.maitre.nopainnoscan

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.maitre.nopainnoscan.api.ApiClient
import com.maitre.nopainnoscan.api.CategoryDto
import com.maitre.nopainnoscan.databinding.ActivityRecommendationsBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Famille de produit + enseigne facultative -> les meilleurs produits pour le profil courant. */
class RecommendationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecommendationsBinding
    private lateinit var prefs: AppPrefs
    private val adapter = RecommendationAdapter { ProductActivity.open(this, it.product_id) }

    private var categories: List<CategoryDto> = emptyList()
    private var category: CategoryDto? = null
    private var store: Store? = null
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecommendationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)
        store = prefs.store

        binding.rvItems.layoutManager = LinearLayoutManager(this)
        binding.rvItems.adapter = adapter

        setupStoreChips()
        binding.fieldCategory.setOnItemClickListener { _, _, position, _ ->
            category = categories.getOrNull(position)
            prefs.lastCategory = category?.slug
            load()
        }
        loadCategories()
    }

    private fun setupStoreChips() {
        val group = binding.storeChips
        val options: List<Store?> = listOf(null) + Store.entries
        options.forEachIndexed { index, s ->
            val chip = layoutInflater.inflate(R.layout.view_chip_light, group, false) as Chip
            chip.id = View.generateViewId()
            chip.tag = index
            chip.text = s?.label ?: getString(R.string.reco_store_any)
            chip.isChecked = s == store
            group.addView(chip)
        }
        if (group.checkedChipId == View.NO_ID) (group.getChildAt(0) as Chip).isChecked = true
        group.setOnCheckedStateChangeListener { g, ids ->
            val chip = ids.firstOrNull()?.let { g.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            store = options[chip.tag as Int]
            load()
        }
    }

    private fun loadCategories() = lifecycleScope.launch {
        categories = runCatching { ApiClient.get(this@RecommendationsActivity).categories() }.getOrDefault(emptyList())
        binding.fieldCategory.setSimpleItems(categories.map { it.label }.toTypedArray())
        // Reprend la dernière famille consultée pour arriver directement sur une liste.
        category = categories.firstOrNull { it.slug == prefs.lastCategory }
        category?.let {
            binding.fieldCategory.setText(it.label, false)
            load()
        }
    }

    private fun load() {
        val cat = category ?: return
        loadJob?.cancel()
        binding.tvScope.visibility = View.VISIBLE
        binding.tvScope.text = getString(R.string.reco_loading)
        loadJob = lifecycleScope.launch {
            val attempt = runCatching {
                ApiClient.get(this@RecommendationsActivity).recommendations(cat.slug, store?.slug)
            }
            val result = attempt.getOrNull()
            if (result == null) {
                binding.tvScope.visibility = View.VISIBLE
                binding.tvScope.text = ApiErrors.describe(this@RecommendationsActivity, attempt.exceptionOrNull()!!)
                adapter.submitList(emptyList())
                return@launch
            }
            val here = result.items.orEmpty()
            val elsewhere = result.items_elsewhere.orEmpty()
            val current = store
            val rows = mutableListOf<RecommendationAdapter.Row>()

            // En tête : l'enseigne choisie (ou tout, sans enseigne).
            val headTitle = current?.let { getString(R.string.reco_in_store, it.label) } ?: getString(R.string.reco_any)
            val headSub = when {
                here.isNotEmpty() || current == null -> null
                elsewhere.isNotEmpty() -> getString(R.string.reco_none_store, current.label)
                else -> getString(R.string.reco_none_store_alone, current.label)
            }
            rows += RecommendationAdapter.Row.Header(headTitle, headSub, withDivider = false)
            here.forEachIndexed { i, item -> rows += RecommendationAdapter.Row.Item(i + 1, item) }

            // Sous un séparateur : ce qui n'a été vu que dans d'autres enseignes.
            if (elsewhere.isNotEmpty()) {
                rows += RecommendationAdapter.Row.Header(getString(R.string.alt_elsewhere), null, withDivider = true)
                elsewhere.forEachIndexed { i, item -> rows += RecommendationAdapter.Row.Item(i + 1, item) }
            }
            adapter.submitList(rows)
            binding.tvScope.text = if (here.isEmpty() && elsewhere.isEmpty()) getString(R.string.reco_empty) else ""
            binding.tvScope.visibility = if (binding.tvScope.text.isEmpty()) View.GONE else View.VISIBLE
        }
    }
}
