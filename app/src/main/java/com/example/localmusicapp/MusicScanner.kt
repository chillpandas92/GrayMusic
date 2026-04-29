package com.example.localmusicapp

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 音乐扫描工具类
 *
 * 扫描策略：
 *   1. 优先用 MediaStore 快速查询 Music 文件夹内已被系统索引的音频
 *   2. 再用 File API 递归补扫，兜底那些还没被索引的文件
 *   3. 严格按扩展名过滤，只接受 MP3 / FLAC / M4A / OGG / WAV
 *   4. 结果默认按歌名拼音排序（TinyPinyin）
 *
 * 额外读取的元数据：
 *   - DATE_ADDED   -> 导入日期排序
 *   - TRACK        -> 专辑内音轨排序（该字段会把 disc/track 编到同一个整数里）
 *   - ALBUM_ARTIST -> 专辑统计 / 艺术家（专辑）排序时优先使用
 */
object MusicScanner {

    enum class ProgressStage {
        DISCOVERING,
        SCANNING,
        ENRICHING
    }

    data class ScanProgress(
        val stage: ProgressStage,
        val overallCurrent: Int,
        val overallTotal: Int,
        val stageCurrent: Int,
        val stageTotal: Int,
        // 刚刚被解析完的文件名（不带目录），仅在 SCANNING 阶段逐首推送；
        // 其他阶段以及空发现阶段为 null
        val fileName: String? = null
    )

