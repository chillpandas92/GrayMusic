package com.example.localmusicapp

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 播放列表抽屉的 RecyclerView 适配器。
 *
 * - 当前播放的行会高亮，右边显示小波浪
 * - 当前播放的行不能被拖动；其他行可通过左侧双横线调整顺序
 * - 拖动后的顺序由 PlayerActivity 提交给 PlaybackService，上一首 / 下一首立即按新顺序生效
 */
class QueueAdapter(
    initialItems: List<MusicScanner.MusicFile>,
    private val onItemClick: (position: Int, file: MusicScanner.MusicFile) -> Unit,
    private val onItemLongClick: ((position: Int, file: MusicScanner.MusicFile) -> Unit)? = null,
    private val onRemoveClick: ((position: Int, file: MusicScanner.MusicFile) -> Unit)? = null,
    private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null
) : RecyclerView.Adapter<QueueAdapter.VH>() {

    private var items: MutableList<MusicScanner.MusicFile> = initialItems.toMutableList()

    fun updateItems(newItems: List<MusicScanner.MusicFile>) {
        items = newItems.toMutableList()
        notifyDataSetChanged()
    }

    fun itemsSnapshot(): List<MusicScanner.MusicFile> = items.toList()

    fun canDrag(position: Int): Boolean {
        if (position !in items.indices) return false
        return items[position].path != currentPath
    }

    fun moveItem(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition !in items.indices || toPosition !in items.indices) return false
        if (fromPosition == toPosition) return false
        if (!canDrag(fromPosition)) return false

        val moved = items.removeAt(fromPosition)
        val target = toPosition.coerceIn(0, items.size)
        items.add(target, moved)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    /** 当前正在播放的 path；修改时只刷新旧/新两行 */
    var currentPath: String? = null
        set(value) {
            if (field == value) return
            val old = field
            field = value
            val oldIdx = indexOfPath(old)
            val newIdx = indexOfPath(value)
            if (oldIdx >= 0) notifyItemChanged(oldIdx)
            if (newIdx >= 0 && newIdx != oldIdx) notifyItemChanged(newIdx)
        }

    /** 当前是否正在播放；只影响当前行的小波浪动画 */
    var isPlaying: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            indexOfPath(currentPath).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        }

    private fun indexOfPath(path: String?): Int {
        if (path == null) return -1
        return items.indexOfFirst { it.path == path }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val dragHandle: ImageView = itemView.findViewById(R.id.ivQueueDragHandle)
        val cover: ImageView = itemView.findViewById(R.id.ivQueueCover)
        val title: TextView = itemView.findViewById(R.id.tvQueueTitle)
        val artist: TextView = itemView.findViewById(R.id.tvQueueArtist)
        val wave: WaveBarsView = itemView.findViewById(R.id.vQueueWave)
        val removeButton: ImageButton = itemView.findViewById(R.id.btnQueueRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.queue_item, parent, false)
        AppFont.applyTo(v)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        val isCurrent = s.path == currentPath

        holder.title.text = s.title
        holder.artist.text = ArtistUtils.displayArtists(s.artist)

        if (isCurrent) {
            holder.title.setTextColor(HIGHLIGHT)
            holder.artist.setTextColor(HIGHLIGHT)
            holder.wave.visibility = View.VISIBLE
            holder.wave.animating = isPlaying
            holder.dragHandle.visibility = View.INVISIBLE
            holder.dragHandle.isEnabled = false
        } else {
            holder.title.setTextColor(NORMAL_TITLE)
            holder.artist.setTextColor(NORMAL_SUB)
            holder.wave.animating = false
            holder.wave.visibility = View.GONE
            holder.dragHandle.visibility = View.VISIBLE
            holder.dragHandle.isEnabled = true
        }

        holder.dragHandle.setOnTouchListener { _, event ->
            val adapterPos = holder.bindingAdapterPosition
            if (event.actionMasked == MotionEvent.ACTION_DOWN && canDrag(adapterPos)) {
                holder.itemView.performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
                onStartDrag?.invoke(holder)
                true
            } else {
                false
            }
        }

        holder.itemView.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                onItemClick(adapterPos, items[adapterPos])
            }
        }

        holder.itemView.setOnLongClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
            val callback = onItemLongClick ?: return@setOnLongClickListener false
            callback(adapterPos, items[adapterPos])
            true
        }

        holder.removeButton.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                onRemoveClick?.invoke(adapterPos, items[adapterPos])
            }
        }

        CoverLoader.load(holder.cover, s.path, R.drawable.music_note_24)
    }

    companion object {
        private const val HIGHLIGHT = 0xFF1565C0.toInt()
        private const val NORMAL_TITLE = 0xFF000000.toInt()
        private const val NORMAL_SUB = 0xFF888888.toInt()
    }
}
