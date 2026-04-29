package com.example.localmusicapp

import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File

object SongActionSheet {
    fun show(
        activity: AppCompatActivity,
        file: MusicScanner.MusicFile,
        inPlaylistId: String? = null
    ) {
        val dialog = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_song_actions, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }

        val albumName = file.album.ifBlank { "未知专辑" }
        val shareButton = view.findViewById<ImageButton>(R.id.btnSongActionShare)
        val favoriteButton = view.findViewById<ImageButton>(R.id.btnSongActionFavorite)
        view.findViewById<TextView>(R.id.tvSongActionTitle).text = file.title

        // 歌手：可点击跳转（单歌手直接跳，多歌手弹选择抽屉）
        val artistView = view.findViewById<TextView>(R.id.tvSongActionArtist)
        artistView.text = ArtistUtils.displayArtists(file.artist)
        artistView.setTextColor(0xFF1565C0.toInt())
        artistView.setOnClickListener {
            val rawArtist = file.artist.ifBlank { file.albumArtist }
            if (rawArtist.isBlank()) return@setOnClickListener
            dialog.dismiss()
            ArtistPicker.pick(activity, rawArtist)
        }

        // 专辑：可点击跳转到专辑详情页
        val albumView = view.findViewById<TextView>(R.id.tvSongActionAlbum)
        albumView.text = albumName
        val qualityBadgeView = view.findViewById<TextView>(R.id.tvSongActionQualityBadge)
        val qualityBadge = if (PlaybackSettings.isQualityBadgeEnabled(activity)) AudioQualityClassifier.classify(file).badge else null
        if (qualityBadge.isNullOrBlank()) {
            qualityBadgeView.visibility = View.GONE
            qualityBadgeView.text = ""
        } else {
            qualityBadgeView.visibility = View.VISIBLE
            qualityBadgeView.text = qualityBadge
        }
        albumView.setTextColor(0xFF1565C0.toInt())
        albumView.setOnClickListener {
            if (file.album.isBlank()) return@setOnClickListener
            dialog.dismiss()
            val openedInline = (activity as? SongListActivity)?.showAlbumSheetForSong(file) == true
            if (!openedInline) {
                ArtistPicker.navigateToAlbum(
                    activity,
                    file.album.trim().ifBlank { "未知专辑" },
                    file.albumArtist.trim()
                )
            }
        }
        CoverLoader.load(
            view.findViewById<ImageView>(R.id.ivSongActionCover),
            file.path,
            R.drawable.music_note_24
        )

        fun syncFavoriteButton() {
            val favored = FavoritesStore.isFavorite(activity, file.path)
            favoriteButton.setImageResource(
                if (favored) R.drawable.ic_favorite_filled_custom
                else R.drawable.ic_favorite_black_custom
            )
            favoriteButton.clearColorFilter()
            favoriteButton.alpha = 1f
        }

        shareButton.setOnClickListener {
            shareSong(activity, file)
            dialog.dismiss()
        }

        syncFavoriteButton()
        favoriteButton.setOnClickListener {
            val favored = FavoritesStore.toggle(activity, file.path)
            syncFavoriteButton()
            Toast.makeText(
                activity,
                if (favored) "已添加到收藏夹" else "已取消收藏",
                Toast.LENGTH_SHORT
            ).show()
        }

