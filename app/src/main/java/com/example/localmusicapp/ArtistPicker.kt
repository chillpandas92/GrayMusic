package com.example.localmusicapp

import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.imageview.ShapeableImageView

/**
 * 歌手选择器：
 * - 单歌手 → 直接跳转到 SongListActivity 的歌手详情页
 * - 多歌手 → 弹出底部小抽屉，显示圆形封面 + 歌手名，点击跳转
 *
 * 调用方：PlayerActivity（点击歌手名） / SongActionSheet（点击歌手行）
 */
object ArtistPicker {

    /**
     * @param activity  当前 Activity（用于弹 Dialog）
     * @param rawArtist 原始歌手字段（可能含 / & feat. 等分隔符）
     * @param onNavigate 可选回调，仅在需要页面跳转时才执行（当前实现直接弹 sheet，不跳转）
     */
    fun pick(
        activity: AppCompatActivity,
        rawArtist: String,
        onNavigate: (() -> Unit)? = null
    ) {
        val artists = ArtistUtils.splitArtists(rawArtist)
        if (artists.size <= 1) {
            // 单歌手：直接弹出歌手详情抽屉，不跳转页面
            val name = artists.firstOrNull() ?: "未知艺术家"
            if (!ArtistDetailSheet.show(activity, name)) {
                android.widget.Toast.makeText(activity, "未找到歌手「$name」", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        showPickerSheet(activity, artists, onNavigate)
    }

    /** 跳转到 SongListActivity 并打开目标歌手的详情页 */
    fun navigateToArtist(activity: AppCompatActivity, artistName: String) {
        if (ArtistDetailSheet.show(activity, artistName)) return
        val intent = Intent(activity, SongListActivity::class.java).apply {
            putExtra(SongListActivity.EXTRA_OPEN_ARTIST, artistName)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        activity.startActivity(intent)
    }

    /** 跳转到 SongListActivity 并打开目标专辑的详情页 */
    fun navigateToAlbum(
        activity: AppCompatActivity,
        albumTitle: String,
        albumArtist: String = ""
    ) {
        if (ArtistDetailSheet.showAlbum(activity, albumTitle, albumArtist)) return
        val intent = Intent(activity, SongListActivity::class.java).apply {
            putExtra(SongListActivity.EXTRA_OPEN_ALBUM, albumTitle)
            putExtra(SongListActivity.EXTRA_OPEN_ALBUM_ARTIST, albumArtist)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        activity.startActivity(intent)
    }

    // ------------------------------------------------------------------
    // 多歌手选择抽屉
    // ------------------------------------------------------------------

    private fun showPickerSheet(
        activity: AppCompatActivity,
        artists: List<String>,
        onNavigate: (() -> Unit)?
    ) {
        val dialog = BottomSheetDialog(activity)
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.sheet_rounded_bg)
            setPadding(0, dp(8), 0, dp(18))
        }

        // 拖拽手柄
        val handle = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(2)
                bottomMargin = dp(14)
            }
            setBackgroundResource(R.drawable.sheet_handle_bg)
        }
        root.addView(handle)

        // 标题
        val title = TextView(activity).apply {
            text = "选择歌手"
            textSize = 16f
            setTextColor(0xFF000000.toInt())
            setPadding(dp(20), 0, dp(20), dp(10))
        }
        AppFont.applyTo(title)
        root.addView(title)

        // 分隔线
        root.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply {
                bottomMargin = dp(4)
            }
            setBackgroundColor(0xFFE6E6E6.toInt())
        })

        // 查找每个歌手的封面路径
        val allFiles = ScanResultHolder.result?.files.orEmpty()

        for (artistName in artists) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(60)
                )
                setPadding(dp(20), 0, dp(20), 0)
                isClickable = true
                isFocusable = true
                background = ContextCompat.getDrawable(
                    activity, R.drawable.song_item_touch_bg
                )
            }

            // 圆形歌手封面
            val cover = ShapeableImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFFF2F2F2.toInt())
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes((dp(42) / 2).toFloat())
                    .build()
                setImageResource(R.drawable.music_note_24)
                contentDescription = "artist cover"
            }
            // 找到该歌手的第一首歌来加载封面
            val coverPath = findCoverForArtist(allFiles, artistName)
            if (coverPath != null) {
                CoverLoader.load(cover, coverPath, R.drawable.music_note_24)
            }
            row.addView(cover)

            // 歌手名
            val nameView = TextView(activity).apply {
                text = artistName
                textSize = 15f
                setTextColor(0xFF111111.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(14)
                }
            }
            AppFont.applyTo(nameView)
            row.addView(nameView)

            row.setOnClickListener {
                dialog.dismiss()
                // 直接弹出歌手详情抽屉，不跳转页面
                if (!ArtistDetailSheet.show(activity, artistName)) {
                    android.widget.Toast.makeText(
                        activity, "未找到歌手「$artistName」", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }

            root.addView(row)
        }

        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.show()
    }

    /** 在歌曲库里找到该歌手名对应的第一首歌的路径（用于加载封面） */
    private fun findCoverForArtist(
        files: List<MusicScanner.MusicFile>,
        artistName: String
    ): String? {
        val key = SortKeyHelper.keyOf(artistName).ifBlank { artistName.trim().lowercase() }
        return files.firstOrNull { file ->
            val source = file.artist.ifBlank { file.albumArtist }.ifBlank { "未知艺术家" }
            ArtistUtils.splitArtists(source).any { name ->
                val k = SortKeyHelper.keyOf(name).ifBlank { name.trim().lowercase() }
                k == key
            }
        }?.path
    }
}
