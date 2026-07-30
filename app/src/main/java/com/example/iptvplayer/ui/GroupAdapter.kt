package com.example.iptvplayer.ui

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.iptvplayer.R

data class GroupItem(
    val name: String,
    val count: Int
)

class GroupAdapter(
    private val selectedName: String,
    private val onClick: (GroupItem) -> Unit
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    private val items = mutableListOf<GroupItem>()

    fun submit(list: List<GroupItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], selectedName, onClick)
    }

    override fun getItemCount() = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val row: View = itemView.findViewById(R.id.row)
        private val name: TextView = itemView.findViewById(R.id.tvGroupName)
        private val count: TextView = itemView.findViewById(R.id.tvGroupItemCount)
        private val selected: ImageView = itemView.findViewById(R.id.tvSelected)

        fun bind(item: GroupItem, selectedName: String, onClick: (GroupItem) -> Unit) {
            name.text = item.name
            count.text = itemView.context.getString(R.string.group_channel_count, item.count)
            val isSelected = item.name == selectedName
            selected.visibility = if (isSelected) View.VISIBLE else View.GONE
            name.setTextColor(resolveColorAttr(
                if (isSelected) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurface
            ))
            row.setOnClickListener { onClick(item) }
        }

        private fun resolveColorAttr(attr: Int): Int {
            val tv = TypedValue()
            itemView.context.theme.resolveAttribute(attr, tv, true)
            return tv.data
        }
    }
}