        view.findViewById<View>(R.id.rowSongActionPlayNext).setOnClickListener {
            val wasCurrent = PlaybackManager.currentPath() == file.path
            PlaybackManager.playNext(activity, file)
            Toast.makeText(
                activity,
                if (wasCurrent) "这首歌正在播放" else "已添加到下一首播放",
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.rowSongActionAppendAndPlay).setOnClickListener {
            val wasCurrent = PlaybackManager.currentPath() == file.path
            PlaybackManager.appendNextAndPlay(activity, file)
            Toast.makeText(
                activity,
                if (wasCurrent) "这首歌正在播放" else "已插播并开始播放",
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
        }



        view.findViewById<View>(R.id.rowSongActionAddToPlaylist).setOnClickListener {
            showAddToPlaylistPicker(activity, file)
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.rowSongActionSetRingtone).setOnClickListener {
            dialog.dismiss()
            RingtoneEditorSheet.show(activity, file)
        }

        view.findViewById<View>(R.id.rowSongActionDelete).setOnClickListener {
            dialog.dismiss()
            val host = activity as? SongListActivity
            if (host != null) {
                host.confirmDeleteSongs(listOf(file))
            } else {
                confirmDeleteFallback(activity, file)
            }
        }

        // 只有从歌单详情页进来才显示"从歌单移除"
        val removeRow = view.findViewById<View>(R.id.rowSongActionRemoveFromPlaylist)
        if (!inPlaylistId.isNullOrBlank()) {
            val playlist = PlaylistStore.get(activity, inPlaylistId)
            if (playlist != null) {
                removeRow.visibility = View.VISIBLE
                view.findViewById<TextView>(R.id.tvSongActionRemoveFromPlaylist).text =
                    "从「${playlist.name}」移除"
                removeRow.setOnClickListener {
                    PlaylistStore.removeSong(activity, inPlaylistId, file.path)
                    Toast.makeText(activity, "已从「${playlist.name}」移除", Toast.LENGTH_SHORT).show()
                    (activity as? SongListActivity)?.notifyPlaylistsChanged()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    // 展示"选择要添加到的歌单"列表。没有歌单时提示去新建。
    private fun showAddToPlaylistPicker(activity: AppCompatActivity, file: MusicScanner.MusicFile) {
        val lists = PlaylistStore.all(activity)
        if (lists.isEmpty()) {
            showCreatePlaylistThenAdd(activity, file)
            return
        }
        val names = lists.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("添加到歌单")
            .setItems(names) { _, which ->
                val target = lists[which]
                if (PlaylistStore.containsSong(activity, target.id, file.path)) {
                    Toast.makeText(activity, "这首歌已在「${target.name}」里", Toast.LENGTH_SHORT).show()
                } else {
                    PlaylistStore.addSong(activity, target.id, file.path)
                    Toast.makeText(activity, "已添加到「${target.name}」", Toast.LENGTH_SHORT).show()
                    // 如果当前正在歌单页，主动刷新一下封面/计数
                    (activity as? SongListActivity)?.notifyPlaylistsChanged()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCreatePlaylistThenAdd(activity: AppCompatActivity, file: MusicScanner.MusicFile) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_new_playlist, null)
        AppFont.applyTo(view)
        view.findViewById<TextView>(R.id.tvPlaylistDialogTitle).text = "新建歌单并添加"
        val input = view.findViewById<android.widget.EditText>(R.id.etPlaylistName)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setView(view)
            .create()

        fun commit() {
            val name = input.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                Toast.makeText(activity, "请输入歌单名称", Toast.LENGTH_SHORT).show()
                return
            }
            val playlist = PlaylistStore.create(activity, name)
            PlaylistStore.addSong(activity, playlist.id, file.path)
            (activity as? SongListActivity)?.notifyPlaylistsChanged()
            Toast.makeText(activity, "已创建「${playlist.name}」并添加歌曲", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.btnPlaylistDialogCancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btnPlaylistDialogConfirm).setOnClickListener {
            commit()
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                commit()
                true
            } else {
                false
            }
        }
        dialog.setOnShowListener {
            input.requestFocus()
            val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }

    private fun shareSong(activity: AppCompatActivity, file: MusicScanner.MusicFile) {
        val audioFile = File(file.path)
        if (!audioFile.exists() || !audioFile.isFile) {
            Toast.makeText(activity, "歌曲文件不存在，无法分享", Toast.LENGTH_SHORT).show()
            return
        }

        val authority = "${activity.packageName}.fileprovider"
        val uri = runCatching { FileProvider.getUriForFile(activity, authority, audioFile) }
            .getOrElse {
                Toast.makeText(activity, "当前歌曲暂时无法分享", Toast.LENGTH_SHORT).show()
                return
            }

        val mimeType = audioMimeType(audioFile.extension)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.title)
            putExtra(Intent.EXTRA_SUBJECT, file.title)
            clipData = ClipData.newUri(activity.contentResolver, file.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        runCatching {
            activity.startActivity(Intent.createChooser(intent, "分享歌曲"))
        }.onFailure {
            Toast.makeText(activity, "未找到可分享的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteFallback(activity: AppCompatActivity, file: MusicScanner.MusicFile) {
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("删除歌曲")
            .setMessage("确定删除1首歌曲？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val ok = File(file.path).delete()
                Toast.makeText(activity, if (ok) "已删除1首歌曲" else "删除失败", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun audioMimeType(extension: String): String {
        return when (extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension.lowercase())
                ?: "audio/*"
        }
    }

}
