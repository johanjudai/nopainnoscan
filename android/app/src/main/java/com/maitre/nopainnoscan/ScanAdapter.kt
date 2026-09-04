package com.maitre.nopainnoscan

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.maitre.nopainnoscan.api.ScanDto
import com.maitre.nopainnoscan.databinding.ItemScanBinding
import java.time.OffsetDateTime

class ScanAdapter(private val onClick: (ScanDto) -> Unit) : ListAdapter<ScanDto, ScanAdapter.Holder>(DIFF) {

    class Holder(val binding: ItemScanBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemScanBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val scan = getItem(position)
        val ctx = holder.binding.root.context
        val store = Store.fromSlug(scan.store)?.label ?: ctx.getString(R.string.store_none)
        holder.binding.tvName.text = scan.product_name
        holder.binding.tvMeta.text = ctx.getString(R.string.main_scan_meta, store, relative(scan.created_at))
        holder.binding.tvScore.showScorePill(scan.score, Category.of(scan.category))
        holder.binding.root.setOnClickListener { onClick(scan) }
    }

    private fun relative(iso: String): String = runCatching {
        val millis = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }.getOrDefault("")

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ScanDto>() {
            override fun areItemsTheSame(a: ScanDto, b: ScanDto) = a.id == b.id
            override fun areContentsTheSame(a: ScanDto, b: ScanDto) = a == b
        }
    }
}
