package com.example.localmusicapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 扫描页。UI 形态跟系统设置里的「媒体来源」弹窗同款——
 *   · 半透明遮罩之上一张白色圆角卡片
 *   · 标题「扫描音乐」+ 右上角转圈
 *   · 一列"正在被逐首扫描/已扫描"的文件名，一边跑一边追加
 *   · 底部「确定」按钮；扫描中灰色禁用，扫描完变成蓝色可点，
 *     点了才进主页
 *
 * 性能关键点（之前出现过"全部扫完才一次性刷出来"的症状）：
 *   - 扫描跑在 IO dispatcher 上
 *   - 每首歌 push 一次 UI 更新时，用 withContext(Dispatchers.Main) 从 IO 切回 Main，
 *     刷完再切回 IO。这个一来一回天然形成一个 lockstep：
 *     IO 不会一口气把 100 个 runnable 塞爆主线程队列，主线程每刷一帧才接一首，
 *     用户就能看到文件名一条一条流进列表
 *   - 列表区域 (scanScroll) 的高度在 onCreate 里算出一个固定上限
 *     （屏幕高度的 52%），不再动态跟着内容往上长、也不再监听 layout 变化，
 *     这样每次 addView 只会触发一次局部布局，不会触发卡片整体重 measure
 *
 * 多文件夹扫描：
 *   - 如果 Intent 里带了 EXTRA_EXTRA_FOLDER_URIS，先扫所有手动添加的文件夹，
 *     再扫系统 Music 目录，最后合并去重。
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var scanCard: LinearLayout
    private lateinit var scanList: LinearLayout
    private lateinit var scanScroll: ScrollView
    private lateinit var scanSpinner: ProgressBar
    private lateinit var btnConfirm: TextView

    private var scanJob: Job? = null
    private var scanFinished: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_scan)
        AppFont.applyTo(findViewById(android.R.id.content))

        val root = findViewById<View>(R.id.root)
        applyEdgeToEdgeInsets(root)

        scanCard = findViewById(R.id.scanCard)
        scanList = findViewById(R.id.scanList)
        scanScroll = findViewById(R.id.scanScroll)
        scanSpinner = findViewById(R.id.scanSpinner)
        btnConfirm = findViewById(R.id.btnScanConfirm)

        // 给滚动区钉一个固定的最大高度：屏幕高度的 52%。
        // 有了固定值，随便扫多少首歌，卡片都不会再继续往上长——
        // 列表内部自己滚就好，外层不需要再做动态 layout 调整
        val screenH = resources.displayMetrics.heightPixels
        val maxScrollH = (screenH * 0.52f).toInt()
        scanScroll.post {
            val lp = scanScroll.layoutParams
            lp.height = maxScrollH
            scanScroll.layoutParams = lp
        }

        btnConfirm.setOnClickListener {
            if (!scanFinished) return@setOnClickListener
            goToMainList()
        }

        startScan()
    }

    /** 边到边 padding，保证卡片不会被状态栏/导航栏挡住 */
    private fun applyEdgeToEdgeInsets(root: View) {
        val initialTop = root.paddingTop
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                top = initialTop + bars.top,
                bottom = initialBottom + bars.bottom
            )
            insets
        }
    }

    private fun startScan() {
        val extraUris = intent
            ?.getStringArrayListExtra(MainActivity.EXTRA_EXTRA_FOLDER_URIS)
            ?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            .orEmpty()

        scanJob?.cancel()
        // 整个协程起在 IO 上；每次要刷 UI 时 withContext 切到 Main
        scanJob = lifecycleScope.launch(Dispatchers.IO) {
            // 用 LinkedHashMap 保留顺序：手动文件夹先入列，再扫系统目录覆盖；
            // 同 path 重复时以最新条目为准（其实差异不大，重要的是不会有重复行）。
            val merged = linkedMapOf<String, MusicScanner.MusicFile>()

            for (uri in extraUris) {
                val tree = MusicScanner.scanDocumentTree(this@ScanActivity, uri) { progress ->
                    withContext(Dispatchers.Main) {
                        onProgress(progress)
                    }
                }
                tree.files.forEach { merged[it.path] = it }
            }

            val systemResult = MusicScanner.scanMusicFolder(this@ScanActivity) { progress ->
                withContext(Dispatchers.Main) {
                    onProgress(progress)
                }
            }
            systemResult.files.forEach { merged.putIfAbsent(it.path, it) }

            val files = merged.values.toList()
            val result = MusicScanner.ScanResult(
                files = files,
                formatCounts = files.groupingBy { it.format }.eachCount()
            )

            // 保存结果。SongListActivity 下次启动直接复用这份缓存
            ScanResultHolder.result = result
            ScanCache.save(this@ScanActivity, result)

            // 后台继续把封面预取到磁盘缓存（不阻塞用户点"确定"）
            CoverDiskCache.init(this@ScanActivity)
            launch(Dispatchers.IO) {
                // 扫描完成后只预热前 80 首封面，剩余封面按需懒加载，避免后台长时间高 I/O 和高耗电。
                for (f in result.files.take(80)) CoverDiskCache.prefetchAndCache(f.path)
            }

            withContext(Dispatchers.Main) {
                onScanComplete(result.files.size)
            }
        }
    }

    /**
     * 每一次 ScanProgress 到达 UI 都做一点事：
     *   - SCANNING 阶段：把新文件名追加进列表，顺手滚到底部
     *   - DISCOVERING / ENRICHING：目前什么都不做
     */
    private fun onProgress(progress: MusicScanner.ScanProgress) {
        if (progress.stage == MusicScanner.ProgressStage.SCANNING) {
            val name = progress.fileName
            if (!name.isNullOrBlank()) {
                appendFileRow(name)
            }
        }
    }

    private fun appendFileRow(name: String) {
        val tv = TextView(this).apply {
            text = name
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 行距跟截图里差不多
            val vgap = (resources.displayMetrics.density * 3f).toInt()
            setPadding(0, vgap, 0, vgap)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scanList.addView(tv)

        // 直接滚——我们现在就在 Main 线程上（withContext 切过来的），
        // 不需要再 post 一次。而且 fullScroll 如果 ScrollView 还没完成
        // measure 时不生效的问题，在加入 view 之后同一帧调用其实是 OK 的，
        // 因为这里每次只加一条不多，ScrollView 的 child height 变化是同步的
        if (!scanFinished) {
            scanScroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    /**
     * 扫描彻底完成：
     *   - 停掉转圈
     *   - 把"确定"按钮从禁用灰切换到启用蓝
     *   - 如果一首都没扫到，给一个提示行；用户还是可以点确定进主页（空主页）
     */
    private fun onScanComplete(totalFound: Int) {
        scanFinished = true
        scanSpinner.visibility = View.INVISIBLE

        if (totalFound == 0 && scanList.childCount == 0) {
            val empty = TextView(this).apply {
                text = "未找到歌曲。可以先允许音频权限，或进入主页后通过文件夹按钮添加歌曲。"
                textSize = 14f
                setTextColor(0xFF888888.toInt())
                gravity = Gravity.CENTER
                val vpad = (resources.displayMetrics.density * 24f).toInt()
                setPadding(0, vpad, 0, vpad)
            }
            scanList.addView(empty)
        }

        // 按钮切到可点的蓝色样式
        btnConfirm.apply {
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.scan_btn_enabled_bg)
            setTextColor(0xFFFFFFFF.toInt())
        }
    }

    private fun goToMainList() {
        startActivity(Intent(this, SongListActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        super.onDestroy()
    }

    /**
     * 返回键处理：扫描没完就无视返回键（否则我们会在 IO 里 half-way 被 kill）。
     * 扫描完之后，返回键等同于点"确定"，进主页。
     */
    override fun onBackPressed() {
        if (scanFinished) {
            goToMainList()
        }
        // 扫描进行中：吞掉返回事件，不 super 回去
    }
}
