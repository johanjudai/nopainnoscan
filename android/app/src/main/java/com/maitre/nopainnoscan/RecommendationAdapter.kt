package com.maitre.nopainnoscan

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.maitre.nopainnoscan.api.RecommendationDto
import com.maitre.nopainnoscan.databinding.ItemRecommendationBinding
import kotlin.math.roundToInt

class RecommendationAdapter(private val onClick: (RecommendationDto) -> Unit) :
    ListAdapter<RecommendationDto, RecommendationAdapter.Holder>(DIFF) {

    class Holder(val binding: ItemRecommendationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemRecommendationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val ctx = holder.binding.root.context
        holder.binding.tvRank.text = (position + 1).toString()
        holder.binding.ivThumb.load(item.image_url) { crossfade(true) }
        holder.binding.tvName.text = item.name
        holder.binding.tvMeta.text = ctx.getString(
            R.string.reco_meta, item.kcal_100g.roundToInt().toString(), Fmt.dec1(item.protein_100g)
        )
        holder.binding.tvScore.showScorePill(item.score, Category.of(item.category))
        holder.binding.root.setOnClickListener { onClick(item) }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<RecommendationDto>() {
            override fun areItemsTheSame(a: RecommendationDto, b: RecommendationDto) = a.product_id == b.product_id
            override fun areContentsTheSame(a: RecommendationDto, b: RecommendationDto) = a == b
        }
    }
}
