package com.example.localmusicapp

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SearchActivity : AppCompatActivity() {

    private enum class SortMethod { TITLE, ARTIST_ALBUM }
    private enum class SortOrder { ASC, DESC }

    private lateinit var request: SearchSessionHolder.Request
    private lateinit var etSearch: EditText
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: SearchResultAdapter
    private lateinit var btnSearchMulti: ImageButton
    private lateinit var btnSearchSort: ImageButton
    private lateinit var multiActionBar: View
    private lateinit var btnMultiMore: View
    private lateinit var btnMultiAll: View
    private lateinit var btnMultiShare: View

    private var sortMethod: SortMethod = SortMethod.TITLE
    private var sortOrder: SortOrder = SortOrder.ASC
    private var multiSelectMode: Boolean = false
    private val selectedPaths = LinkedHashSet<String>()
    private var baseItems: List<SearchSessionHolder.Item> = emptyList()
    private var displayedItems: List<SearchSessionHolder.Item> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        setContentView(R.layout.activity_search)
        AppFont.applyTo(findViewById(android.R.id.content))

        val req = SearchSessionHolder.request
        if (req == null) {
            finish()
            overridePendingTransition(R.anim.stay, R.anim.slide_out_right)
            return
        }
        request = req
        baseItems = req.items

        val root = findViewById<View>(R.id.searchRoot)
        val baseTop = root.paddingTop
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.updatePadding(top = baseTop + bars.top, bottom = baseBottom + bars.bottom)
            insets
        }

        etSearch = findViewById(R.id.etSearch)
        rv = findViewById(R.id.rvSearch)
        tvEmpty = findViewById(R.id.tvSearchEmpty)
        btnSearchMulti = findViewById(R.id.btnSearchMulti)
        btnSearchSort = findViewById(R.id.btnSearchSort)
        multiActionBar = findViewById(R.id.searchMultiActionBar)
        btnMultiMore = findViewById(R.id.btnSearchMultiMore)
        btnMultiAll = findViewById(R.id.btnSearchMultiAll)
        btnMultiShare = findViewById(R.id.btnSearchMultiShare)

        findViewById<TextView>(R.id.tvSearchScopeHint).text =
            "搜索${request.sourceName}内的${request.targetLabel}"
        findViewById<ImageButton>(R.id.btnSearchCancel).setOnClickListener { finishWithAnim() }

        val canMultiSelect = canMultiSelectCurrentRequest()
        btnSearchMulti.visibility = if (canMultiSelect) View.VISIBLE else View.GONE
        btnSearchMulti.setOnClickListener {
            if (multiSelectMode) exitMultiSelectMode() else enterMultiSelectMode()
        }
        btnSearchSort.setOnClickListener { showSortSheet() }
        btnMultiMore.setOnClickListener { showMultiSelectMoreSheet() }
        btnMultiAll.setOnClickListener { selectAllCurrentResults() }
        btnMultiShare.setOnClickListener { shareSelectedSongs() }

        adapter = SearchResultAdapter(
            presentation = request.presentation,
            onItemClick = { item ->
                val committedQuery = etSearch.text?.toString()?.trim().orEmpty()
                SearchSessionHolder.lastSearchQuery = committedQuery
                SearchSessionHolder.lastSearchResultPaths =
                    if (request.presentation == SearchSessionHolder.Presentation.SONG) displayedItems.map { it.path } else emptyList()
                setResult(
                    Activity.RESULT_OK,
                    Intent().apply {
                        putExtra(EXTRA_SCOPE, request.scope)
                        putExtra(EXTRA_PATH, item.path)
                        putExtra(EXTRA_INDEX, item.index)
                        putExtra(EXTRA_QUERY, committedQuery)
                    }
                )
                finishWithAnim()
            },
            onItemLongClick = { item ->
                val file = ScanResultHolder.result?.files?.firstOrNull { it.path == item.path }
                if (file != null) SongActionSheet.show(this, file)
            },
            onSelectionChanged = { paths ->
                selectedPaths.clear()
                selectedPaths.addAll(paths)
                updateMultiActionBarText()
            }
        )
        rv.layoutManager = if (request.presentation == SearchSessionHolder.Presentation.ALBUM) {
            GridLayoutManager(this, 2)
        } else {
            LinearLayoutManager(this)
        }
        rv.adapter = adapter
        rv.setHasFixedSize(true)

        etSearch.hint = "搜索${request.targetLabel}"
        tvEmpty.text = "请输入关键词搜索${request.targetLabel}"
        etSearch.doAfterTextChanged { editable ->
            updateResults(editable?.toString().orEmpty())
        }
        updateResults("")

        etSearch.post {
            etSearch.requestFocus()
            getSystemService<InputMethodManager>()?.showSoftInput(
                etSearch,
                InputMethodManager.SHOW_IMPLICIT
            )
        }
    }

    override fun onBackPressed() {
        if (multiSelectMode) exitMultiSelectMode() else finishWithAnim()
    }

    private fun updateResults(rawQuery: String) {
        val query = rawQuery.trim()
        val queryLower = normalizeSearchText(query)
        val matched = if (queryLower.isBlank()) {
            emptyList()
        } else {
            baseItems.filter { matchesSearchQuery(it, queryLower) }
        }
        val items = sortItems(matched)
        tvEmpty.text = if (query.isBlank()) {
            "请输入关键词搜索${request.targetLabel}"
        } else {
            "没有找到相关${request.targetLabel}"
        }
        displayedItems = items
        adapter.submit(items, query)
        if (multiSelectMode) {
            selectedPaths.retainAll(items.map { it.path }.toSet())
            adapter.setSelectedPaths(selectedPaths)
            updateMultiActionBarText()
        }
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rv.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun sortItems(items: List<SearchSessionHolder.Item>): List<SearchSessionHolder.Item> {
        val comparator = compareBy<SearchSessionHolder.Item> {
            when (sortMethod) {
                SortMethod.TITLE -> SortKeyHelper.keyOf(it.title)
                SortMethod.ARTIST_ALBUM -> SortKeyHelper.keyOf("${it.subtitle} ${it.title}")
            }
        }.thenBy { it.index }
        val sorted = items.sortedWith(comparator)
        return if (sortOrder == SortOrder.ASC) sorted else sorted.asReversed()
    }

    private fun matchScore(
        item: SearchSessionHolder.Item,
        queryLower: String
    ): Int {
        if (queryLower.isBlank()) return 0

        val title = normalizeSearchText(item.title)
        val subtitle = normalizeSearchText(item.subtitle)
        val trailing = normalizeSearchText(item.trailing)
        val titleKey = SortKeyHelper.searchKeyOf(item.title).lowercase()
        val subtitleKey = SortKeyHelper.searchKeyOf(item.subtitle).lowercase()
        val queryKey = SortKeyHelper.searchKeyOf(queryLower).lowercase()

        return when {
            title == queryLower -> 1000
            title.startsWith(queryLower) -> 900
            title.contains(queryLower) -> 800
            titleKey.startsWith(queryKey) -> 760
            titleKey.contains(queryKey) -> 740
            subtitle == queryLower -> 700
            subtitle.startsWith(queryLower) -> 650
            subtitle.contains(queryLower) -> 600
            subtitleKey.startsWith(queryKey) -> 580
            subtitleKey.contains(queryKey) -> 560
            trailing == queryLower -> 550
            trailing.startsWith(queryLower) -> 500
            trailing.contains(queryLower) -> 450
            else -> -1
        }
    }

    private fun matchesSearchQuery(
        item: SearchSessionHolder.Item,
        queryLower: String
    ): Boolean {
        if (queryLower.isBlank()) return false
        // 搜索结果必须能在列表文字里产生蓝色高亮；不再用拼音 key、拆词或宽泛匹配。
        return listOf(item.title, item.subtitle, item.trailing)
            .any { normalizeSearchText(it).contains(queryLower) }
    }

    private fun normalizeSearchText(text: String): String {
        return text.trim().replace(Regex("\\s+"), " ").lowercase()
    }

    private fun canMultiSelectCurrentRequest(): Boolean {
        if (request.presentation != SearchSessionHolder.Presentation.SONG) return false
        return request.scope in setOf(
            SearchSessionHolder.Scope.LIBRARY,
            SearchSessionHolder.Scope.LEADERBOARD,
            SearchSessionHolder.Scope.FAVORITES,
            SearchSessionHolder.Scope.FOLDER,
            SearchSessionHolder.Scope.PLAYLIST
        )
    }

    private fun enterMultiSelectMode() {
        if (!canMultiSelectCurrentRequest() || multiSelectMode) return
        multiSelectMode = true
        selectedPaths.clear()
        adapter.setMultiSelectMode(true)
        adapter.setSelectedPaths(emptySet())
        btnSearchMulti.setImageResource(R.drawable.ic_close_24)
        btnSearchMulti.contentDescription = "退出多选"
        rv.updatePadding(bottom = dp(92))
        multiActionBar.visibility = View.VISIBLE
        updateMultiActionBarText()
    }

    private fun exitMultiSelectMode() {
        if (!multiSelectMode) return
        multiSelectMode = false
        selectedPaths.clear()
        adapter.setMultiSelectMode(false)
        adapter.setSelectedPaths(emptySet())
        btnSearchMulti.setImageResource(R.drawable.ic_multiselect_24)
        btnSearchMulti.contentDescription = "多选"
        multiActionBar.visibility = View.GONE
        rv.updatePadding(bottom = dp(24))
    }

    private fun updateMultiActionBarText() {
        // 搜索页多选底栏保持和主页面一致：底栏只显示固定的“更多 / 全选 / 分享”三项，
        // 选中数量由每一行右侧圆形勾选状态表达，不额外改写底栏文字。
    }

    private fun selectAllCurrentResults() {
        if (!multiSelectMode) return
        val paths = displayedItems.map { it.path }
        if (paths.isEmpty()) {
            Toast.makeText(this, "当前列表为空", Toast.LENGTH_SHORT).show()
            return
        }
        val next = if (selectedPaths.containsAll(paths)) emptySet() else paths.toSet()
        selectedPaths.clear()
        selectedPaths.addAll(next)
        adapter.setSelectedPaths(selectedPaths)
        updateMultiActionBarText()
    }

    private fun showSortSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_sort, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }

        view.findViewById<View>(R.id.rowMethodImportDate).visibility = View.GONE
        view.findViewById<View>(R.id.rowMethodPlayCount).visibility = View.GONE
        val checkTitle = view.findViewById<ImageView>(R.id.checkMethodTitle)
        val checkArtistAlbum = view.findViewById<ImageView>(R.id.checkMethodArtistAlbum)
        val checkAsc = view.findViewById<ImageView>(R.id.checkOrderAsc)
        val checkDesc = view.findViewById<ImageView>(R.id.checkOrderDesc)

        fun refreshChecks() {
            checkTitle.visibility = if (sortMethod == SortMethod.TITLE) View.VISIBLE else View.INVISIBLE
            checkArtistAlbum.visibility = if (sortMethod == SortMethod.ARTIST_ALBUM) View.VISIBLE else View.INVISIBLE
            checkAsc.visibility = if (sortOrder == SortOrder.ASC) View.VISIBLE else View.INVISIBLE
            checkDesc.visibility = if (sortOrder == SortOrder.DESC) View.VISIBLE else View.INVISIBLE
        }
        fun applySort() {
            updateResults(etSearch.text?.toString().orEmpty())
            refreshChecks()
        }
        refreshChecks()
        view.findViewById<View>(R.id.rowMethodTitle).setOnClickListener {
            sortMethod = SortMethod.TITLE
            applySort()
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.rowMethodArtistAlbum).setOnClickListener {
            sortMethod = SortMethod.ARTIST_ALBUM
            applySort()
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.rowOrderAsc).setOnClickListener {
            sortOrder = SortOrder.ASC
            applySort()
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.rowOrderDesc).setOnClickListener {
            sortOrder = SortOrder.DESC
            applySort()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showMultiSelectMoreSheet() {
        val files = selectedMusicFiles()
        if (files.isEmpty()) {
            Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.sheet_rounded_bg)
            setPadding(dp(20), dp(8), dp(20), dp(18))
        }
        root.addView(View(this).apply {
            setBackgroundResource(R.drawable.sheet_handle_bg)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(2)
                bottomMargin = dp(12)
            }
        })
        root.addView(makeActionRow(R.drawable.add_circle_24, "添加到歌单", 0xFF000000.toInt()) {
            dialog.dismiss()
            showAddSelectedToPlaylist(files)
        })
        root.addView(makeActionRow(R.drawable.ic_delete_24, "删除", 0xFFD32F2F.toInt()) {
            dialog.dismiss()
            confirmDeleteSongs(files)
        })
        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.show()
    }

    private fun makeActionRow(
        iconRes: Int,
        label: String,
        textColor: Int,
        onClick: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.song_item_touch_bg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
            )
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            })
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(16)
                }
            })
            setOnClickListener { onClick() }
        }
    }

    private fun selectedMusicFiles(): List<MusicScanner.MusicFile> {
        if (selectedPaths.isEmpty()) return emptyList()
        val byPath = (ScanResultHolder.result?.files ?: emptyList()).associateBy { it.path }
        return selectedPaths.mapNotNull { byPath[it] }
    }

    private fun showAddSelectedToPlaylist(files: List<MusicScanner.MusicFile>) {
        if (files.isEmpty()) {
            Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
            return
        }
        val lists = PlaylistStore.all(this)
        if (lists.isEmpty()) {
            showCreatePlaylistThenAdd(files)
            return
        }
        val names = lists.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("添加到歌单")
            .setItems(names) { _, which ->
                val target = lists[which]
                var added = 0
                for (file in files) {
                    val existed = PlaylistStore.containsSong(this, target.id, file.path)
                    PlaylistStore.addSong(this, target.id, file.path)
                    if (!existed) added++
                }
                Toast.makeText(this, "已添加 ${added} 首歌曲到「${target.name}」", Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCreatePlaylistThenAdd(files: List<MusicScanner.MusicFile>) {
        val view = layoutInflater.inflate(R.layout.dialog_new_playlist, null)
        AppFont.applyTo(view)
        view.findViewById<TextView>(R.id.tvPlaylistDialogTitle).text = "新建歌单并添加"
        val input = view.findViewById<EditText>(R.id.etPlaylistName)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        fun commit() {
            val name = input.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                Toast.makeText(this, "请输入歌单名称", Toast.LENGTH_SHORT).show()
                return
            }
            val playlist = PlaylistStore.create(this, name)
            files.forEach { PlaylistStore.addSong(this, playlist.id, it.path) }
            Toast.makeText(this, "已创建「${playlist.name}」并添加 ${files.size} 首歌曲", Toast.LENGTH_SHORT).show()
            exitMultiSelectMode()
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.btnPlaylistDialogCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnPlaylistDialogConfirm).setOnClickListener { commit() }
        dialog.setOnShowListener { input.requestFocus() }
        dialog.show()
    }

    private fun shareSelectedSongs() {
        val paths = selectedPaths.toList()
        if (paths.isEmpty()) {
            Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
            return
        }
        val files = paths.map { File(it) }.filter { it.exists() && it.isFile }
        if (files.isEmpty()) {
            Toast.makeText(this, "选中的文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        if (files.size == 1) shareSingleFile(files.first()) else shareMultipleFiles(files)
    }

    private fun shareSingleFile(file: File) {
        val uri = contentUriFor(file) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.nameWithoutExtension)
            clipData = ClipData.newUri(contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, "分享歌曲"))
            exitMultiSelectMode()
        }.onFailure {
            Toast.makeText(this, "未找到可分享的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareMultipleFiles(files: List<File>) {
        Toast.makeText(this, "正在打包 ${files.size} 首歌…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val zipFile = withContext(Dispatchers.IO) {
                runCatching { buildZipInCache(files) }.getOrNull()
            }
            if (zipFile == null || !zipFile.exists()) {
                Toast.makeText(this@SearchActivity, "打包失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = contentUriFor(zipFile) ?: return@launch
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, zipFile.name)
                clipData = ClipData.newUri(contentResolver, zipFile.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                startActivity(Intent.createChooser(intent, "分享歌曲合集"))
                exitMultiSelectMode()
            }.onFailure {
                Toast.makeText(this@SearchActivity, "未找到可分享的应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildZipInCache(files: List<File>): File {
        val shareDir = File(cacheDir, "shared").apply { mkdirs() }
        shareDir.listFiles { f -> f.name.startsWith("songs_") && f.name.endsWith(".zip") }
            ?.forEach { runCatching { it.delete() } }
        val zipFile = File(shareDir, "songs_${System.currentTimeMillis()}.zip")
        val usedNames = HashSet<String>()
        java.util.zip.ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            for (file in files) {
                val base = file.name
                var entryName = base
                var n = 1
                while (!usedNames.add(entryName)) {
                    entryName = "${file.nameWithoutExtension} ($n).${file.extension}"
                    n++
                }
                zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                file.inputStream().buffered().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipFile
    }

    private fun confirmDeleteSongs(files: List<MusicScanner.MusicFile>) {
        val existing = files.distinctBy { it.path }.filter { File(it.path).exists() }
        if (existing.isEmpty()) {
            Toast.makeText(this, "歌曲文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val count = existing.size
        AlertDialog.Builder(this)
            .setTitle("删除歌曲")
            .setMessage("确定删除${count}首歌曲？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val deletedPaths = withContext(Dispatchers.IO) {
                        existing.filter { deleteAudioFileBestEffort(it.path) }.map { it.path }.toSet()
                    }
                    if (deletedPaths.isNotEmpty()) {
                        removeDeletedSongsFromStores(deletedPaths)
                        refreshSearchAfterDelete(deletedPaths)
                        PlaybackManager.currentPath()?.let {
                            if (it in deletedPaths) PlaybackManager.stop(this@SearchActivity)
                        }
                    }
                    Toast.makeText(
                        this@SearchActivity,
                        if (deletedPaths.size == count) "已删除${deletedPaths.size}首歌曲" else "已删除${deletedPaths.size}/${count}首歌曲",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (multiSelectMode) exitMultiSelectMode()
                }
            }
            .show()
    }

    private fun deleteAudioFileBestEffort(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return true
        if (runCatching { file.delete() }.getOrDefault(false)) return true
        return runCatching {
            val uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            contentResolver.delete(uri, android.provider.MediaStore.Audio.Media.DATA + "=?", arrayOf(path)) > 0
        }.getOrDefault(false)
    }

    private fun removeDeletedSongsFromStores(paths: Set<String>) {
        for (path in paths) FavoritesStore.setFavorite(this, path, false)
        for (playlist in PlaylistStore.all(this)) {
            for (path in paths) PlaylistStore.removeSong(this, playlist.id, path)
        }
    }

    private fun refreshSearchAfterDelete(deletedPaths: Set<String>) {
        val current = ScanResultHolder.result?.files.orEmpty()
        if (current.isNotEmpty()) {
            val remaining = current.filterNot { it.path in deletedPaths }
            val newResult = MusicScanner.ScanResult(
                files = remaining,
                formatCounts = remaining.groupingBy { it.format }.eachCount()
            )
            ScanResultHolder.result = newResult
            ScanCache.save(this, newResult)
        }
        baseItems = baseItems.filterNot { it.path in deletedPaths }
        request = request.copy(items = baseItems)
        SearchSessionHolder.request = request
        selectedPaths.removeAll(deletedPaths)
        updateResults(etSearch.text?.toString().orEmpty())
    }

    private fun contentUriFor(file: File): Uri? {
        return runCatching {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        }.getOrElse {
            Toast.makeText(this, "无法分享该文件", Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun mimeFor(file: File): String {
        return when (file.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "alac" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "audio/*"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun finishWithAnim() {
        finish()
        overridePendingTransition(R.anim.stay, R.anim.slide_out_right)
    }

    companion object {
        const val EXTRA_SCOPE = "scope"
        const val EXTRA_PATH = "path"
        const val EXTRA_INDEX = "index"
        const val EXTRA_QUERY = "query"
    }
}
