package com.example.localmusicapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 听歌排行 adapter —— 通用版，同时用于：
 *   - 歌曲排行：每行一首歌（primary=歌名, subtitle=歌手, trailing=播放时长）
 *   - 歌手排行：每行一个歌手（primary=歌手名, subtitle=空, trailing=N 首）
 */
class LeaderboardAdapter(
    initialItems: List<Row>,
    private val onItemClick: (position: Int, row: Row) -> Unit,
    private val onItemLongClick: ((position: Int, row: Row) -> Unit)? = null,
    /** 多选模式下选中集变动时的回调（和 SongAdapter 对齐） */
    var onSelectionChanged: ((Set<String>) -> Unit)? = null
) : RecyclerView.Adapter<LeaderboardAdapter.VH>() {

    data class Row(
        val file: MusicScanner.MusicFile,
        val primary: String,
        val subtitle: String,
        val trailing: String,
        val clickable: Boolean = true,
        val showRank: Boolean = true
    )

    private var items: List<Row> = initialItems
    private val pendingFlashPaths = linkedSetOf<String>()
    private var multiSelectMode: Boolean = false
    private var selectedPaths: Set<String> = emptySet()

    fun setMultiSelectMode(enabled: Boolean) {
        if (multiSelectMode == enabled) return
        multiSelectMode = enabled
        if (!enabled) selectedPaths = emptySet()
        notifyDataSetChanged()
    }

    fun isMultiSelectMode(): Boolean = multiSelectMode

    fun setSelectedPaths(paths: Set<String>) {
        if (selectedPaths == paths) return
        val prev = selectedPaths
        selectedPaths = paths.toSet()
        val changed = (prev + selectedPaths) - (prev intersect selectedPaths)
        for (p in changed) {
            val idx = positionOfPath(p)
            if (idx >= 0) notifyItemChanged(idx)
        }
    }

    fun allPaths(): List<String> = items.map { it.file.path }
    fun selectedPathsSnapshot(): Set<String> = selectedPaths.toSet()

    fun updateItems(newItems: List<Row>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun positionOfPath(path: String?): Int {
        if (path == null) return -1
        return items.indexOfFirst { it.file.path == path }
    }

    fun flashPath(path: String?) {
        val idx = positionOfPath(path)
        if (path.isNullOrBlank() || idx < 0) return
        pendingFlashPaths.add(path)
        notifyItemChanged(idx)
    }

    var currentPath: String? = null
        set(value) {
            if (field == value) return
            val old = field
            field = value
            positionOfPath(old).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
            positionOfPath(value).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val rank: TextView = v.findViewById(R.id.tvRank)
        val cover: ImageView = v.findViewById(R.id.ivCover)
        val title: TextView = v.findViewById(R.id.tvTitle)
        val subtitle: TextView = v.findViewById(R.id.tvSubtitle)
        val time: TextView = v.findViewById(R.id.tvListenTime)
        val checkIcon: ImageView = v.findViewById(R.id.ivMultiSelectCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.leaderboard_item, parent, false)
        AppFont.applyTo(v)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        val isCurrent = row.file.path == currentPath
        val isSelected = multiSelectMode && row.clickable && selectedPaths.contains(row.file.path)

        if (row.showRank) {
            holder.rank.visibility = View.VISIBLE
            holder.rank.text = (position + 1).toString()
            holder.rank.setTextColor(
                when (position) {
                    0 -> 0xFFFFB300.toInt()
                    1 -> 0xFF90A4AE.toInt()
                    2 -> 0xFFA0522D.toInt()
                    else -> 0xFF888888.toInt()
                }
            )
        } else {
            holder.rank.visibility = View.GONE
            holder.rank.text = ""
        }

        holder.title.text = row.primary
        if (row.subtitle.isBlank()) {
            holder.subtitle.visibility = View.GONE
        } else {
            holder.subtitle.visibility = View.VISIBLE
            holder.subtitle.text = row.subtitle
        }
        holder.time.text = row.trailing

        // 多选 UI：有 file path 的行才显示圆圈；歌手行 (clickable=false) 不参与多选
        if (multiSelectMode && row.clickable) {
            holder.checkIcon.visibility = View.VISIBLE
            holder.checkIcon.setImageResource(
                if (isSelected) R.drawable.ic_multiselect_circle_checked
                else R.drawable.ic_multiselect_circle_empty
            )
            holder.time.visibility = View.GONE
        } else {
            holder.checkIcon.visibility = View.GONE
            holder.time.visibility = View.VISIBLE
        }

        if (multiSelectMode && row.clickable) {
            holder.itemView.setBackgroundResource(R.drawable.song_item_touch_bg)
            holder.cover.alpha = 1f
        } else {
            holder.cover.alpha = 1f
        }

        if (isCurrent && !isSelected) {
            holder.title.setTextColor(HIGHLIGHT)
            holder.subtitle.setTextColor(HIGHLIGHT)
        } else {
            holder.title.setTextColor(0xFF000000.toInt())
            holder.subtitle.setTextColor(0xFF888888.toInt())
        }

        if (row.clickable) {
            if (!multiSelectMode) {
                holder.itemView.setBackgroundResource(R.drawable.song_item_touch_bg)
            }
            holder.itemView.isClickable = true
            holder.itemView.isFocusable = true
            holder.itemView.isActivated = false
            holder.itemView.setOnClickListener {
                val adapterPos = holder.bindingAdapterPosition
                if (adapterPos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (multiSelectMode) {
                    UiEffects.pulseSelection(holder.itemView, durationMs = 90L)
                    val path = items[adapterPos].file.path
                    val next = selectedPaths.toMutableSet()
                    if (!next.add(path)) next.remove(path)
                    val snapshot = next.toSet()
                    selectedPaths = snapshot
                    notifyItemChanged(adapterPos)
                    onSelectionChanged?.invoke(snapshot)
                } else {
                    UiEffects.pulseSelection(holder.itemView)
                    onItemClick(adapterPos, items[adapterPos])
                }
            }
            holder.itemView.setOnLongClickListener {
                val adapterPos = holder.bindingAdapterPosition
                if (adapterPos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                if (multiSelectMode) return@setOnLongClickListener true
                val callback = onItemLongClick ?: return@setOnLongClickListener false
                UiEffects.pulseSelection(holder.itemView, durationMs = 180L)
                callback(adapterPos, items[adapterPos])
                true
            }
        } else {
            holder.itemView.setBackgroundColor(0x00000000)
            holder.itemView.isClickable = false
            holder.itemView.isFocusable = false
            holder.itemView.isActivated = false
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnLongClickListener(null)
        }
        CoverLoader.load(holder.cover, row.file.path, R.drawable.music_note_24)

        if (pendingFlashPaths.remove(row.file.path)) {
            holder.itemView.post { UiEffects.flashTwice(holder.itemView) }
        } else {
            holder.itemView.alpha = 1f
        }
    }

    companion object {
        private const val HIGHLIGHT = 0xFF1565C0.toInt()
    }
}
