package com.example.localmusicapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class ArtistAdapter(
    initialItems: List<ArtistEntry>,
    private val onItemClick: (position: Int, item: ArtistEntry) -> Unit
) : RecyclerView.Adapter<ArtistAdapter.VH>() {

    init {
        setHasStableIds(true)
    }

    private var items: List<ArtistEntry> = initialItems
    private val pendingFlashKeys = linkedSetOf<String>()

    fun updateItems(newItems: List<ArtistEntry>) {
        val oldItems = items
        if (oldItems === newItems) return
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition].key == newItems[newItemPosition].key
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition] == newItems[newItemPosition]
            }
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    fun positionOfKey(key: String?): Int {
        if (key.isNullOrBlank()) return -1
        return items.indexOfFirst { it.key == key }
    }

    fun flashKey(key: String?) {
        val idx = positionOfKey(key)
        if (idx < 0 || key.isNullOrBlank()) return
        pendingFlashKeys.add(key)
        notifyItemChanged(idx)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.tvArtistName)
        val meta: TextView = itemView.findViewById(R.id.tvArtistMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.artist_item, parent, false)
        AppFont.applyTo(view)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.meta.text = item.metaText
        holder.name.setTextColor(0xFF000000.toInt())
        holder.meta.setTextColor(0xFF888888.toInt())

        holder.itemView.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                UiEffects.pulseSelection(holder.itemView)
                onItemClick(adapterPos, items[adapterPos])
            }
        }

        if (pendingFlashKeys.remove(item.key)) {
            holder.itemView.post { UiEffects.flashTwice(holder.itemView) }
        } else {
            holder.itemView.alpha = 1f
        }
    }
}
