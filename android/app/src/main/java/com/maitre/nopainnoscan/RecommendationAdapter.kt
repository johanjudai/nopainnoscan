package com.maitre.nopainnoscan

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.maitre.nopainnoscan.api.RecommendationDto
import com.maitre.nopainnoscan.databinding.ItemRecommendationBinding
import com.maitre.nopainnoscan.databinding.ItemSectionHeaderBinding
import kotlin.math.roundToInt

/** Liste en deux sections : produits de l'enseigne en tête, puis « ailleurs » sous un séparateur. */
class RecommendationAdapter(private val onClick: (RecommendationDto) -> Unit) :
    ListAdapter<RecommendationAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    sealed interface Row {
        data class Header(val title: String, val subtitle: String?, val withDivider: Boolean) : Row
        data class Item(val rank: Int, val item: RecommendationDto) : Row
    }

    class HeaderHolder(val binding: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class ItemHolder(val binding: ItemRecommendationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = if (getItem(position) is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) HeaderHolder(ItemSectionHeaderBinding.inflate(inflater, parent, false))
        else ItemHolder(ItemRecommendationBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderHolder).binding.apply {
                divider.visibility = if (row.withDivider) View.VISIBLE else View.GONE
                tvTitle.text = row.title
                tvSub.visibility = if (row.subtitle == null) View.GONE else View.VISIBLE
                tvSub.text = row.subtitle
            }
            is Row.Item -> (holder as ItemHolder).binding.apply {
                val ctx = root.context
                tvRank.text = row.rank.toString()
                ivThumb.load(row.item.image_url) { crossfade(true) }
                tvName.text = row.item.name
                tvMeta.text = ctx.getString(
                    R.string.reco_meta, row.item.kcal_100g.roundToInt().toString(), Fmt.dec1(row.item.protein_100g)
                )
                tvScore.showScorePill(row.item.score, Category.of(row.item.category))
                root.setOnClickListener { onClick(row.item) }
            }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = when {
                a is Row.Item && b is Row.Item -> a.item.product_id == b.item.product_id
                a is Row.Header && b is Row.Header -> a.title == b.title
                else -> false
            }
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }
    }
}
