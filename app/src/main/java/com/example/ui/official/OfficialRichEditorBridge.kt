package com.example.ui.official

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对齐官方 `HybridEditorScriptInterface`（addJavascriptInterface 名：rich_editor_interface）。
 * 仅实现编辑/保存/图片链路所需回调，其余方法提供安全空实现以免 Web 端崩溃。
 */
class OfficialRichEditorBridge(
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onPagePrepared()
        fun onSaveData(richText: String)
        fun onTitleChanged(title: String)
        fun onTextCountChanged(count: Int)
        fun showToast(message: String)
        fun contentLength(): Long
        fun isDarkMode(): Boolean
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tag = "OfficialRichBridge"

    /** 信息栏日期：打开便签时用 note.modifyDate，避免一直显示「现在」 */
    @Volatile
    var infoTimeMillis: Long = System.currentTimeMillis()

    @JavascriptInterface
    fun getBaseUri(): String = "content://com.miui.notes/"

    @JavascriptInterface
    fun getViewMode(): String = "editor"

    @JavascriptInterface
    fun getContentLength(): Long = callbacks.contentLength()

    @JavascriptInterface
    fun getDeviceFlag(): Int = 0

    @JavascriptInterface
    fun isDarkMode(): Boolean = callbacks.isDarkMode()

    /**
     * 官方 H5 在 setData 后会执行 `E(isNoteCreated())` 控制占位层。
     * 返回 true 会导致「有正文时仍显示占位」，直到用户点图片才刷新；这里固定 false。
     * 空文档占位改由 H5 的 docChanged / isEmptyDoc 决定。
     */
    @JavascriptInterface
    fun isNoteCreated(): Boolean = false

    @JavascriptInterface
    fun canCreateMindNote(): Boolean = false

    @JavascriptInterface
    fun showMarkdown(): Boolean = false

    @JavascriptInterface
    fun panelNeedTaller(): Boolean = false

    @JavascriptInterface
    fun preserveAiStatements(): Boolean = false

    @JavascriptInterface
    fun usingAIRecorder(): Boolean = false

    @JavascriptInterface
    fun getLocaleInfo(): String {
        val locale = Locale.getDefault()
        return JSONObject()
            .put("locale", locale.language)
            .put("countryCode", locale.country)
            .put("isRtl", false)
            .toString()
    }

    @JavascriptInterface
    fun getStaticInfos(): String {
        // STATIC_INFOS.isNoteCreated 必须为 true，否则 index.html 不会挂载 info_timeString（日期空白）
        // 方法 isNoteCreated() 仍返回 false，避免 setData 后占位层残留
        val timeText = formatInfoTime(infoTimeMillis)
        return JSONObject()
            .put("edit_note_hint_title", "标题")
            .put("word_count", "0字")
            .put("image_annotation_edit_hint", "添加描述")
            .put("note_mindnote_entrance_hint_newversion", "记下你的想法…")
            // 空串：不展示「思维笔记」按钮文案
            .put("note_mindnote_entrance_icon_text_newversion", "")
            .put("isNoteCreated", true)
            .put("canCreateMindNote", false)
            .put("locale", Locale.getDefault().language)
            .put("countryCode", Locale.getDefault().country)
            .put("isRtl", false)
            .put("device_flag", 0)
            .put("info_timeString", timeText)
            .put("info_time", infoTimeMillis)
            .put("isFirstlyEnterNoteEdit", false)
            .toString()
    }

    fun formatInfoTime(millis: Long): String {
        return SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(millis))
    }

    @JavascriptInterface
    fun getStringFromResource(key: String?): String {
        return when (key) {
            "edit_note_hint_title" -> "标题"
            "image_annotation_edit_hint" -> "添加描述"
            "note_mindnote_entrance_hint_newversion" -> "记下你的想法…"
            "note_mindnote_entrance_icon_text_newversion" -> ""
            else -> key.orEmpty()
        }
    }

    @JavascriptInterface
    fun getNumberAndStringFromResource(number: Int, key: String?): String {
        return if (number == 0) "0字" else "${number}字"
    }

    @JavascriptInterface
    fun onPagePrepared() {
        mainHandler.post { callbacks.onPagePrepared() }
    }

    @JavascriptInterface
    fun onSaveData(newRichTextData: String?) {
        val data = newRichTextData.orEmpty()
        mainHandler.post { callbacks.onSaveData(data) }
    }

    @JavascriptInterface
    fun onTitleChanged(title: String?) {
        mainHandler.post { callbacks.onTitleChanged(title.orEmpty().trim()) }
    }

    @JavascriptInterface
    fun onTextCountChanged(textCount: Int) {
        mainHandler.post { callbacks.onTextCountChanged(textCount) }
    }

    @JavascriptInterface
    fun showToast(message: String?) {
        val msg = message.orEmpty()
        if (msg.isNotBlank()) mainHandler.post { callbacks.showToast(msg) }
    }

    @JavascriptInterface
    fun saveLog(log: String?) {
        Log.d(tag, log.orEmpty().take(2000))
    }

    @JavascriptInterface fun doPaste() {}
    @JavascriptInterface fun hidePopMenu() {}
    @JavascriptInterface fun notifyRefreshComplete() {}
    @JavascriptInterface fun notifySetNewContent() {}
    @JavascriptInterface fun onAnnotateImageInDocEnd() {}
    @JavascriptInterface fun onAnnotateImageInDocStart() {}
    @JavascriptInterface fun onChangeToMindMap() {}
    @JavascriptInterface fun onContentOverLength(overType: Int) {}
    @JavascriptInterface fun onCorrectNumChanged(num: Int) {}
    @JavascriptInterface fun onDisableScroll(disable: Boolean) {}
    @JavascriptInterface fun onEmbedElementClicked(elementJson: String?) {}
    @JavascriptInterface fun onFlowDone() {}
    @JavascriptInterface fun onFlowDone(a: String?) {}
    @JavascriptInterface fun onFlowDone(a: String?, b: String?) {}
    @JavascriptInterface fun onImageAnnotationOverLength() {}
    @JavascriptInterface fun onPlayAudioStateChanged(stateCode: Int) {}
    @JavascriptInterface fun onPopMenuHide() {}
    @JavascriptInterface fun onRefreshDone() {}
    @JavascriptInterface fun onRichTextPanelStateChanged(isOpen: Boolean, withAnim: Boolean) {}
    @JavascriptInterface fun onSelectedDataCallback(data: String?, flag: Int) {}
    @JavascriptInterface fun onShareImage(imgBase64: Array<String>?) {}
    @JavascriptInterface fun onStartDragElement(elementJson: String?) {}
    @JavascriptInterface fun onStateChanged(state: Int) {}
    @JavascriptInterface fun onStopDone() {}
    @JavascriptInterface fun onTextStyleChanged(statusJson: String?) {}
    @JavascriptInterface fun onUndoRedoStateChanged(undoEnable: Boolean, redoEnable: Boolean) {}
    @JavascriptInterface fun onUpdateViewState(
        canSelect: Boolean,
        canQuery: Boolean,
        canPhrase: Boolean,
        canTranslate: Boolean,
        selectData: String?,
        htmlData: String?
    ) {}
    @JavascriptInterface fun performHaptic(type: Int) {}
    @JavascriptInterface fun requestAudioDesc(fileId: String?) {}
    @JavascriptInterface fun showAIRecorderComponent(a: String?, b: Boolean, c: Int) {}
    @JavascriptInterface fun showAIRecorderRecording() {}
    @JavascriptInterface fun stopAIRecorder() {}
    @JavascriptInterface fun trackEvent(event: String?) {}
}
