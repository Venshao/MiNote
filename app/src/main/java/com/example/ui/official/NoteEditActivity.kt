package com.example.ui.official

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.data.AppDatabase
import com.example.data.NoteEntity
import com.example.data.NoteImageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 原生官方风格编辑页：全屏 noteeditor WebView + 对齐官方底栏。
 * 支持文字 / TODO / 图片；颜色标记沿用官方 colorId→themeId。
 */
class NoteEditActivity : ComponentActivity() {

    private var noteId: String = ""
    private var note: NoteEntity? = null
    private var isNewNote = false
    private var titleDraft = ""
    private var snippetDraft = ""
    private var colorIdDraft = 0
    private var stylePanelVisible = false
    private var colorPanelVisible = false
    /** 用户是否真正改过；仅打开再退出不应写库/刷新 modifyDate */
    private var userEdited = false
    /** setData 回写期间忽略脏标记 */
    private var acceptUserEdits = false
    private var baselineTitle = ""
    private var baselineSnippet = ""
    private var baselineColorId = 0

    private lateinit var webView: WebView
    private lateinit var loading: ProgressBar
    private lateinit var stylePanel: HorizontalScrollView
    private lateinit var colorPanel: LinearLayout
    private var editor: OfficialWebEditorController? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val fileId = withContext(Dispatchers.IO) {
                NoteImageCache.importFromUri(this@NoteEditActivity, uri)
            }
            if (fileId == null) {
                Toast.makeText(this@NoteEditActivity, "图片导入失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            markEdited()
            // 只走官方 insertImageEnd；内容由 onSaveData / 退出时 pmNode2SavedData 回写
            editor?.insertImageByFileId(fileId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_edit)
        applySystemBarInsets()

        noteId = intent.getStringExtra(EXTRA_NOTE_ID).orEmpty()
        isNewNote = noteId.isBlank()
        webView = findViewById(R.id.note_editor_webview)
        loading = findViewById(R.id.editor_loading)
        stylePanel = findViewById(R.id.style_panel)
        colorPanel = findViewById(R.id.color_panel)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finishWithSave() }
        findViewById<ImageButton>(R.id.btn_done).setOnClickListener { finishWithSave() }

        bindToolbar()
        bindColorDots()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishWithSave()
        })

        editor = OfficialWebEditorController(
            context = this,
            webView = webView,
            callbacks = object : OfficialWebEditorController.Callbacks {
                override fun onReady() {
                    loading.visibility = View.GONE
                    webView.requestFocus()
                    // setData 异步回写结束后：吸收规范化结果为 baseline，再侦测真实用户编辑
                    webView.postDelayed({
                        baselineTitle = titleDraft
                        baselineSnippet = snippetDraft
                        baselineColorId = colorIdDraft
                        acceptUserEdits = true
                    }, 450)
                }

                override fun onTitleChanged(title: String) {
                    titleDraft = title
                    if (!acceptUserEdits) {
                        // setData 回写窗口：同步 baseline，避免误标脏；仍保留 draft 供提前退出比对
                        baselineTitle = title
                    } else if (title != baselineTitle) {
                        markEdited()
                    }
                }

                override fun onContentChanged(richText: String) {
                    snippetDraft = richText
                    if (!acceptUserEdits) {
                        baselineSnippet = richText
                    } else if (richText != baselineSnippet) {
                        markEdited()
                    }
                }

                override fun onToast(message: String) {
                    Toast.makeText(this@NoteEditActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        )

        lifecycleScope.launch {
            val entity = if (isNewNote) {
                createBlankNote()
            } else {
                withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(applicationContext).noteDao().getNoteById(noteId)
                }
            }
            if (entity == null) {
                Toast.makeText(this@NoteEditActivity, "便签不存在", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            note = entity
            noteId = entity.id
            titleDraft = entity.title
            snippetDraft = entity.snippet
            colorIdDraft = entity.colorId
            // 先用加载值作 baseline；setData 回写期间会通过 !acceptUserEdits 路径同步覆盖
            baselineTitle = titleDraft
            baselineSnippet = snippetDraft
            baselineColorId = colorIdDraft
            findViewById<TextView>(R.id.tv_top_title).text =
                if (isNewNote) "新建便签" else "编辑便签"
            highlightColorDot(colorIdDraft)

            val themeId = mapToOfficialThemeId(colorIdDraft)
            editor?.attach(themeId)
            editor?.setInitialData(
                title = titleDraft,
                richText = snippetDraft,
                modifyTimeMillis = entity.modifyDate
            )
        }
    }

    private fun createBlankNote(): NoteEntity {
        val now = System.currentTimeMillis()
        val randomSuffix = (1000..9999).random().toString()
        return NoteEntity(
            id = "$now$randomSuffix",
            snippet = "",
            modifyDate = now,
            createDate = now,
            colorId = 0,
            status = "normal",
            extraInfo = "{\"title\":\"\"}"
        )
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.note_edit_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun bindToolbar() {
        findViewById<ImageButton>(R.id.btn_gallery).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<ImageButton>(R.id.btn_checkbox).setOnClickListener {
            markEdited()
            editor?.setCheckbox()
        }
        findViewById<ImageButton>(R.id.btn_bullet).setOnClickListener {
            markEdited()
            editor?.setBullet()
        }
        val styleBtn = findViewById<ImageButton>(R.id.btn_style_toggle)
        val colorBtn = findViewById<ImageButton>(R.id.btn_color_toggle)
        styleBtn.setOnClickListener {
            stylePanelVisible = !stylePanelVisible
            stylePanel.visibility = if (stylePanelVisible) View.VISIBLE else View.GONE
            if (stylePanelVisible) {
                colorPanelVisible = false
                colorPanel.visibility = View.GONE
            }
            // 底栏图标统一主题黄 #FFBB00；选中态用透明度区分
            styleBtn.alpha = if (stylePanelVisible) 1f else 0.72f
            colorBtn.alpha = 0.72f
            styleBtn.clearColorFilter()
            colorBtn.clearColorFilter()
        }
        colorBtn.setOnClickListener {
            colorPanelVisible = !colorPanelVisible
            colorPanel.visibility = if (colorPanelVisible) View.VISIBLE else View.GONE
            if (colorPanelVisible) {
                stylePanelVisible = false
                stylePanel.visibility = View.GONE
            }
            colorBtn.alpha = if (colorPanelVisible) 1f else 0.72f
            styleBtn.alpha = 0.72f
            colorBtn.clearColorFilter()
            styleBtn.clearColorFilter()
        }
        findViewById<ImageButton>(R.id.btn_undo).setOnClickListener {
            markEdited()
            editor?.undo()
        }
        findViewById<ImageButton>(R.id.btn_redo).setOnClickListener {
            markEdited()
            editor?.redo()
        }

        findViewById<TextView>(R.id.btn_h1).setOnClickListener { markEdited(); editor?.setH1() }
        findViewById<TextView>(R.id.btn_h2).setOnClickListener { markEdited(); editor?.setH2() }
        findViewById<TextView>(R.id.btn_bold).setOnClickListener { markEdited(); editor?.setBold() }
        findViewById<TextView>(R.id.btn_italic).setOnClickListener { markEdited(); editor?.setItalic() }
        findViewById<TextView>(R.id.btn_underline).setOnClickListener { markEdited(); editor?.setUnderline() }
        findViewById<TextView>(R.id.btn_align_left).setOnClickListener { markEdited(); editor?.alignLeft() }
        findViewById<TextView>(R.id.btn_align_center).setOnClickListener { markEdited(); editor?.alignCenter() }
        findViewById<TextView>(R.id.btn_align_right).setOnClickListener { markEdited(); editor?.alignRight() }
    }

    private fun markEdited() {
        userEdited = true
    }

    private fun bindColorDots() {
        val ids = listOf(
            R.id.color_dot_0,
            R.id.color_dot_1,
            R.id.color_dot_2,
            R.id.color_dot_3,
            R.id.color_dot_4
        )
        ids.forEachIndexed { index, viewId ->
            val dot = findViewById<View>(viewId)
            dot.background = ovalDrawable(NOTE_BG_COLORS[index], selected = false)
            dot.setOnClickListener {
                if (colorIdDraft != index) markEdited()
                colorIdDraft = index
                highlightColorDot(index)
            }
        }
    }

    private fun highlightColorDot(selected: Int) {
        val ids = listOf(
            R.id.color_dot_0,
            R.id.color_dot_1,
            R.id.color_dot_2,
            R.id.color_dot_3,
            R.id.color_dot_4
        )
        ids.forEachIndexed { index, viewId ->
            findViewById<View>(viewId).background =
                ovalDrawable(NOTE_BG_COLORS[index], selected = index == selected)
        }
        applyRootTint(selected)
    }

    private fun ovalDrawable(fill: Int, selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(
                if (selected) 4 else 2,
                if (selected) 0xFFFFBB00.toInt() else 0xFFE1E3E8.toInt()
            )
        }
    }

    private fun applyRootTint(colorId: Int) {
        val bg = NOTE_BG_COLORS[colorId.coerceIn(0, NOTE_BG_COLORS.lastIndex)]
        findViewById<View>(R.id.note_edit_root).setBackgroundColor(bg)
        webView.setBackgroundColor(bg)
    }

    private fun finishWithSave() {
        val current = note ?: run {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        lifecycleScope.launch {
            // 始终拉一次编辑器最新内容，避免 450ms 窗口内改动未置 userEdited 而丢改
            flushEditorToDrafts()
            editor?.latestBody()?.let { if (it.isNotEmpty()) snippetDraft = it }
            editor?.latestTitle()?.let { titleDraft = it }

            val hasContent = snippetDraft.isNotBlank() || titleDraft.isNotBlank()
            val contentChanged = titleDraft != baselineTitle ||
                snippetDraft != baselineSnippet ||
                colorIdDraft != baselineColorId
            val shouldPersist = when {
                !hasContent -> false
                // 已有便签：无内容和颜色变化则不写库，避免刷新 modifyDate
                !isNewNote && !userEdited && !contentChanged -> false
                else -> true
            }
            if (shouldPersist) {
                withContext(Dispatchers.IO) {
                    val extraJson = try {
                        val json = if (current.extraInfo.isNotBlank()) {
                            JSONObject(current.extraInfo)
                        } else {
                            JSONObject()
                        }
                        json.put("title", titleDraft)
                        json.toString()
                    } catch (_: Exception) {
                        JSONObject().put("title", titleDraft).toString()
                    }
                    val updated = current.copy(
                        snippet = snippetDraft,
                        extraInfo = extraJson,
                        colorId = colorIdDraft,
                        themeId = mapToOfficialThemeId(colorIdDraft),
                        modifyDate = System.currentTimeMillis()
                    )
                    AppDatabase.getDatabase(applicationContext).noteDao().insertNote(updated)
                }
                // 仅真正写库时通知列表滚顶重排
                setResult(RESULT_OK)
            } else {
                setResult(RESULT_CANCELED)
            }
            finish()
        }
    }

    private suspend fun flushEditorToDrafts() {
        val ctrl = editor ?: return
        suspendCancellableCoroutine { cont ->
            ctrl.requestSave {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        editor?.requestSave()
    }

    override fun onDestroy() {
        editor?.destroy()
        editor = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NOTE_ID = "note_id"

        fun intent(context: Context, noteId: String): Intent {
            return Intent(context, NoteEditActivity::class.java).putExtra(EXTRA_NOTE_ID, noteId)
        }

        fun intentNew(context: Context): Intent {
            return Intent(context, NoteEditActivity::class.java)
        }

        /** colorId → 官方 themeId（避开 0 自动 dark） */
        fun mapToOfficialThemeId(colorId: Int): Int {
            return when (colorId % 5) {
                0 -> 1
                1 -> 2
                2 -> 1
                3 -> 3
                else -> 4
            }
        }

        private val NOTE_BG_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), // 简约白
            0xFFFFF4E5.toInt(), // 暖阳黄
            0xFFE3F2FD.toInt(), // 静谧蓝
            0xFFE8F5E9.toInt(), // 薄荷绿
            0xFFFCE4EC.toInt()  // 珊瑚粉
        )
    }
}
