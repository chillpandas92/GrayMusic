package com.example.localmusicapp

import android.graphics.Color
import android.os.Build
import android.text.Layout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView

class LyricLineAdapter(
    private val onLineClick: (LyricRepository.LyricLine) -> Unit
) : RecyclerView.Adapter<LyricLineAdapter.LyricViewHolder>() {

    private val items = mutableListOf<LyricRepository.LyricLine>()
    private val attachedTextViews = LinkedHashSet<KaraokeLyricTextView>()
    // 当前有哪些行处于"高亮中" —— 支持多行同时高亮，覆盖时间重叠的逐字歌词。
    private var activeTimes: Set<Long> = emptySet()
    private var playbackPositionMs: Long = 0L
    // primaryColor: 用于 inactive 行与未唱部分（偏深、高对比、贴近文本常规色）。
    // accentColor: 用于 active 行与已唱填色（偏亮/饱和，来自封面）。两者分开，避免只用一种。
    private var primaryColor: Int = Color.BLACK
    private var accentColor: Int = Color.BLACK
    private var hasTimedLines: Boolean = false
    private var measurePaint: TextPaint? = null
    private var recyclerContentWidthPx: Int = 0
    private var attachedRecycler: RecyclerView? = null
    private var alignment: LyricsSettings.Alignment = LyricsSettings.Alignment.LEFT

    private companion object {
        // 活跃行和非活跃行用同一个字号：不再"当前行放大"。这样歌词不会忽大忽小、
        // 更平静、也方便容纳长句。
        const val ACTIVE_TEXT_SIZE_SP = 22.2f
        const val NORMAL_TEXT_SIZE_SP = 22.2f
        // 相同 DOM（多个相同行首时间戳）只让第一行保持主字号，后续行作为补充歌词缩小显示。
        const val CONTINUATION_TEXT_SIZE_SP = 18.8f
        const val PRIMARY_SLOT_LINE_HEIGHT_SP = 31.5f
        const val CONTINUATION_SLOT_LINE_HEIGHT_SP = 25.8f
        // 长句允许换到多行；5 行基本能覆盖绝大多数情况，再长才会尾部 "..."。
        const val MAX_LYRIC_LINES = 5
    }

    init {
        setHasStableIds(true)
    }

    fun submitLines(lines: List<LyricRepository.LyricLine>) {
        items.clear()
        items.addAll(lines)
        hasTimedLines = lines.any { it.timeMs >= 0L }
        activeTimes = emptySet()
        playbackPositionMs = 0L
        notifyDataSetChanged()
    }

    fun setPrimaryColor(color: Int) {
        if (primaryColor == color) return
        primaryColor = color
        notifyDataSetChanged()
    }

    fun setAccentColor(color: Int) {
        if (accentColor == color) return
        accentColor = color
        notifyDataSetChanged()
    }

    fun setAlignment(newAlignment: LyricsSettings.Alignment) {
        if (alignment == newAlignment) return
        alignment = newAlignment
        notifyDataSetChanged()
    }

    fun setPlaybackPosition(positionMs: Long) {
        if (playbackPositionMs == positionMs) return
        playbackPositionMs = positionMs
        attachedTextViews.forEach { it.setPlaybackPosition(positionMs) }
    }

    /** 单行高亮的兼容入口。 */
    fun setActiveTime(timeMs: Long?) {
        val next = if (timeMs == null) emptySet() else setOf(timeMs)
        setActiveTimes(next)
    }

    /** 多行同时高亮：传入当前应处于激活态的所有行的 timeMs 集合。 */
    fun setActiveTimes(times: Set<Long>) {
        if (activeTimes == times) return
        val oldSet = activeTimes
        activeTimes = times.toSet()
        val changedTimes = (oldSet + activeTimes) - (oldSet intersect activeTimes)
        if (changedTimes.isEmpty()) return
        notifyPositionsForTimes(changedTimes)
    }

    fun itemAt(position: Int): LyricRepository.LyricLine? {
        return items.getOrNull(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val textView = KaraokeLyricTextView(parent.context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = true
            // 默认允许多行：长句会自动换行，占用几行就撑开几行；只有超过 MAX_LYRIC_LINES
            // 的极端长句才会在最后一行尾部省略，避免一行歌词"吃掉"半个屏幕。
            maxLines = MAX_LYRIC_LINES
            ellipsize = null
            setSingleLine(false)
            setLineSpacing(0f, 1.02f)
            setPadding(0, 0, 0, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // SIMPLE 更接近自然排版：只有实际放不下时才换行，避免 BALANCED 过早折行。
                breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            }
            typeface = AppFont.typeface(parent.context)
            paint.isFakeBoldText = true
            letterSpacing = 0.012f
        }
        return LyricViewHolder(textView)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        bindTextView(holder.textView, items[position], position)
    }

    override fun onViewAttachedToWindow(holder: LyricViewHolder) {
        super.onViewAttachedToWindow(holder)
        attachedTextViews.add(holder.textView)
        holder.textView.setPlaybackPosition(playbackPositionMs)
    }

    override fun onViewDetachedFromWindow(holder: LyricViewHolder) {
        attachedTextViews.remove(holder.textView)
        super.onViewDetachedFromWindow(holder)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecycler = recyclerView
        updateContentWidth(recyclerView)
        recyclerView.addOnLayoutChangeListener(widthChangeListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnLayoutChangeListener(widthChangeListener)
        if (attachedRecycler === recyclerView) attachedRecycler = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private val widthChangeListener = android.view.View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        val rv = v as? RecyclerView ?: return@OnLayoutChangeListener
        val before = recyclerContentWidthPx
        updateContentWidth(rv)
        if (before != recyclerContentWidthPx && items.isNotEmpty()) {
            notifyDataSetChanged()
        }
    }

    private fun updateContentWidth(rv: RecyclerView) {
        recyclerContentWidthPx = (rv.width - rv.paddingLeft - rv.paddingRight).coerceAtLeast(0)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long {
        val line = items.getOrNull(position) ?: return RecyclerView.NO_ID
        return line.timeMs * 1_000_003L + line.sourceIndex.toLong() * 101L + line.text.hashCode().toLong()
    }

    private fun bindTextView(textView: KaraokeLyricTextView, line: LyricRepository.LyricLine, position: Int) {
        val active = line.timeMs >= 0L && activeTimes.contains(line.timeMs)
        val hasWordTiming = active && line.words.isNotEmpty()
        textView.text = line.text
        textView.gravity = when (alignment) {
            LyricsSettings.Alignment.CENTER -> Gravity.CENTER or Gravity.CENTER_VERTICAL
            LyricsSettings.Alignment.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
        }
        val textSizeSp = when {
            line.isContinuationInGroup -> CONTINUATION_TEXT_SIZE_SP
            active -> ACTIVE_TEXT_SIZE_SP
            else -> NORMAL_TEXT_SIZE_SP
        }
        textView.textSize = textSizeSp
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lineHeightSp = when {
                line.isContinuationInGroup -> CONTINUATION_SLOT_LINE_HEIGHT_SP
                else -> PRIMARY_SLOT_LINE_HEIGHT_SP
            }
            val lineHeightPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                lineHeightSp,
                textView.resources.displayMetrics
            ).toInt()
            textView.lineHeight = lineHeightPx
        }
        // 高亮和非高亮歌词都允许更多行。短句不会被撑开，长句会自动换到第 3、4 行，
        // 直到 MAX_LYRIC_LINES 为止；再长才会在末尾 "..."。
        textView.minLines = 1
        textView.maxLines = MAX_LYRIC_LINES
        textView.ellipsize = null

        val textAlpha = when {
            active && line.isContinuationInGroup -> 214
            active -> 255
            line.isContinuationInGroup -> 62
            else -> 92
        }
        // 高亮行：文字和填色都用封面采出的 accent（更亮/更饱和），而不是偏深的 primary。
        val activeBaseColor = if (accentColor != 0) accentColor else primaryColor
        val normalTextColor = if (active) {
            ColorUtils.setAlphaComponent(activeBaseColor, textAlpha)
        } else {
            ColorUtils.setAlphaComponent(primaryColor, textAlpha)
        }
        val karaokeUnfilledAlpha = if (line.isContinuationInGroup) 108 else 132
        val unfilled = ColorUtils.setAlphaComponent(primaryColor, karaokeUnfilledAlpha)
        textView.setTextColor(if (hasWordTiming) unfilled else normalTextColor)
        textView.bindKaraokeLine(
            line = line,
            active = hasWordTiming,
            currentPositionMs = playbackPositionMs,
            fillColor = activeBaseColor,
            unfilledColor = unfilled
        )
        textView.setOnClickListener {
            if (line.timeMs >= 0L) onLineClick(line)
        }
        textView.isClickable = line.timeMs >= 0L
        textView.alpha = if (line.timeMs >= 0L || !hasTimedLines) 1f else 0.58f

        val lp = (textView.layoutParams as? ViewGroup.MarginLayoutParams)
            ?: ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        lp.topMargin = when {
            position == 0 -> 0
            line.isContinuationInGroup -> dp(textView, 6)
            else -> dp(textView, 30)
        }
        // 不额外压缩右侧可用宽度，避免歌词过早换行。
        lp.marginEnd = 0
        lp.bottomMargin = 0
        textView.layoutParams = lp
    }

    private fun notifyPositionsForTimes(times: Set<Long>) {
        if (times.isEmpty()) return
        items.forEachIndexed { index, line ->
            if (line.timeMs in times) notifyItemChanged(index)
        }
    }

    private fun dp(view: KaraokeLyricTextView, value: Int): Int {
        return (value * view.resources.displayMetrics.density).toInt()
    }

    class LyricViewHolder(val textView: KaraokeLyricTextView) : RecyclerView.ViewHolder(textView)
}
