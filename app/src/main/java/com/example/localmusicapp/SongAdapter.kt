package com.example.localmusicapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class SongAdapter(
    initialItems: List<MusicScanner.MusicFile>,
    private val onItemClick: (position: Int, file: MusicScanner.MusicFile) -> Unit,
    private val onItemLongClick: ((position: Int, file: MusicScanner.MusicFile) -> Unit)? = null,
    /**
     * 多选模式下选中集变化时回调。Activity 用这个把选中集同步到自己的状态里、刷新底部操作栏。
     * 传回的是新的完整 selectedPaths 快照（不可变拷贝）。
     */
    var onSelectionChanged: ((Set<String>) -> Unit)? = null
) : RecyclerView.Adapter<SongAdapter.VH>() {

    enum class TrailingMode { DURATION, PLAY_COUNT }

    init {
        setHasStableIds(true)
    }

    private var items: List<MusicScanner.MusicFile> = initialItems
    private val pendingFlashPaths = linkedSetOf<String>()
    private var trailingMode: TrailingMode = TrailingMode.DURATION
    private var playCountMap: Map<String, Int> = emptyMap()
    // 多选模式：外部通过 setMultiSelectMode / setSelectedPaths 驱动
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
        // 只刷新变动的行
        val changed = (prev + selectedPaths) - (prev intersect selectedPaths)
        for (p in changed) {
            val idx = positionOf(p)
            if (idx >= 0) notifyItemChanged(idx)
        }
    }

    /** 当前 adapter 里所有行的 path，顺序和显示顺序一致。用于"全选"。 */
    fun allPaths(): List<String> = items.map { it.path }

    /** 当前 adapter 已选中的 path 快照，用于 Activity 侧读取。 */
    fun selectedPathsSnapshot(): Set<String> = selectedPaths.toSet()

    /** 重新设置歌曲列表（用于排序变化）。会保留 currentPath 高亮状态。 */
    fun updateItems(newItems: List<MusicScanner.MusicFile>) {
        val oldItems = items
        if (oldItems === newItems) return
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition].path == newItems[newItemPosition].path
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition] == newItems[newItemPosition]
            }
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    /** 当前正在播放歌曲的路径；设置时会刷新新旧两项 */
    var currentPath: String? = null
        set(value) {
            if (field == value) return
            val old = field
            field = value
            val oldIdx = positionOf(old)
            val newIdx = positionOf(value)
            if (oldIdx >= 0) notifyItemChanged(oldIdx)
            if (newIdx >= 0 && newIdx != oldIdx) notifyItemChanged(newIdx)
        }

    fun positionOf(path: String?): Int {
        if (path == null) return -1
        return items.indexOfFirst { it.path == path }
    }

    fun flashPath(path: String?) {
        val idx = positionOf(path)
        if (path.isNullOrBlank() || idx < 0) return
        pendingFlashPaths.add(path)
        notifyItemChanged(idx)
    }

    fun setTrailingMode(mode: TrailingMode) {
        if (trailingMode == mode) return
        trailingMode = mode
        notifyDataSetChanged()
    }

    fun setPlayCountMap(counts: Map<String, Int>) {
        playCountMap = counts
        if (trailingMode == TrailingMode.PLAY_COUNT) notifyDataSetChanged()
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cover: ImageView = itemView.findViewById(R.id.ivCover)
        val title: TextView = itemView.findViewById(R.id.tvTitle)
        val subtitle: TextView = itemView.findViewById(R.id.tvSubtitle)
        val qualityBadge: TextView = itemView.findViewById(R.id.tvQualityBadge)
        val duration: TextView = itemView.findViewById(R.id.tvDuration)
        val metricIcon: ImageView = itemView.findViewById(R.id.ivMetricIcon)
        // 多选圆圈：平时 GONE，进多选模式时切换 empty / checked
        val checkIcon: ImageView = itemView.findViewById(R.id.ivMultiSelectCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.song_item, parent, false)
        AppFont.applyTo(v)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].path.hashCode().toLong()

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        val isCurrent = s.path == currentPath
        val isSelected = multiSelectMode && selectedPaths.contains(s.path)

        holder.title.text = s.title
        holder.subtitle.text = buildSubtitle(s.artist, s.album)
        val qualityBadge = if (PlaybackSettings.isQualityBadgeEnabled(holder.itemView.context)) {
            AudioQualityClassifier.cached(s)?.badge
        } else null
        if (qualityBadge.isNullOrBlank()) {
            holder.qualityBadge.visibility = View.GONE
            holder.qualityBadge.text = ""
        } else {
            holder.qualityBadge.visibility = View.VISIBLE
            holder.qualityBadge.text = qualityBadge
        }

        // 进多选模式时用圆圈替换原有的"播放次数/时长"区域，让视觉重心落在 check 上
        if (multiSelectMode) {
            holder.checkIcon.visibility = View.VISIBLE
            holder.checkIcon.setImageResource(
                if (isSelected) R.drawable.ic_multiselect_circle_checked
                else R.drawable.ic_multiselect_circle_empty
            )
            holder.metricIcon.visibility = View.GONE
            holder.duration.visibility = View.GONE
        } else {
            holder.checkIcon.visibility = View.GONE
            holder.duration.visibility = View.VISIBLE
            if (trailingMode == TrailingMode.PLAY_COUNT) {
                holder.metricIcon.visibility = View.VISIBLE
                holder.duration.text = (playCountMap[s.path] ?: 0).toString()
            } else {
                holder.metricIcon.visibility = View.GONE
                holder.duration.text = formatDuration(s.duration)
            }
        }

        // 多选模式下用平色（灰/透明）；非多选模式下恢复 song_item_touch_bg selector，
        // 这样 pulseSelection 设置的 isActivated=true 能把 state_activated 的灰色 flash 出来，
        // 长按 / 点击都会短暂显示灰色小框，和专辑页里点击歌曲的反馈一致。
        if (multiSelectMode) {
            holder.itemView.setBackgroundResource(R.drawable.song_item_touch_bg)
            holder.cover.alpha = 1f
        } else {
            holder.itemView.setBackgroundResource(R.drawable.song_item_touch_bg)
            holder.cover.alpha = 1f
        }

        // 正在播放的用深蓝；否则恢复默认黑/灰
        if (isCurrent && !isSelected) {
            holder.title.setTextColor(HIGHLIGHT)
            holder.subtitle.setTextColor(HIGHLIGHT)
            holder.duration.setTextColor(HIGHLIGHT)
            holder.metricIcon.setColorFilter(HIGHLIGHT)
        } else {
            holder.title.setTextColor(NORMAL_TITLE)
            holder.subtitle.setTextColor(NORMAL_SUB)
            holder.duration.setTextColor(NORMAL_SUB)
            holder.metricIcon.setColorFilter(NORMAL_SUB)
        }

        holder.itemView.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos == RecyclerView.NO_POSITION) return@setOnClickListener
            if (multiSelectMode) {
                // 多选模式下点击 = 切换选中状态（不播放）；只保留点击瞬间灰色反馈，不保留选中底色。
                UiEffects.pulseSelection(holder.itemView, durationMs = 90L)
                val path = items[adapterPos].path
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
            if (multiSelectMode) return@setOnLongClickListener true  // 多选模式里长按无动作
            val callback = onItemLongClick ?: return@setOnLongClickListener false
            UiEffects.pulseSelection(holder.itemView, durationMs = 180L)
            callback(adapterPos, items[adapterPos])
            true
        }

        CoverLoader.load(holder.cover, s.path, R.drawable.music_note_24)

        if (pendingFlashPaths.remove(s.path)) {
            holder.itemView.post { UiEffects.flashTwice(holder.itemView) }
        } else {
            holder.itemView.alpha = 1f
        }
    }

    companion object {
        private const val HIGHLIGHT = 0xFF1565C0.toInt()  // Material Blue 800
        private const val NORMAL_TITLE = 0xFF000000.toInt()
        private const val NORMAL_SUB = 0xFF888888.toInt()

        fun buildSubtitle(artist: String, album: String): String {
            val a = ArtistUtils.displayArtists(artist)
            val b = album.ifBlank { "未知专辑" }
            return if (a == b) a else "$a  ·  $b"
        }

        fun formatDuration(ms: Long): String {
            if (ms <= 0) return "--:--"
            val totalSec = ms / 1000
            val m = totalSec / 60
            val s = totalSec % 60
            return "%02d:%02d".format(m, s)
        }
    }
}
