package com.maitre.nopainnoscan.ui

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.Drawable
import coil.load
import com.maitre.nopainnoscan.R
import com.maitre.nopainnoscan.databinding.DialogPhotoBinding

/** Photo du produit en plein écran, zoomable ; la vignette sert d'aperçu pendant le chargement. */
object PhotoDialog {

    // Open Food Facts publie chaque image en 100, 200, 400 px et « full » : on part de la vignette.
    private val SIZE_SUFFIX = Regex("""\.(100|200|400)\.jpg$""")

    fun show(context: Context, url: String, thumbnail: Drawable?) {
        val binding = DialogPhotoBinding.inflate(android.view.LayoutInflater.from(context))
        val dialog = Dialog(context, R.style.Theme_NoPainNoScan_Photo).apply { setContentView(binding.root) }
        binding.btnClose.setOnClickListener { dialog.dismiss() }
        binding.image.onTap = { dialog.dismiss() }

        // Pleine résolution d'abord ; si elle manque, la 400 px ; sinon la vignette d'origine.
        val candidates = listOfNotNull(
            SIZE_SUFFIX.replace(url, ".full.jpg").takeIf { it != url },
            SIZE_SUFFIX.replace(url, ".400.jpg").takeIf { it != url },
            url,
        ).distinct()
        fun loadFrom(index: Int) {
            binding.image.load(candidates[index]) {
                placeholder(thumbnail)
                crossfade(true)
                listener(onError = { _, _ -> if (index + 1 < candidates.size) loadFrom(index + 1) })
            }
        }
        loadFrom(0)
        dialog.show()
    }
}
