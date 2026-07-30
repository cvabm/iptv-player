package com.example.iptvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.iptvplayer.R
import com.example.iptvplayer.data.Channel
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip

class ChannelAdapter(
    private val onClick: (Channel) -> Unit
) : ListAdapter<Channel, ChannelAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), position + 1, onClick)
    }

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
        private val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(a: Channel, b: Channel) = a.url == b.url
            override fun areContentsTheSame(a: Channel, b: Channel) = a == b
        }
    }
}
