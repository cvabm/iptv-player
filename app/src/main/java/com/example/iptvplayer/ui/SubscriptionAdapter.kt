package com.example.iptvplayer.ui

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.iptvplayer.R
import com.example.iptvplayer.data.SourceType
import com.example.iptvplayer.data.Subscription

class SubscriptionAdapter(
    private val selectedId: String?,
    private val onSelect: (Subscription) -> Unit,
    private val onDelete: (Subscription) -> Unit
) : RecyclerView.Adapter<SubscriptionAdapter.VH>() {

    private val items = mutableListOf<Subscription>()

    fun submit(list: List<Subscription>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subscription, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], selectedId, onSelect, onDelete)
    }

    override fun getItemCount() = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val row: View = itemView.findViewById(R.id.row)
        private val name: TextView = itemView.findViewById(R.id.tvSubName)
        private val meta: TextView = itemView.findViewById(R.id.tvSubMeta)
        private val selected: ImageView = itemView.findViewById(R.id.ivSelected)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

        fun bind(
            item: Subscription,
            selectedId: String?,
            onSelect: (Subscription) -> Unit,
            onDelete: (Subscription) -> Unit
        ) {
            name.text = item.name
            val typeLabel = when (item.type) {
                SourceType.URL -> "URL"
                SourceType.FILE -> "文件"
                SourceType.SINGLE_STREAM -> "单路流"
                SourceType.PASTE -> "粘贴"
            }
            meta.text = itemView.context.getString(
                R.string.subscription_meta,
                typeLabel,
                item.channelCount
            )
            val isSelected = item.id == selectedId
            selected.visibility = if (isSelected) View.VISIBLE else View.GONE
            name.setTextColor(
                resolveColorAttr(
                    if (isSelected) com.google.android.material.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurface
                )
            )
            row.setOnClickListener { onSelect(item) }
            btnDelete.setOnClickListener { onDelete(item) }
        }

        private fun resolveColorAttr(attr: Int): Int {
            val tv = TypedValue()
            itemView.context.theme.resolveAttribute(attr, tv, true)
            return tv.data
        }
    }
}
