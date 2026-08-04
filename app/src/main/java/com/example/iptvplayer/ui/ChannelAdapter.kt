package com.example.iptvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.iptvplayer.R
import com.example.iptvplayer.data.Channel
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip

/**
 * Channel list adapter tuned for very large playlists.
 *
 * [ListAdapter] + DiffUtil becomes multi-second stalls at 10k–50k items.
 * For large replacements we skip DiffUtil and use [notifyDataSetChanged]
 * (RecyclerView only binds visible rows, so this is fine).
 */
class ChannelAdapter(
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private var items: List<Channel> = emptyList()

    val currentList: List<Channel>
        get() = items

    fun submitList(list: List<Channel>) {
        val old = items
        val newList = list
        // DiffUtil is O(n²)-ish in worst cases and always O(n); skip for big lists.
        if (old.size > DIFF_THRESHOLD || newList.size > DIFF_THRESHOLD ||
            old.size + newList.size > DIFF_THRESHOLD * 2
        ) {
            items = newList
            notifyDataSetChanged()
            return
        }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos].url == newList[newPos].url
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos] == newList[newPos]
        })
        items = newList
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position + 1, onClick)
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.card)
        private val index: TextView = itemView.findViewById(R.id.tvIndex)
        private val name: TextView = itemView.findViewById(R.id.tvName)
        private val group: Chip = itemView.findViewById(R.id.tvGroup)
        private val url: TextView = itemView.findViewById(R.id.tvUrl)

        fun bind(channel: Channel, position: Int, onClick: (Channel) -> Unit) {
            index.text = position.toString()
            name.text = channel.name
            group.text = channel.group
            url.text = channel.url
            card.setOnClickListener { onClick(channel) }
        }
    }

    companion object {
        /** Above this size, DiffUtil cost dominates frame time. */
        private const val DIFF_THRESHOLD = 800
    }
}
