package com.example.localmusicapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class MainActivity : AppCompatActivity() {

    private lateinit var btnAudio: Button
    private lateinit var btnNotification: Button
    private lateinit var btnPickFolder: Button
    private lateinit var btnDirectScan: Button
    private lateinit var pickedFoldersContainer: LinearLayout

    /** 用户在权限页选中的待扫描文件夹（持久化 URI 已 take）。 */
    private val pickedFolders = mutableListOf<PickedFolder>()

    private data class PickedFolder(
        val uri: Uri,
        val displayName: String
    )

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            updateAudioButton(true)
            Toast.makeText(this, "已允许扫描系统音频", Toast.LENGTH_SHORT).show()
        } else {
            updateAudioButton(false)
            Toast.makeText(this, "未授予音频权限，仍可进入后添加文件夹", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            updateNotificationButton(true)
            Toast.makeText(this, "已允许通知", Toast.LENGTH_SHORT).show()
        } else {
            updateNotificationButton(false)
            Toast.makeText(this, "未授予通知权限，不影响本地播放", Toast.LENGTH_SHORT).show()
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) handleFolderPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val cached = if (ScanCache.exists(this)) ScanCache.load(this) else null
        if (cached != null && cached.files.isNotEmpty()) {
            ScanResultHolder.result = cached
            startActivity(Intent(this, SongListActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        AppFont.applyTo(findViewById(android.R.id.content))
        applyEdgeToEdgeInsets(findViewById(R.id.root))

        btnAudio = findViewById(R.id.btnAudio)
        btnNotification = findViewById(R.id.btnNotification)
        btnPickFolder = findViewById(R.id.btnPickFolder)
        btnDirectScan = findViewById(R.id.btnDirectScan)
        pickedFoldersContainer = findViewById(R.id.pickedFoldersContainer)

        updateAudioButton(hasAudioPermission())
        updateNotificationButton(hasNotificationPermission())

        btnAudio.setOnClickListener { requestAudioPermission() }
        btnNotification.setOnClickListener { requestNotificationPermission() }
        btnPickFolder.setOnClickListener { launchFolderPicker() }
        btnDirectScan.setOnClickListener { openScanPage() }
    }

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

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestAudioPermission() {
        if (hasAudioPermission()) {
            updateAudioButton(true)
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        audioPermissionLauncher.launch(permission)
    }

    private fun requestNotificationPermission() {
        if (hasNotificationPermission()) {
            updateNotificationButton(true)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            updateNotificationButton(true)
        }
    }

    private fun updateAudioButton(granted: Boolean) {
        if (!::btnAudio.isInitialized) return
        btnAudio.text = if (granted) "✓ 已允许扫描系统音频" else "允许扫描系统音频（可选）"
        btnAudio.isEnabled = !granted
    }

    private fun updateNotificationButton(granted: Boolean) {
        if (!::btnNotification.isInitialized) return
        btnNotification.text = if (granted) "✓ 已允许通知" else "允许通知（可选）"
        btnNotification.isEnabled = !granted
    }

    private fun launchFolderPicker() {
        try {
            folderPickerLauncher.launch(null)
        } catch (_: Exception) {
            Toast.makeText(this, "当前设备不支持文件夹选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleFolderPicked(uri: Uri) {
        // 重复添加同一个 URI 直接忽略，避免列表里出现重复项。
        if (pickedFolders.any { it.uri == uri }) {
            Toast.makeText(this, "该文件夹已添加", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val displayName = resolveTreeDisplayName(uri)
        pickedFolders.add(PickedFolder(uri = uri, displayName = displayName))
        rebuildPickedFoldersUi()
        updatePickFolderButton()
    }

    /** 从 Tree URI 取一个能用的显示名（首选 DocumentsContract 查询，失败用 docId 解析）。 */
    private fun resolveTreeDisplayName(treeUri: Uri): String {
        val fromQuery = runCatching {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            contentResolver.query(rootDocUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.trim().orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("")
        if (fromQuery.isNotBlank()) return fromQuery

        val fromDocId = runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            // primary:Music/Sub → Sub；primary: → 内部存储
            val parts = docId.split(':', limit = 2)
            val tail = if (parts.size == 2) parts[1] else docId
            tail.substringAfterLast('/').ifBlank { tail }.ifBlank { "文件夹" }
        }.getOrDefault("文件夹")
        return fromDocId.ifBlank { "文件夹" }
    }

    private fun updatePickFolderButton() {
        if (!::btnPickFolder.isInitialized) return
        btnPickFolder.text = if (pickedFolders.isEmpty()) {
            "+ 添加文件夹（可选，可多选）"
        } else {
            "+ 继续添加文件夹（已添加 ${pickedFolders.size} 个）"
        }
    }

    private fun rebuildPickedFoldersUi() {
        if (!::pickedFoldersContainer.isInitialized) return
        pickedFoldersContainer.removeAllViews()
        if (pickedFolders.isEmpty()) {
            pickedFoldersContainer.visibility = View.GONE
            return
        }
        pickedFoldersContainer.visibility = View.VISIBLE
        for (folder in pickedFolders) {
            pickedFoldersContainer.addView(createPickedFolderRow(folder))
        }
    }

    private fun createPickedFolderRow(folder: PickedFolder): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(8), dp(10))
            setBackgroundResource(R.drawable.picked_folder_row_bg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_folder_24)
            setColorFilter(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginEnd = dp(10)
            }
        }
        row.addView(icon)

        val name = TextView(this).apply {
            text = folder.displayName
            textSize = 14f
            setTextColor(0xFF222222.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        row.addView(name)

        val remove = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close_24)
            setColorFilter(0xFF666666.toInt())
            background = null
            contentDescription = "移除文件夹"
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { removePickedFolder(folder) }
        }
        row.addView(remove)

        return row
    }

    private fun removePickedFolder(folder: PickedFolder) {
        if (!pickedFolders.remove(folder)) return
        // 释放掉刚刚 take 的持久权限，避免污染系统持久 URI 列表。
        runCatching {
            contentResolver.releasePersistableUriPermission(
                folder.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        rebuildPickedFoldersUi()
        updatePickFolderButton()
    }

    private fun openScanPage() {
        val intent = Intent(this, ScanActivity::class.java)
        if (pickedFolders.isNotEmpty()) {
            // 只把 URI 字符串传给扫描页，扫描页自己负责按顺序扫描每个文件夹并合并结果。
            // 这里 take 过的持久权限会跟着 URI 一起被扫描页继续使用。
            val uris = ArrayList(pickedFolders.map { it.uri.toString() })
            intent.putStringArrayListExtra(EXTRA_EXTRA_FOLDER_URIS, uris)
        }
        startActivity(intent)
        finish()
    }

    companion object {
        const val EXTRA_EXTRA_FOLDER_URIS = "extra_folder_uris"
    }
}
