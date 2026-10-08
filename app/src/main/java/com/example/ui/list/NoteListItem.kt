package com.example.ui.list

import android.content.Context
import com.example.data.NoteEntity
import com.example.data.NoteImageCache
import com.example.data.XiaomiRichTextParser
import com.example.ui.formatMiNoteDate

/**
 * 列表行预计算模型（对齐官方 NoteCache：bind 前离线算好标题/摘要/时间/首图）。
 */
data class NoteListItem(
    val id: String,
    val note: NoteEntity,
    val displayTitle: String,
    val bodyText: String,
    val formattedDate: String,
    val bgColor: Int,
    val sticky: Boolean,
    /** 对齐官方 NoteCache.getFirstImage() */
    val firstImageFileId: String?,
    /** 首图是否已落盘；随 imageCacheTick 重算，驱动 DiffUtil rebind */
    val imagePreviewReady: Boolean = false
) {
    companion object {
        private val CARD_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(),
            0xFFFFF4E5.toInt(),
            0xFFE3F2FD.toInt(),
            0xFFE8F5E9.toInt(),
            0xFFFCE4EC.toInt()
        )

        fun from(note: NoteEntity, context: Context? = null): NoteListItem {
            val preview = XiaomiRichTextParser.buildNotePreview(
                title = note.title,
                snippet = note.snippet,
                maxBodyLines = 3
            )
            val body = preview.bodyText.ifBlank { "暂无附加内容" }
            val fileId = XiaomiRichTextParser.extractImageFileIds(note.snippet).firstOrNull()
            val ready = context != null && !fileId.isNullOrBlank() &&
                NoteImageCache.isCached(context, fileId)
            return NoteListItem(
                id = note.id,
                note = note,
                displayTitle = preview.displayTitle,
                bodyText = body,
                formattedDate = formatMiNoteDate(note.modifyDate),
                bgColor = CARD_COLORS[note.colorId.coerceAtLeast(0) % CARD_COLORS.size],
                sticky = note.stickyTime > 0L,
                firstImageFileId = fileId,
                imagePreviewReady = ready
            )
        }
    }
}
