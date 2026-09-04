package com.maitre.nopainnoscan

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.maitre.nopainnoscan.api.ApiClient
import com.maitre.nopainnoscan.databinding.ActivityProductBinding
import com.maitre.nopainnoscan.ui.ResultRenderer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Fiche d'un produit déjà scanné : note actuelle et alternatives, sans créer de scan. */
class ProductActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProductBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProductBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val productId = intent.getIntExtra(EXTRA_PRODUCT_ID, -1)
        val store = AppPrefs(this).store
        binding.tvContext.text = store?.let { getString(R.string.product_context_store, it.label) }
            ?: getString(R.string.product_context_any)

        val renderer = ResultRenderer(this, binding.result, layoutInflater) { open(this, it) }
        binding.result.root.visibility = View.INVISIBLE

        lifecycleScope.launch {
            val api = ApiClient.get(this@ProductActivity)
            val (score, goal) = coroutineScope {
                val score = async { runCatching { api.product(productId, store?.slug) }.getOrNull() }
                val goal = async { runCatching { api.getProfile().goal }.getOrNull() }
                score.await() to goal.await()
            }
            if (score == null) {
                binding.tvError.visibility = View.VISIBLE
                binding.result.root.visibility = View.GONE
                return@launch
            }
            binding.result.root.visibility = View.VISIBLE
            renderer.render(score, goal)
        }
    }

    companion object {
        private const val EXTRA_PRODUCT_ID = "product_id"

        fun open(context: Context, productId: Int) {
            context.startActivity(Intent(context, ProductActivity::class.java).putExtra(EXTRA_PRODUCT_ID, productId))
        }
    }
}
