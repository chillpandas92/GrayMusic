package com.example.localmusicapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class AlbumAdapter(
    initialItems: List<AlbumEntry>,
    private val onItemClick: (position: Int, item: AlbumEntry) -> Unit
) : RecyclerView.Adapter<AlbumAdapter.VH>() {

    init {
        setHasStableIds(true)
    }

    private var items: List<AlbumEntry> = initialItems
    private val pendingFlashKeys = linkedSetOf<String>()

    fun updateItems(newItems: List<AlbumEntry>) {
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


    var currentKey: String? = null
        set(value) {
            if (field == value) return
            val old = field
            field = value
            val oldIdx = positionOfKey(old)
            val newIdx = positionOfKey(value)
            if (oldIdx >= 0) notifyItemChanged(oldIdx)
            if (newIdx >= 0 && newIdx != oldIdx) notifyItemChanged(newIdx)
        }

    fun flashKey(key: String?) {
        val idx = positionOfKey(key)
        if (idx < 0 || key.isNullOrBlank()) return
        pendingFlashKeys.add(key)
        notifyItemChanged(idx)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: MaterialCardView = itemView as MaterialCardView
        val cover: ImageView = itemView.findViewById(R.id.ivAlbumCover)
        val title: TextView = itemView.findViewById(R.id.tvAlbumTitle)
        val artist: TextView = itemView.findViewById(R.id.tvAlbumArtist)
        val year: TextView = itemView.findViewById(R.id.tvAlbumYear)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.album_item, parent, false)
        AppFont.applyTo(view)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.artist.text = item.allArtistsLabel
        if (item.yearText.isBlank()) {
            holder.year.visibility = View.GONE
        } else {
            holder.year.visibility = View.VISIBLE
            holder.year.text = item.yearText
        }
        // 专辑卡片不随播放状态高亮——只有专辑详情页（bottom sheet）内的歌曲行会高亮当前曲目
        holder.title.setTextColor(NORMAL_TITLE)
        holder.artist.setTextColor(NORMAL_SUB)
        holder.year.setTextColor(NORMAL_YEAR)
        holder.card.setCardBackgroundColor(CARD_BG)
        holder.card.cardElevation = 4f
        CoverLoader.loadAlbumCover(holder.cover, item.coverPath, R.drawable.music_note_24)

        holder.itemView.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                onItemClick(adapterPos, items[adapterPos])
            }
        }

        if (pendingFlashKeys.remove(item.key)) {
            holder.itemView.post { UiEffects.flashTwice(holder.itemView) }
        } else {
            holder.itemView.alpha = 1f
        }
    }
    companion object {
        private const val NORMAL_TITLE = 0xFF000000.toInt()
        private const val NORMAL_SUB = 0xFF777777.toInt()
        private const val NORMAL_YEAR = 0xFF999999.toInt()
        private const val CARD_BG = 0xFFFFFFFF.toInt()
    }
}
