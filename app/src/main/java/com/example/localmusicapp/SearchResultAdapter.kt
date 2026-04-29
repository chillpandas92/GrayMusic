package com.example.localmusicapp

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SearchResultAdapter(
    private val presentation: SearchSessionHolder.Presentation = SearchSessionHolder.Presentation.SONG,
    private val onItemClick: (SearchSessionHolder.Item) -> Unit,
    private val onItemLongClick: ((SearchSessionHolder.Item) -> Unit)? = null,
    var onSelectionChanged: ((Set<String>) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    init {
        setHasStableIds(true)
    }

    private var items: List<SearchSessionHolder.Item> = emptyList()
    private var query: String = ""
    private var multiSelectMode: Boolean = false
    private var selectedPaths: Set<String> = emptySet()

    fun submit(newItems: List<SearchSessionHolder.Item>, query: String) {
        items = newItems
        this.query = query
        notifyDataSetChanged()
    }

    fun setMultiSelectMode(enabled: Boolean) {
        if (multiSelectMode == enabled) return
        multiSelectMode = enabled
        if (!enabled) selectedPaths = emptySet()
        notifyDataSetChanged()
    }

    fun setSelectedPaths(paths: Set<String>) {
        if (selectedPaths == paths) return
        selectedPaths = paths.toSet()
        notifyDataSetChanged()
    }

    fun allPaths(): List<String> = items.map { it.path }

    fun selectedPathsSnapshot(): Set<String> = selectedPaths.toSet()

    class SongVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cover: ImageView = itemView.findViewById(R.id.ivCover)
        val title: TextView = itemView.findViewById(R.id.tvTitle)
        val subtitle: TextView = itemView.findViewById(R.id.tvSubtitle)
        val qualityBadge: TextView = itemView.findViewById(R.id.tvQualityBadge)
        val duration: TextView = itemView.findViewById(R.id.tvDuration)
        val metricIcon: ImageView = itemView.findViewById(R.id.ivMetricIcon)
        val checkIcon: ImageView = itemView.findViewById(R.id.ivMultiSelectCheck)
    }

    class AlbumVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cover: ImageView = itemView.findViewById(R.id.ivAlbumCover)
        val title: TextView = itemView.findViewById(R.id.tvAlbumTitle)
        val subtitle: TextView = itemView.findViewById(R.id.tvAlbumArtist)
        val trailing: TextView = itemView.findViewById(R.id.tvAlbumYear)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = if (presentation == SearchSessionHolder.Presentation.ALBUM) {
            inflater.inflate(R.layout.album_item, parent, false)
        } else {
            inflater.inflate(R.layout.song_item, parent, false)
        }
        AppFont.applyTo(view)
        return if (presentation == SearchSessionHolder.Presentation.ALBUM) {
            AlbumVH(view)
        } else {
            SongVH(view)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].path.hashCode().toLong()

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is SongVH -> bindSong(holder, item)
            is AlbumVH -> bindAlbum(holder, item)
        }

        holder.itemView.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos == RecyclerView.NO_POSITION) return@setOnClickListener
            val clicked = items[adapterPos]
            if (presentation == SearchSessionHolder.Presentation.SONG && multiSelectMode) {
                UiEffects.pulseSelection(holder.itemView, durationMs = 90L)
                val next = selectedPaths.toMutableSet()
                if (!next.add(clicked.path)) next.remove(clicked.path)
                selectedPaths = next.toSet()
                notifyItemChanged(adapterPos)
                onSelectionChanged?.invoke(selectedPaths.toSet())
            } else {
                UiEffects.pulseSelection(holder.itemView)
                onItemClick(clicked)
            }
        }

        if (presentation == SearchSessionHolder.Presentation.SONG && onItemLongClick != null) {
            holder.itemView.setOnLongClickListener {
                val adapterPos = holder.bindingAdapterPosition
                if (adapterPos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                if (multiSelectMode) return@setOnLongClickListener true
                UiEffects.pulseSelection(holder.itemView, durationMs = 180L)
                onItemLongClick.invoke(items[adapterPos])
                true
            }
        } else {
            holder.itemView.setOnLongClickListener(null)
        }
    }

    private fun bindSong(holder: SongVH, item: SearchSessionHolder.Item) {
        holder.title.text = highlight(item.title, query)
        if (item.subtitle.isBlank()) {
            holder.subtitle.visibility = View.GONE
        } else {
            holder.subtitle.visibility = View.VISIBLE
            holder.subtitle.text = highlight(item.subtitle, query)
        }
        holder.qualityBadge.visibility = View.GONE
        holder.metricIcon.visibility = View.GONE
        if (multiSelectMode) {
            val selected = selectedPaths.contains(item.path)
            holder.checkIcon.visibility = View.VISIBLE
            holder.checkIcon.setImageResource(
                if (selected) R.drawable.ic_multiselect_circle_checked
                else R.drawable.ic_multiselect_circle_empty
            )
            holder.duration.visibility = View.GONE
        } else {
            holder.checkIcon.visibility = View.GONE
            holder.duration.visibility = View.GONE
        }
        holder.title.setTextColor(NORMAL_TITLE)
        holder.subtitle.setTextColor(NORMAL_SUB)
        CoverLoader.load(holder.cover, item.coverPath.ifBlank { item.path }, R.drawable.music_note_24)
    }

    private fun bindAlbum(holder: AlbumVH, item: SearchSessionHolder.Item) {
        holder.title.text = highlight(item.title, query)
        holder.subtitle.text = highlight(item.subtitle, query)
        holder.trailing.text = highlight(item.trailing, query)
        CoverLoader.load(holder.cover, item.coverPath.ifBlank { item.path }, R.drawable.music_note_24)
    }

    private fun highlight(text: String, query: String): CharSequence {
        if (query.isBlank() || text.isBlank()) return text
        val builder = SpannableStringBuilder(text)
        val q = query.lowercase()
        val src = text.lowercase()
        var start = src.indexOf(q)
        while (start >= 0) {
            val end = start + q.length
            builder.setSpan(
                ForegroundColorSpan(HIGHLIGHT),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            start = src.indexOf(q, end)
        }
        return builder
    }

    companion object {
        private const val HIGHLIGHT = 0xFF1565C0.toInt()
        private const val NORMAL_TITLE = 0xFF000000.toInt()
        private const val NORMAL_SUB = 0xFF888888.toInt()
    }
}
