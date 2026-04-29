package com.example.localmusicapp

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bottom sheet for scanning and managing ReplayGain values. */
object ReplayGainScanSheet {

    fun show(
        activity: AppCompatActivity,
        files: List<MusicScanner.MusicFile>,
        onStatsChanged: () -> Unit
    ) {
        val dialog = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 10), dp(activity, 20), dp(activity, 20))
            background = ContextCompat.getDrawable(activity, R.drawable.sheet_rounded_bg)
        }

        val handle = View(activity).apply {
            background = ContextCompat.getDrawable(activity, R.drawable.sheet_handle_bg)
            val lp = LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 4))
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = dp(activity, 14)
            layoutParams = lp
        }
        root.addView(handle)

        val title = TextView(activity).apply {
            text = "ReplayGain 回放增益"
            textSize = 24f
            setTextColor(Color.BLACK)
        }
        root.addView(title)

        val description = TextView(activity).apply {
            text = "扫描歌曲已有的 ReplayGain 标签（replaygain_track_gain / replaygain_album_gain / R128_GAIN 等）并写入本机缓存；没有标签的歌曲可点“补全无RG”，用本机音频分析生成回放增益缓存，让这些歌曲也能参与 ReplayGain 播放。"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setLineSpacing(dp(activity, 2).toFloat(), 1f)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(activity, 8)
            layoutParams = lp
        }
        root.addView(description)

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.stat_card_bg)
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(activity, 14)
            layoutParams = lp
        }
        root.addView(card)

        val statsText = TextView(activity).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setLineSpacing(dp(activity, 3).toFloat(), 1f)
        }
        card.addView(statsText)

        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 6))
            lp.topMargin = dp(activity, 12)
            layoutParams = lp
        }
        card.addView(progress)

        val scanStatus = TextView(activity).apply {
            text = "等待扫描"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(activity, 10)
            layoutParams = lp
        }
        card.addView(scanStatus)

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(activity, 14)
            layoutParams = lp
        }
        root.addView(buttonRow)

        fun makeButton(text: String, primary: Boolean): TextView = TextView(activity).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(if (primary) Color.WHITE else Color.parseColor("#0D47A1"))
            background = ContextCompat.getDrawable(activity, if (primary) R.drawable.ringtone_primary_btn_bg else R.drawable.tab_bg_on)
            isClickable = true
            isFocusable = true
            setPadding(dp(activity, 10), dp(activity, 9), dp(activity, 10), dp(activity, 9))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = dp(activity, 8)
            layoutParams = lp
        }

        val btnScan = makeButton("扫描", primary = true)
        val btnClear = makeButton("清空", primary = false)
        val btnSave = makeButton("保存缓存", primary = false).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        }
        buttonRow.addView(btnScan)
        buttonRow.addView(btnClear)
        buttonRow.addView(btnSave)

        val buttonRow2 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(activity, 10)
            layoutParams = lp
        }
        root.addView(buttonRow2)

        val btnFillMissing = makeButton("补全无RG", primary = false).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        }
        buttonRow2.addView(btnFillMissing)

        // 扫描结果保留在内存里，扫描完成同时写入 ReplayGainStore，避免设置页状态和真实标签脱节。
        var pendingEntries: List<ReplayGainStore.Entry> = emptyList()

        fun setButtonsEnabled(enabled: Boolean) {
            btnScan.isEnabled = enabled
            btnClear.isEnabled = enabled
            btnSave.isEnabled = enabled
            btnFillMissing.isEnabled = enabled
        }

        // 扫描中或没有数据时保持次按钮配色。
        fun setSaveAccented(accented: Boolean) {
            btnSave.setTextColor(if (accented) Color.WHITE else Color.parseColor("#0D47A1"))
            btnSave.background = ContextCompat.getDrawable(
                activity,
                if (accented) R.drawable.ringtone_primary_btn_bg else R.drawable.tab_bg_on
            )
        }

        fun renderStats() {
            val stats = ReplayGainStore.stats(activity, files)
            statsText.text = "专辑：${stats.albumCount} 个\n歌曲：${stats.songCount} 首\n已有 ReplayGain：${stats.withReplayGain} 首\n没有 ReplayGain：${stats.withoutReplayGain} 首"
        }
        renderStats()

        btnScan.setOnClickListener {
            if (files.isEmpty()) {
                Toast.makeText(activity, "当前曲库没有可扫描的歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 重新扫描时丢掉上一次未保存的结果，避免叠加。
            pendingEntries = emptyList()
            setSaveAccented(false)
            setButtonsEnabled(false)
            progress.visibility = View.VISIBLE
            progress.progress = 0
            scanStatus.text = "正在读取 ReplayGain 标签…"

            activity.lifecycleScope.launch {
                try {
                    val scanned: List<ReplayGainStore.Entry> = withContext(Dispatchers.IO) {
                        val out = ArrayList<ReplayGainStore.Entry>()
                        files.forEachIndexed { index, file ->
                            withContext(Dispatchers.Main) {
                                progress.progress = (((index + 1).toFloat() / files.size.toFloat()) * 100f).toInt().coerceIn(0, 100)
                                scanStatus.text = "读取标签：${index + 1}/${files.size}"
                            }
                            val entry = runCatching { ReplayGainReader.read(file) }.getOrNull()
                            if (entry != null) out.add(entry)
                        }
                        withContext(Dispatchers.Main) {
                            progress.progress = 100
                        }
                        out
                    }
                    pendingEntries = scanned
                    if (scanned.isNotEmpty()) {
                        ReplayGainStore.saveEntries(activity, scanned)
                        renderStats()
                        onStatsChanged()
                        PlaybackManager.refreshReplayGain()
                    } else {
                        renderStats()
                    }
                    scanStatus.text = if (scanned.isEmpty()) {
                        "扫描完成，当前歌曲没有可读取的 ReplayGain 标签。可点“补全无RG”生成本机回放增益缓存。"
                    } else {
                        "扫描完成，已扫描全部 ${files.size} 首歌曲，并读取缓存 ${scanned.size} 首歌曲的 ReplayGain 标签。"
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    renderStats()
                    scanStatus.text = "扫描中断：${t.message ?: "未知错误"}"
                    Toast.makeText(activity, "ReplayGain 扫描失败，已阻止崩溃", Toast.LENGTH_SHORT).show()
                } finally {
                    setButtonsEnabled(true)
                    setSaveAccented(false)
                }
            }
        }

        btnFillMissing.setOnClickListener {
            if (files.isEmpty()) {
                Toast.makeText(activity, "当前曲库没有可补全的歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            pendingEntries = emptyList()
            setSaveAccented(false)
            setButtonsEnabled(false)
            progress.visibility = View.VISIBLE
            progress.progress = 0
            scanStatus.text = "正在检查没有 ReplayGain 的歌曲…"

            activity.lifecycleScope.launch {
                try {
                    val missing = withContext(Dispatchers.IO) {
                        ReplayGainStore.filesWithoutReplayGain(activity, files)
                    }
                    if (missing.isEmpty()) {
                        renderStats()
                        Toast.makeText(activity, "当前所有歌曲已有 ReplayGain 缓存", Toast.LENGTH_SHORT).show()
                        scanStatus.text = "当前所有歌曲已有 ReplayGain 缓存。"
                        progress.progress = 100
                        return@launch
                    }

                    scanStatus.text = "正在补全没有 ReplayGain 的歌曲…"

                    val generated: List<ReplayGainStore.Entry> = withContext(Dispatchers.IO) {
                        val out = ArrayList<ReplayGainStore.Entry>()
                        missing.forEachIndexed { index, file ->
                            withContext(Dispatchers.Main) {
                                progress.progress = (((index + 1).toFloat() / missing.size.toFloat()) * 100f).toInt().coerceIn(0, 100)
                                scanStatus.text = "补全无RG：${index + 1}/${missing.size}"
                            }
                            val entry = runCatching { ReplayGainReader.read(file) }.getOrNull()
                                ?: runCatching { ReplayGainAnalyzer.analyze(file) }.getOrNull()
                            if (entry != null) out.add(entry)
                        }
                        withContext(Dispatchers.Main) {
                            progress.progress = 100
                        }
                        out
                    }

                    pendingEntries = generated
                    if (generated.isNotEmpty()) {
                        ReplayGainStore.saveEntries(activity, generated)
                        renderStats()
                        onStatsChanged()
                        PlaybackManager.refreshReplayGain()
                    } else {
                        renderStats()
                    }
                    val failed = (missing.size - generated.size).coerceAtLeast(0)
                    scanStatus.text = when {
                        generated.isEmpty() -> "补全完成，但没有生成可用的 ReplayGain 值。"
                        failed == 0 -> "补全完成，已为 ${generated.size} 首歌曲写入本机 ReplayGain 缓存。"
                        else -> "补全完成，已为 ${generated.size} 首歌曲写入本机 ReplayGain 缓存；${failed} 首未成功。"
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    renderStats()
                    scanStatus.text = "补全中断：${t.message ?: "未知错误"}"
                    Toast.makeText(activity, "补全无RG失败，已阻止崩溃", Toast.LENGTH_SHORT).show()
                } finally {
                    setButtonsEnabled(true)
                    setSaveAccented(false)
                }
            }
        }

        btnClear.setOnClickListener {
            ReplayGainStore.clear(activity)
            // 同时丢弃刚扫到、还没保存的结果——避免用户清空后又点保存把刚清掉的数据写回去。
            pendingEntries = emptyList()
            renderStats()
            onStatsChanged()
            progress.visibility = View.GONE
            scanStatus.text = "已清空本机 ReplayGain 值。"
            setSaveAccented(false)
        }

        btnSave.setOnClickListener {
            val toSave = pendingEntries
            if (toSave.isEmpty()) {
                Toast.makeText(activity, "没有可保存的扫描结果，请先点扫描或补全无RG", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 真正落盘的地方。saveEntries 是 merge by path，多次调用安全。
            ReplayGainStore.saveEntries(activity, toSave)
            val savedCount = toSave.size
            pendingEntries = emptyList()
            renderStats()
            onStatsChanged()
            scanStatus.text = "已重新保存 $savedCount 首歌曲的 ReplayGain 缓存。"
            Toast.makeText(activity, "已重新保存 $savedCount 首歌曲的 ReplayGain 值", Toast.LENGTH_SHORT).show()
            setSaveAccented(false)
        }

        val scroll = ScrollView(activity).apply {
            addView(root)
        }
        AppFont.applyTo(scroll)
        dialog.setContentView(scroll)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.show()
    }

    private fun dp(activity: AppCompatActivity, v: Int): Int {
        return (v * activity.resources.displayMetrics.density + 0.5f).toInt()
    }
}
