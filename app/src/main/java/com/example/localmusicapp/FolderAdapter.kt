package com.example.localmusicapp

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView

class FolderAdapter(
    initialItems: List<FolderEntry>,
    private val onItemClick: (position: Int, item: FolderEntry) -> Unit,
    private val onItemLongClick: ((position: Int, item: FolderEntry) -> Unit)? = null
) : RecyclerView.Adapter<FolderAdapter.VH>() {

    init {
        setHasStableIds(true)
    }

    private var items: List<FolderEntry> = initialItems
    private val pendingFlashKeys = linkedSetOf<String>()

    fun updateItems(newItems: List<FolderEntry>) {
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

    fun itemAt(position: Int): FolderEntry? = items.getOrNull(position)

    fun flashKey(key: String?) {
        val idx = positionOfKey(key)
        if (idx < 0 || key.isNullOrBlank()) return
        pendingFlashKeys.add(key)
        notifyItemChanged(idx)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cover: ShapeableImageView = itemView.findViewById(R.id.ivFolderCover)
        val name: TextView = itemView.findViewById(R.id.tvFolderName)
        val meta: TextView = itemView.findViewById(R.id.tvFolderMeta)
        val more: ImageButton = itemView.findViewById(R.id.btnFolderMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.folder_item, parent, false)
        AppFont.applyTo(view)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.meta.text = if (item.hidden) "${item.songCount}首歌曲 · 已隐藏" else "${item.songCount}首歌曲"
        holder.more.visibility = View.GONE

        val strikeFlag = Paint.STRIKE_THRU_TEXT_FLAG
        if (item.hidden) {
            holder.name.paintFlags = holder.name.paintFlags or strikeFlag
            holder.meta.paintFlags = holder.meta.paintFlags or strikeFlag
            holder.name.setTextColor(0xFF9E9E9E.toInt())
            holder.meta.setTextColor(0xFFB0B0B0.toInt())
            holder.cover.alpha = 0.42f
        } else {
            holder.name.paintFlags = holder.name.paintFlags and strikeFlag.inv()
            holder.meta.paintFlags = holder.meta.paintFlags and strikeFlag.inv()
            holder.name.setTextColor(0xFF000000.toInt())
            holder.meta.setTextColor(0xFF888888.toInt())
            holder.cover.alpha = 1f
        }

        if (item.coverPath.isNotBlank()) {
            CoverLoader.load(holder.cover, item.coverPath, R.drawable.ic_folder_24)
        } else {
            holder.cover.setImageResource(R.drawable.ic_folder_24)
            CoverFrameStyler.applyDefault(holder.cover)
        }
        holder.cover.alpha = if (item.hidden) 0.42f else 1f

        holder.itemView.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                UiEffects.pulseSelection(holder.itemView)
                onItemClick(adapterPos, items[adapterPos])
            }
        }

        holder.itemView.setOnLongClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
            val callback = onItemLongClick ?: return@setOnLongClickListener false
            UiEffects.pulseSelection(holder.itemView, durationMs = 180L)
            callback(adapterPos, items[adapterPos])
            true
        }

        if (pendingFlashKeys.remove(item.key)) {
            holder.itemView.post { UiEffects.flashTwice(holder.itemView) }
        } else {
            holder.itemView.alpha = 1f
        }
    }
}