    val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "m4a", "ogg", "wav")

    data class MusicFile(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val path: String,
        val duration: Long,
        val format: String,
        val size: Long,
        val dateAddedSec: Long = 0L,
        val albumArtist: String = "",
        val discNumber: Int = 0,
        val trackNumber: Int = 0,
        val year: Int = 0,
        val folderName: String = "",
        val folderPath: String = "",
        val externalLrcUri: String = ""
    ) {
        fun artistGroup(): String = ArtistUtils.primaryArtist(albumArtist.ifBlank { artist })
    }

    data class ScanResult(
        val files: List<MusicFile>,
        val formatCounts: Map<String, Int>
    )

    suspend fun scanMusicFolder(
        context: Context,
        onProgress: suspend (progress: ScanProgress) -> Unit
    ): ScanResult {
        val collected = linkedMapOf<String, MusicFile>()

        val musicDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MUSIC
        ).absolutePath

        // 发现阶段开始：先通知一下 UI，好让「扫描中」骨架能立起来
        onProgress(
            ScanProgress(
                stage = ProgressStage.DISCOVERING,
                overallCurrent = 0,
                overallTotal = 0,
                stageCurrent = 0,
                stageTotal = 0
            )
        )

        // MediaStore 查询 + 文件系统兜底。两者都是同步返回一整批结果，
        // 但在整个 scanMusicFolder 里都算"快"的一步，执行完就立刻进入逐首逐首 push 阶段
        val mediaStoreFiles = queryMediaStore(context, musicDir)

        val fileSystemFiles = try {
            scanFileSystem(File(musicDir))
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val totalEstimate = (mediaStoreFiles.size + fileSystemFiles.size)
            .coerceAtLeast(1)

        // ---- 空库兜底：一首都没发现时，拉一小段动画让用户看到"扫过了" ----
        if (mediaStoreFiles.isEmpty() && fileSystemFiles.isEmpty()) {
            val fake = 10
            for (i in 1..fake) {
                onProgress(
                    ScanProgress(
                        stage = ProgressStage.SCANNING,
                        overallCurrent = i,
                        overallTotal = fake,
                        stageCurrent = i,
                        stageTotal = fake
                    )
                )
                delay(80)
            }
            return ScanResult(emptyList(), emptyMap())
        }

        // ---- 主扫描阶段：每处理完一首，立刻把文件名推给 UI ----
        // 关键：onProgress 是 suspend 的。调用方在回调里做 withContext(Main) 切到 UI 线程
        // 刷新视图、再回到 IO；这一轮切换天然给了主线程喘气的机会，避免"全部排队到最后一次性刷出来"
        var index = 0

        for (music in mediaStoreFiles) {
            val ext = music.format.lowercase()
            if (ext !in SUPPORTED_EXTENSIONS) continue
            // 极快的文件存在性校验（大部分情况下 MediaStore 给的路径是有效的，不用打开流）
            val f = File(music.path)
            if (!f.exists() || f.length() == 0L) continue

            collected[music.path] = music
            index++
            onProgress(
                ScanProgress(
                    stage = ProgressStage.SCANNING,
                    overallCurrent = index,
                    overallTotal = totalEstimate,
                    stageCurrent = index,
                    stageTotal = totalEstimate,
                    fileName = f.name
                )
            )
        }

        // 把 MediaStore 漏掉的文件兜底补上（去重）
        for (file in fileSystemFiles) {
            val path = file.absolutePath
            if (path in collected) continue
            val ext = file.extension.lowercase()
            if (ext !in SUPPORTED_EXTENSIONS) continue
            if (!file.exists() || file.length() == 0L) continue

            val music = MusicFile(
                id = path.hashCode().toLong(),
                title = file.nameWithoutExtension,
                artist = "未知艺术家",
                album = "未知专辑",
                path = path,
                duration = 0L,
                format = ext,
                size = file.length(),
                dateAddedSec = (file.lastModified() / 1000L).coerceAtLeast(0L),
                albumArtist = "",
                discNumber = 0,
                trackNumber = 0,
                year = 0
            )
            collected[path] = music
            index++
            onProgress(
                ScanProgress(
                    stage = ProgressStage.SCANNING,
                    overallCurrent = index,
                    overallTotal = totalEstimate,
                    stageCurrent = index,
                    stageTotal = totalEstimate,
                    fileName = file.name
                )
            )
        }

        // NOTE: 过去这里还有一个 "阶段 3: 年份补读"，会用 MediaMetadataRetriever 对
        // 每个缺年份的专辑读一次代表文件。那一步是整个扫描里最慢的一段（每首 ~50-200ms 的
        // I/O），而年份只影响专辑页的副标题显示，缺了也不影响使用。
        // 为了"扫描要快 + 一首一首出"这两条硬性要求，直接把它跳过；年份缺失时 UI 已经
        // 会自动隐藏，不影响其他功能。
        val files = collected.values.sortedWith(
            compareBy<MusicFile> { SortKeyHelper.keyOf(it.title) }
                .thenBy { SortKeyHelper.keyOf(it.artist) }
                .thenBy { SortKeyHelper.keyOf(it.album) }
                .thenBy { it.trackNumber }
                .thenBy { it.path }
        )
        val formatCounts = files.groupingBy { it.format }.eachCount()
        return ScanResult(files, formatCounts)
    }


    suspend fun scanDocumentTree(
        context: Context,
        treeUri: Uri,
        onProgress: suspend (progress: ScanProgress) -> Unit
    ): ScanResult {
        val rootName = resolveDocumentTreeName(context, treeUri).ifBlank { "文件夹" }
        val rootKey = treeUri.toString()

        onProgress(
            ScanProgress(
                stage = ProgressStage.DISCOVERING,
                overallCurrent = 0,
                overallTotal = 0,
                stageCurrent = 0,
                stageTotal = 0
            )
        )

        // 先尝试把系统文件夹选择器返回的 primary:xxx 映射回真实文件路径。
        // 能直接读文件时用 File API + retriever，比 SAF 的逐个 URI 访问快很多；
        // 不能直接读时再回到 DocumentsContract/DocumentFile 路径，保证无文件权限也可用。
        val directRoot = resolveTreeUriToFile(treeUri)
        if (directRoot != null && directRoot.exists() && directRoot.isDirectory && directRoot.canRead()) {
            val directResult = scanSelectedFileTree(
                context = context,
                root = directRoot,
                rootName = rootName,
                rootKey = rootKey,
                onProgress = onProgress
            )
            if (directResult.files.isNotEmpty()) return directResult
        }

        val entries = collectDocumentMusicEntries(context, treeUri, onProgress)
        if (entries.isEmpty()) {
            return ScanResult(emptyList(), emptyMap())
        }

        // SAF 路径同样改并发：buildMusicFileFromDocument 内部也是一次 MMR，
        // setDataSource(context, uri) 在 SAF 上比 File 路径还慢一点。
        // 用同样的 Semaphore(4) 把每首歌的 MMR 并发跑，进度按完成顺序推送。
        val concurrency = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val semaphore = Semaphore(concurrency)
        val collected = ConcurrentHashMap<String, MusicFile>()
        val completed = AtomicInteger(0)
        val total = entries.size

        coroutineScope {
            entries.map { entry ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val music = buildMusicFileFromDocument(
                            context = context,
                            entry = entry,
                            rootName = rootName,
                            rootKey = rootKey
                        )
                        if (music != null) collected[entry.uri.toString()] = music
                        val current = completed.incrementAndGet()
                        onProgress(
                            ScanProgress(
                                stage = ProgressStage.SCANNING,
                                overallCurrent = current,
                                overallTotal = total,
                                stageCurrent = current,
                                stageTotal = total,
                                fileName = entry.name
                            )
                        )
                    }
                }
            }.awaitAll()
        }

        val files = collected.values.sortedWith(
            compareBy<MusicFile> { SortKeyHelper.keyOf(it.title) }
                .thenBy { SortKeyHelper.keyOf(it.artist) }
                .thenBy { it.path }
        )
        return ScanResult(files = files, formatCounts = files.groupingBy { it.format }.eachCount())
    }

    private data class DocumentMusicEntry(
        val uri: Uri,
        val name: String,
        val size: Long,
        val lastModified: Long,
        val lrcUri: String = ""
    )

    private data class FastDocumentChild(
        val documentId: String,
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val size: Long,
        val lastModified: Long
    ) {
        val isDirectory: Boolean
            get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private suspend fun scanSelectedFileTree(
        context: Context,
        root: File,
        rootName: String,
        rootKey: String,
        onProgress: suspend (progress: ScanProgress) -> Unit
    ): ScanResult {
        val audioFiles = try {
            scanFileSystem(root)
        } catch (_: Exception) {
            emptyList()
        }
        if (audioFiles.isEmpty()) return ScanResult(emptyList(), emptyMap())

        // 优化: 之前是 forEachIndexed 顺序调用 buildMusicFileFromPath，每首都要 MMR
        // (setDataSource + 8 次 extractMetadata + release，单首 ~50-200ms 的 I/O)。
        // 1000 首歌按 100ms 估，纯串行就要 100 秒。
        // 这里先批量查 MediaStore 拿已索引的元数据 (零 MMR 命中)，剩余的再丢给协程
        // 池并发跑 MMR (上限 4 个，避免同时打开太多硬件解码器导致设备卡顿)。
        return scanFilesParallel(
            audioFiles = audioFiles,
            rootName = rootName,
            rootKey = rootKey,
            onProgress = onProgress,
            context = context
        )
    }

    /**
     * 公用的"批量元数据补全"流程。
     *   - 先一次性查 MediaStore（DATA IN (...)）拿已索引文件的元数据，命中即免 MMR
     *   - 剩下的丢给 Semaphore 限流的协程池并发跑 MMR
     *   - 进度按"完成顺序"逐首推送，UI 端依然能看到一条一条流进列表
     */
    private suspend fun scanFilesParallel(
        audioFiles: List<File>,
        rootName: String,
        rootKey: String,
        onProgress: suspend (progress: ScanProgress) -> Unit,
        context: Context? = null
    ): ScanResult {
        val total = audioFiles.size
        val mediaStoreCache: Map<String, MusicFile> = if (context != null) {
            queryMediaStoreByPaths(context, audioFiles.map { it.absolutePath })
        } else emptyMap()

        // 并发上限：CPU 核数减半，clamp 到 [2, 4]。MediaMetadataRetriever 内部调用硬件
        // 解码器，太多并发反而会互相抢资源。4 个对一般中端机已经接近极限。
        val concurrency = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val semaphore = Semaphore(concurrency)
        val collected = ConcurrentHashMap<String, MusicFile>()
        val completed = AtomicInteger(0)

        coroutineScope {
            audioFiles.map { file ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val fromCache = mediaStoreCache[file.absolutePath]
                        // 即使 MediaStore 命中，folderName/folderPath 仍以本次扫描传入的为准——
                        // 同一首歌可能既在系统 Music 也在用户手动添的子文件夹里，要按当前入口归属。
                        val music = fromCache?.copy(folderName = rootName, folderPath = rootKey)
                            ?: buildMusicFileFromPath(file, rootName, rootKey)
                        if (music != null) collected[file.absolutePath] = music
                        val current = completed.incrementAndGet()
                        // onProgress 是 suspend，内部会 withContext(Main)。多个 worker 并发回调
                        // 时主线程会自然串行化（每个 dequeue 一个执行），不会出现刷新冲突。
                        onProgress(
                            ScanProgress(
                                stage = ProgressStage.SCANNING,
                                overallCurrent = current,
                                overallTotal = total,
                                stageCurrent = current,
                                stageTotal = total,
                                fileName = file.name
                            )
                        )
                    }
                }
            }.awaitAll()
        }

        // 还按文件遍历顺序输出（audioFiles 来自 scanFileSystem 的 BFS），方便用户在
        // 进度条文本里大致预估剩余进度；最终再统一按拼音/标题排序
        val files = collected.values.sortedWith(
            compareBy<MusicFile> { SortKeyHelper.keyOf(it.title) }
                .thenBy { SortKeyHelper.keyOf(it.artist) }
                .thenBy { it.path }
        )
        return ScanResult(
            files = files,
            formatCounts = files.groupingBy { it.format }.eachCount()
        )
    }

    /**
     * 用一组绝对路径批量查 MediaStore，返回 path -> MusicFile 的映射。
     *
     * 这一步之所以划得来：单次 SQL 用 IN (?,?,?,...) 一次性拿 N 行，比对每首歌跑
     * MediaMetadataRetriever 快好几个数量级。命中率高时（用户音乐都在公共目录），
     * 整批扫描里的 MMR 次数可能被压到只剩零头。
     *
     * 注意 IN 子句的参数上限 (Android SQLite 默认 999)，这里按 500 一批切一下。
     */
    private fun queryMediaStoreByPaths(
        context: Context,
        paths: List<String>
    ): Map<String, MusicFile> {
        if (paths.isEmpty()) return emptyMap()
        val out = HashMap<String, MusicFile>(paths.size)
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.AudioColumns.TRACK)
            add(MediaStore.Audio.AudioColumns.YEAR)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.ALBUM_ARTIST)
            }
        }.toTypedArray()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        try {
            paths.chunked(500).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val selection = "${MediaStore.Audio.Media.DATA} IN ($placeholders)"
                val args = chunk.toTypedArray()
                context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                    val titleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    val artistIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    val albumIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                    val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    val durIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                    val trackIdx = cursor.getColumnIndex(MediaStore.Audio.AudioColumns.TRACK)
                    val yearIdx = cursor.getColumnIndex(MediaStore.Audio.AudioColumns.YEAR)
                    val dateIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    val albumArtistIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        cursor.getColumnIndex(MediaStore.MediaColumns.ALBUM_ARTIST) else -1

                    while (cursor.moveToNext()) {
                        val data = if (dataIdx >= 0) cursor.getString(dataIdx).orEmpty() else continue
                        if (data.isBlank()) continue
                        val ext = data.substringAfterLast('.', "").lowercase()
                        if (ext !in SUPPORTED_EXTENSIONS) continue
                        val rawTrack = if (trackIdx >= 0) cursor.getInt(trackIdx) else 0
                        out[data] = MusicFile(
                            id = if (idIdx >= 0) cursor.getLong(idIdx) else data.hashCode().toLong(),
                            title = fixEncoding(
                                if (titleIdx >= 0) cursor.getString(titleIdx)?.takeIf { it.isNotBlank() } ?: File(data).nameWithoutExtension
                                else File(data).nameWithoutExtension
                            ),
                            artist = fixEncoding(normalizeTag(if (artistIdx >= 0) cursor.getString(artistIdx) else null, "未知艺术家")),
                            album = fixEncoding(normalizeTag(if (albumIdx >= 0) cursor.getString(albumIdx) else null, "未知专辑")),
                            path = data,
                            duration = if (durIdx >= 0) cursor.getLong(durIdx).coerceAtLeast(0L) else 0L,
                            format = ext,
                            size = if (sizeIdx >= 0) cursor.getLong(sizeIdx).coerceAtLeast(0L) else 0L,
                            dateAddedSec = if (dateIdx >= 0) cursor.getLong(dateIdx).coerceAtLeast(0L) else 0L,
                            albumArtist = fixEncoding(
                                normalizeTag(
                                    if (albumArtistIdx >= 0) cursor.getString(albumArtistIdx) else null,
                                    ""
                                )
                            ),
                            discNumber = 0,
                            trackNumber = rawTrack.coerceAtLeast(0),
                            year = if (yearIdx >= 0) cursor.getInt(yearIdx) else 0
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // MediaStore 查询失败时退化为空映射，调用方会自动 fallback 到 MMR
        }
        return out
    }

    private fun resolveTreeUriToFile(treeUri: Uri): File? {
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parts = docId.split(':', limit = 2)
            if (parts.size != 2) return@runCatching null
            val volume = parts[0]
            val relative = parts[1]
            if (!volume.equals("primary", ignoreCase = true)) return@runCatching null
            if (relative.isBlank()) Environment.getExternalStorageDirectory() else File(Environment.getExternalStorageDirectory(), relative)
        }.getOrNull()
    }

    private fun resolveDocumentTreeName(context: Context, treeUri: Uri): String {
        return runCatching {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            context.contentResolver.query(rootDocUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.trim().orEmpty()
                } else {
                    ""
                }
            }.orEmpty()
        }.getOrDefault("").ifBlank {
            DocumentFile.fromTreeUri(context, treeUri)?.name?.trim().orEmpty()
        }.ifBlank { "文件夹" }
    }

    private suspend fun collectDocumentMusicEntries(
        context: Context,
        treeUri: Uri,
        onProgress: suspend (progress: ScanProgress) -> Unit
    ): List<DocumentMusicEntry> {
        val fast = runCatching {
            collectDocumentMusicEntriesFast(context, treeUri, onProgress)
        }.getOrDefault(emptyList())
        if (fast.isNotEmpty()) return fast
        return collectDocumentMusicEntriesWithDocumentFile(context, treeUri, onProgress)
    }

    private suspend fun collectDocumentMusicEntriesFast(
        context: Context,
        treeUri: Uri,
        onProgress: suspend (progress: ScanProgress) -> Unit
    ): List<DocumentMusicEntry> {
        val result = mutableListOf<DocumentMusicEntry>()
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val queue = ArrayDeque<String>()
        queue.add(rootDocId)

        while (queue.isNotEmpty()) {
            val parentId = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val children = queryFastDocumentChildren(context, treeUri, childrenUri)
            val lrcByBaseName = children.asSequence()
                .filter { !it.isDirectory }
                .mapNotNull { child ->
                    val ext = child.name.substringAfterLast('.', "").lowercase()
                    if (ext == "lrc") child.name.substringBeforeLast('.', child.name).lowercase() to child.uri.toString() else null
                }
                .toMap()

            for (child in children) {
                if (child.isDirectory) {
                    queue.add(child.documentId)
                    continue
                }
                val ext = child.name.substringAfterLast('.', "").lowercase()
                if (ext in SUPPORTED_EXTENSIONS && child.size != 0L) {
                    val baseName = child.name.substringBeforeLast('.', child.name).lowercase()
                    result.add(
                        DocumentMusicEntry(
                            uri = child.uri,
                            name = child.name,
                            size = child.size.coerceAtLeast(0L),
                            lastModified = child.lastModified.coerceAtLeast(0L),
                            lrcUri = lrcByBaseName[baseName].orEmpty()
                        )
                    )
                    onProgress(
                        ScanProgress(
                            stage = ProgressStage.DISCOVERING,
                            overallCurrent = result.size,
                            overallTotal = 0,
                            stageCurrent = result.size,
                            stageTotal = 0,
                            fileName = child.name
                        )
                    )
                }
            }
        }
        return result
    }

    private fun queryFastDocumentChildren(
        context: Context,
        treeUri: Uri,
        childrenUri: Uri
    ): List<FastDocumentChild> {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val result = mutableListOf<FastDocumentChild>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                if (idCol < 0) continue
                val documentId = cursor.getString(idCol).orEmpty()
                if (documentId.isBlank()) continue
                val name = if (nameCol >= 0 && !cursor.isNull(nameCol)) cursor.getString(nameCol).orEmpty() else ""
                if (name.isBlank()) continue
                val mime = if (mimeCol >= 0 && !cursor.isNull(mimeCol)) cursor.getString(mimeCol).orEmpty() else ""
                val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L
                val lastModified = if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) cursor.getLong(modifiedCol) else 0L
                result.add(
                    FastDocumentChild(
                        documentId = documentId,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                        name = name,
                        mimeType = mime,
                        size = size,
                        lastModified = lastModified
                    )
                )
            }
        }
        return result
    }

    private suspend fun collectDocumentMusicEntriesWithDocumentFile(
        context: Context,
        treeUri: Uri,
        onProgress: suspend (progress: ScanProgress) -> Unit
    ): List<DocumentMusicEntry> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val result = mutableListOf<DocumentMusicEntry>()

        suspend fun walk(dir: DocumentFile, depth: Int) {
            if (depth > 24) return
            val children = runCatching { dir.listFiles().toList() }.getOrDefault(emptyList())
            val lrcByBaseName = children.asSequence()
                .filter { it.isFile }
                .mapNotNull { child ->
                    val name = child.name.orEmpty()
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext == "lrc") name.substringBeforeLast('.', name).lowercase() to child.uri.toString() else null
                }
                .toMap()

            for (child in children) {
                when {
                    child.isDirectory -> walk(child, depth + 1)
                    child.isFile -> {
                        val name = child.name.orEmpty()
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val length = runCatching { child.length() }.getOrDefault(-1L)
                        if (ext in SUPPORTED_EXTENSIONS && length != 0L) {
                            val baseName = name.substringBeforeLast('.', name).lowercase()
                            result.add(
                                DocumentMusicEntry(
                                    uri = child.uri,
                                    name = name,
                                    size = length.coerceAtLeast(0L),
                                    lastModified = runCatching { child.lastModified() }.getOrDefault(0L).coerceAtLeast(0L),
                                    lrcUri = lrcByBaseName[baseName].orEmpty()
                                )
                            )
                            onProgress(
                                ScanProgress(
                                    stage = ProgressStage.DISCOVERING,
                                    overallCurrent = result.size,
                                    overallTotal = 0,
                                    stageCurrent = result.size,
                                    stageTotal = 0,
                                    fileName = name
                                )
                            )
                        }
                    }
                }
            }
        }

        walk(root, 0)
        return result
    }

    private fun buildMusicFileFromPath(
        file: File,
        rootName: String,
        rootKey: String
    ): MusicFile? {
        val name = file.name.trim()
        if (name.isBlank()) return null
        val ext = file.extension.lowercase()
        if (ext !in SUPPORTED_EXTENSIONS) return null
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
            val rawTrack = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).orEmpty()
            val trackNumber = parseTrackString(rawTrack)
            val rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.trim().orEmpty()
            val rawDate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.trim().orEmpty()
            val year = extractYear(rawYear) ?: extractYear(rawDate) ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L

            MusicFile(
                id = file.absolutePath.hashCode().toLong(),
                title = fixEncoding(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() }
                        ?: file.nameWithoutExtension
                ),
                artist = fixEncoding(
                    normalizeTag(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                        "未知艺术家"
                    )
                ),
                album = fixEncoding(
                    normalizeTag(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                        "未知专辑"
                    )
                ),
                path = file.absolutePath,
                duration = durationMs,
                format = ext,
                size = file.length().coerceAtLeast(0L),
                dateAddedSec = (file.lastModified() / 1000L).coerceAtLeast(0L),
                albumArtist = fixEncoding(
                    normalizeTag(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                        ""
                    )
                ),
                discNumber = 0,
                trackNumber = trackNumber,
                year = year,
                folderName = rootName,
                folderPath = rootKey,
                externalLrcUri = ""
            )
        } catch (_: Exception) {
            MusicFile(
                id = file.absolutePath.hashCode().toLong(),
                title = file.nameWithoutExtension,
                artist = "未知艺术家",
                album = "未知专辑",
                path = file.absolutePath,
                duration = 0L,
                format = ext,
                size = file.length().coerceAtLeast(0L),
                dateAddedSec = (file.lastModified() / 1000L).coerceAtLeast(0L),
                albumArtist = "",
                discNumber = 0,
                trackNumber = 0,
                year = 0,
                folderName = rootName,
                folderPath = rootKey,
                externalLrcUri = ""
            )
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    private fun buildMusicFileFromDocument(
        context: Context,
        entry: DocumentMusicEntry,
        rootName: String,
        rootKey: String
    ): MusicFile? {
        val name = entry.name.trim()
        if (name.isBlank()) return null
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in SUPPORTED_EXTENSIONS) return null
        val uri = entry.uri
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever().apply { setDataSource(context, uri) }
            val rawTrack = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).orEmpty()
            val trackNumber = parseTrackString(rawTrack)
            val rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.trim().orEmpty()
            val rawDate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.trim().orEmpty()
            val year = extractYear(rawYear) ?: extractYear(rawDate) ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L

            MusicFile(
                id = uri.toString().hashCode().toLong(),
                title = fixEncoding(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() }
                        ?: name.substringBeforeLast('.', name)
                ),
                artist = fixEncoding(
                    normalizeTag(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                        "未知艺术家"
                    )
                ),
                album = fixEncoding(
                    normalizeTag(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                        "未知专辑"
                    )
                ),
                path = uri.toString(),
                duration = durationMs,
                format = ext,
                size = entry.size.coerceAtLeast(0L),
                dateAddedSec = (entry.lastModified / 1000L).coerceAtLeast(0L),
                albumArtist = fixEncoding(
                    normalizeTag(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                        ""
                    )
                ),
                discNumber = 0,
                trackNumber = trackNumber,
                year = year,
                folderName = rootName,
                folderPath = rootKey,
                externalLrcUri = entry.lrcUri
            )
        } catch (_: Exception) {
            MusicFile(
                id = uri.toString().hashCode().toLong(),
                title = name.substringBeforeLast('.', name),
                artist = "未知艺术家",
                album = "未知专辑",
                path = uri.toString(),
                duration = 0L,
                format = ext,
                size = entry.size.coerceAtLeast(0L),
                dateAddedSec = (entry.lastModified / 1000L).coerceAtLeast(0L),
                albumArtist = "",
                discNumber = 0,
                trackNumber = 0,
                year = 0,
                folderName = rootName,
                folderPath = rootKey,
                externalLrcUri = entry.lrcUri
            )
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    private fun parseTrackString(raw: String): Int {
        if (raw.isBlank()) return 0
        val first = raw.substringBefore('/').substringBefore('-').trim()
        return first.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    private fun queryMediaStore(context: Context, musicDir: String): List<MusicFile> {
        val result = mutableListOf<MusicFile>()

        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.AudioColumns.TRACK)
            add(MediaStore.Audio.AudioColumns.YEAR)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.ALBUM_ARTIST)
            }
        }.toTypedArray()

        val selection = "${MediaStore.Audio.Media.DATA} LIKE ?"
        val selectionArgs = arrayOf("$musicDir%")

        val cursor = try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        } ?: return result

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val trackCol = c.getColumnIndex(MediaStore.Audio.AudioColumns.TRACK)
            val yearCol = c.getColumnIndex(MediaStore.Audio.AudioColumns.YEAR)
            val dateAddedCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val albumArtistCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                c.getColumnIndex(MediaStore.MediaColumns.ALBUM_ARTIST)
            } else {
                -1
            }

            while (c.moveToNext()) {
                val path = c.getString(dataCol) ?: continue
                val ext = path.substringAfterLast('.', "").lowercase()
                if (ext !in SUPPORTED_EXTENSIONS) continue

                val rawArtist = c.getString(artistCol)
                val rawAlbum = c.getString(albumCol)
                val rawAlbumArtist = if (albumArtistCol >= 0) c.getString(albumArtistCol) else null

                // MediaStore 的 SIZE 理论上应该有值，但个别情况下为 0；用文件系统兜底
                var sizeBytes = c.getLong(sizeCol)
                if (sizeBytes <= 0L) {
                    sizeBytes = try { File(path).length() } catch (e: Exception) { 0L }
                }

                val trackRaw = if (trackCol >= 0) c.getInt(trackCol) else 0
                val (discNumber, trackNumber) = parseTrackInfo(trackRaw)

                var dateAddedSec = if (dateAddedCol >= 0) c.getLong(dateAddedCol) else 0L
                if (dateAddedSec <= 0L) {
                    dateAddedSec = try { File(path).lastModified() / 1000L } catch (_: Exception) { 0L }
                }
                val year = if (yearCol >= 0) c.getInt(yearCol).takeIf { it in 1000..9999 } ?: 0 else 0

                result.add(
                    MusicFile(
                        id = c.getLong(idCol),
                        title = fixEncoding(c.getString(titleCol) ?: File(path).nameWithoutExtension),
                        artist = fixEncoding(normalizeTag(rawArtist, "未知艺术家")),
                        album = fixEncoding(normalizeTag(rawAlbum, "未知专辑")),
                        path = path,
                        duration = c.getLong(durationCol),
                        format = ext,
                        size = sizeBytes,
                        dateAddedSec = dateAddedSec.coerceAtLeast(0L),
                        albumArtist = fixEncoding(normalizeTag(rawAlbumArtist, "")),
                        discNumber = discNumber,
                        trackNumber = trackNumber,
                        year = year
                    )
                )
            }
        }

        return result
    }

    private fun countMissingAlbumYearGroups(files: List<MusicFile>): Int {
        if (files.isEmpty()) return 0
        return files.groupBy { albumGroupKey(it) }.values.count { songs ->
            songs.none { it.year in 1000..9999 }
        }
    }

    /** MediaStore TRACK: 1..999 为单碟音轨；1001/2001... 形式表示 disc + track */
    private fun parseTrackInfo(raw: Int): Pair<Int, Int> {
        if (raw <= 0) return 0 to 0
        return if (raw >= 1000) {
            val disc = raw / 1000
            val track = raw % 1000
            disc.coerceAtLeast(1) to track.coerceAtLeast(0)
        } else {
            1 to raw
        }
    }


    /**
     * 年份优先走 MediaStore；若某个专辑整组都缺失年份，再用 1 首代表歌曲的标签补一次。
     * 这样专辑页能显示年份，同时避免对每首歌都做 retriever，尽量保持扫描速度。
     *
     * @param onProgress (step, total) 回调——step 是已处理的"补读"次数（调 MediaMetadataRetriever 那种慢路径），
     *                   total 是需要补读的专辑数；两次 tick 之间是一次慢 I/O，够撑起进度条
     */
    private fun enrichMissingAlbumYears(
        files: List<MusicFile>,
        onProgress: (step: Int, total: Int) -> Unit = { _, _ -> }
    ): List<MusicFile> {
        if (files.isEmpty()) return files

        // 先分组并分出"已知年份"和"需要补读年份"的两类专辑
        val groups = files.groupBy { albumGroupKey(it) }.values
        data class Needy(val songs: List<MusicFile>)
        val ready = HashMap<String, Int>()          // 路径 -> 年份（MediaStore 已经有）
        val needy = mutableListOf<Needy>()

        for (songs in groups) {
            val existing = songs.map { it.year }.filter { it in 1000..9999 }
            val known = existing.minOrNull()
            if (known != null && known > 0) {
                songs.forEach { ready[it.path] = known }
            } else {
                needy.add(Needy(songs))
            }
        }

        // 对每个缺年份的专辑，只读该组的第一首代表文件，节省 I/O
        val enrichTotal = needy.size
        needy.forEachIndexed { idx, nd ->
            val year = nd.songs.asSequence()
                .map { readYearFromFile(it.path) }
                .firstOrNull { it in 1000..9999 } ?: 0
            if (year > 0) {
                nd.songs.forEach { ready[it.path] = year }
            }
            onProgress(idx + 1, enrichTotal)
        }

        if (ready.isEmpty()) return files
        return files.map { file ->
            val year = ready[file.path] ?: file.year
            if (year == file.year) file else file.copy(year = year)
        }
    }

    private fun albumGroupKey(file: MusicFile): String {
        val album = file.album.trim().ifBlank { "未知专辑" }
        val artist = file.artistGroup().trim().ifBlank { "未知艺术家" }
        return album + "\u0001" + artist
    }

    private fun readYearFromFile(path: String): Int {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            // MP3 → TYER 走 METADATA_KEY_YEAR，FLAC/M4A/OGG → date/TDRC 走 METADATA_KEY_DATE
            val rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.trim().orEmpty()
            val rawDate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                ?.trim().orEmpty()
            try {
                retriever.release()
            } catch (_: Exception) {
            }
            // 两者都试——只要前 4 位是 1000..9999 的整数就接受。
            // rawDate 格式多变：2024-05-02、20240502、2024 都要能解析
            extractYear(rawYear) ?: extractYear(rawDate) ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun extractYear(raw: String): Int? {
        if (raw.isEmpty()) return null
        // 找出字符串中第一段连续 4 位数字（处理 "2024" "2024-05" "05/2024" 等）
        var i = 0
        while (i <= raw.length - 4) {
            val slice = raw.substring(i, i + 4)
            if (slice.all { it.isDigit() }) {
                val n = slice.toIntOrNull()
                if (n != null && n in 1000..9999) return n
            }
            i++
        }
        return null
    }

    /** MediaStore 对缺失字段会返回 "<unknown>" 字面量，统一处理掉 */
    private fun normalizeTag(value: String?, fallback: String): String {
        if (value.isNullOrBlank()) return fallback
        if (value.equals("<unknown>", ignoreCase = true)) return fallback
        return value
    }

    /**
     * MediaStore 在 ID3 标签没明确指定编码时会按 Latin-1 解读，CJK 歌会变成
     * "ã ã®é ï½" 这种 mojibake。检测并通过"Latin-1 字节 → UTF-8 解码"还原。
     *
     * 只对 "所有字符都在 U+0000..U+00FF 范围" 的字符串处理——这是 Latin-1
     * 解码结果的唯一特征；已经是正确 CJK（U+4E00+）的字符串不会被误改。
     */
    internal fun fixEncoding(s: String): String {
        if (s.isEmpty()) return s
        // 已有真正的高位 Unicode（如中文/日文）→ 说明解码是正确的，不动
        if (s.any { it.code > 0xFF }) return s
        // 纯 ASCII → 不可能 mojibake
        if (s.all { it.code < 0x80 }) return s

        return try {
            val bytes = s.toByteArray(Charsets.ISO_8859_1)
            val decoded = String(bytes, Charsets.UTF_8)
            // 如果有替换字符（\uFFFD），说明不是有效的 UTF-8 字节序列，保持原样
            if (decoded.contains('\uFFFD')) s else decoded
        } catch (_: Exception) {
            s
        }
    }

    private fun scanFileSystem(root: File): List<File> {
        if (!root.exists() || !root.isDirectory) return emptyList()

        val result = mutableListOf<File>()
        val queue = ArrayDeque<File>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val children = try { dir.listFiles() } catch (e: Exception) { null } ?: continue

            for (child in children) {
                if (child.isDirectory) {
                    queue.add(child)
                } else if (child.isFile) {
                    val ext = child.extension.lowercase()
                    if (ext in SUPPORTED_EXTENSIONS && child.length() > 0) {
                        result.add(child)
                    }
                }
            }
        }
        return result
    }
}
