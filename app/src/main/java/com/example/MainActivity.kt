package com.example

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.graphics.Rect
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.example.data.AppDatabase
import com.example.data.NoteRepository
import com.example.ui.CloudSyncDialog
import com.example.ui.NoteViewModel
import com.example.ui.NoteViewModelFactory
import com.example.ui.list.NoteListAdapter
import com.example.ui.list.NoteListItem
import com.example.ui.official.NoteEditActivity
import com.example.ui.theme.MyApplicationTheme
import com.google.android.material.appbar.AppBarLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 对齐官方 NotesListActivity + NoteListPageFragment：
 * NestedHeaderLayout 折叠顶栏（此处用 AppBar/CollapsingToolbar 复刻）+ RecyclerView 列表。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: NoteViewModel
    private lateinit var adapter: NoteListAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var composeOverlay: ComposeView
    private lateinit var searchInput: EditText
    private lateinit var notesCount: TextView
    private lateinit var titleExpanded: TextView
    private lateinit var titleCollapsed: TextView
    private lateinit var btnToggle: ImageButton
    private lateinit var btnSync: ImageButton
    private lateinit var headerActions: View
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView
    private lateinit var btnSelectAll: TextView
    private lateinit var btnSelectionStick: TextView
    private lateinit var btnSelectionDelete: TextView
    private lateinit var fabAdd: ImageButton

    private var backupPassword: String = ""
    private var pendingExportPassword: String? = null
    private var pendingImportPassword: String? = null
    private var showSyncDialog: Boolean = false
    private var gridMode: Boolean = true // onCreate 里会按 ViewModel 已持久化的值覆盖
    private var noteItemDecoration: RecyclerView.ItemDecoration? = null
    /** 从编辑页返回后需要滚顶并重排（改色/改内容会更新 modifyDate 顶到第一） */
    private var pendingEditReturnRefresh = false
    private lateinit var selectionBackCallback: OnBackPressedCallback

    private val editNoteLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 只有编辑页真正写库（RESULT_OK）才滚顶；仅打开再返回保持原滚动位置
        if (result.resultCode == RESULT_OK) {
            pendingEditReturnRefresh = true
            refreshAfterEditReturn()
        }
    }

    private val createJsonLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri ?: return@registerForActivityResult
        val password = pendingExportPassword ?: return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val exportResult = viewModel.exportEncryptedBackup(password)
                if (exportResult.isFailure) {
                    Toast.makeText(
                        this@MainActivity,
                        "导出失败：${exportResult.exceptionOrNull()?.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val bytes = exportResult.getOrThrow()
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                        ?: throw IllegalStateException("无法写入文件")
                }
                Toast.makeText(this@MainActivity, "加密备份导出成功！", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "导出失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                pendingExportPassword = null
            }
        }
    }

    private val openJsonLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val password = pendingImportPassword ?: backupPassword
        lifecycleScope.launch {
            try {
                val fileBytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("无法读取文件")
                }
                val result = viewModel.importBackupFile(fileBytes, password)
                if (result.isSuccess) {
                    Toast.makeText(
                        this@MainActivity,
                        "成功导入 ${result.getOrNull()} 条便签！",
                        Toast.LENGTH_SHORT
                    ).show()
                    hideSyncDialog()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "导入失败：${result.exceptionOrNull()?.localizedMessage ?: "格式不正确"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "导入失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                pendingImportPassword = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_notes_list)

        val database = AppDatabase.getDatabase(applicationContext)
        val repository = NoteRepository(database.noteDao())
        viewModel = ViewModelProvider(
            this,
            NoteViewModelFactory(application, repository)
        )[NoteViewModel::class.java]
        gridMode = viewModel.isGridView.value

        recycler = findViewById(R.id.notes_recycler)
        emptyView = findViewById(R.id.empty_view)
        composeOverlay = findViewById(R.id.compose_overlay)
        searchInput = findViewById(R.id.search_input)
        notesCount = findViewById(R.id.notes_count)
        titleExpanded = findViewById(R.id.title_notes)
        titleCollapsed = findViewById(R.id.title_notes_collapsed)
        btnToggle = findViewById(R.id.btn_toggle_layout)
        btnSync = findViewById(R.id.btn_sync)
        headerActions = findViewById(R.id.header_actions)
        selectionBar = findViewById(R.id.selection_bar)
        selectionCount = findViewById(R.id.selection_count)
        btnSelectAll = findViewById(R.id.btn_select_all)
        btnSelectionStick = findViewById(R.id.btn_selection_stick)
        btnSelectionDelete = findViewById(R.id.btn_selection_delete)
        fabAdd = findViewById(R.id.fab_add)
        val btnSelectionClose = findViewById<ImageButton>(R.id.btn_selection_close)
        val appBar = findViewById<AppBarLayout>(R.id.app_bar)
        // 禁止抬升变色（Material3 默认会变成浅紫容器色）
        appBar.setLiftable(false)
        appBar.isLifted = false

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val topPad = bars.top + (8 * resources.displayMetrics.density).toInt()
            // 右上角按钮贴在状态栏下方，与展开标题同一基线
            headerActions.setPadding(
                headerActions.paddingLeft,
                topPad,
                headerActions.paddingRight,
                headerActions.paddingBottom
            )
            selectionBar.setPadding(
                selectionBar.paddingLeft,
                topPad,
                selectionBar.paddingRight,
                selectionBar.paddingBottom
            )
            fabAdd.translationY = -bars.bottom.toFloat()
            insets
        }

        // 折叠动画：大标题/计数/搜索淡出，小标题淡入；右上角图标始终可见
        appBar.addOnOffsetChangedListener { bar, verticalOffset ->
            val range = bar.totalScrollRange.toFloat().coerceAtLeast(1f)
            val collapse = (abs(verticalOffset) / range).coerceIn(0f, 1f)
            val smooth = collapse * collapse * (3f - 2f * collapse)
            titleExpanded.alpha = (1f - smooth * 1.2f).coerceIn(0f, 1f)
            notesCount.alpha = (1f - collapse * 2.5f).coerceIn(0f, 1f)
            searchInput.alpha = (1f - smooth * 1.5f).coerceIn(0f, 1f)
            searchInput.translationY = -20f * smooth
            titleCollapsed.alpha = ((collapse - 0.45f) / 0.55f).coerceIn(0f, 1f)
        }

        adapter = NoteListAdapter(
            onOpen = { item ->
                editNoteLauncher.launch(NoteEditActivity.intent(this, item.id))
            },
            onSelectionChanged = { mode, count -> updateSelectionUi(mode, count) }
        )
        adapter.gridMode = gridMode
        recycler.adapter = adapter
        // 瀑布流卡片高度不固定，fixed size 易在增删后布局异常
        recycler.setHasFixedSize(false)
        recycler.setItemViewCacheSize(12)
        recycler.itemAnimator = null
        applyLayoutManager(gridMode)
        updateToggleIcon()

        selectionBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                adapter.exitSelection()
            }
        }
        onBackPressedDispatcher.addCallback(this, selectionBackCallback)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.setSearchQuery(s?.toString().orEmpty())
            }
        })

        btnToggle.setOnClickListener {
            gridMode = !gridMode
            adapter.gridMode = gridMode
            applyLayoutManager(gridMode)
            updateToggleIcon()
            viewModel.toggleLayout()
        }
        btnSync.setOnClickListener { showSyncDialog() }
        fabAdd.setOnClickListener {
            editNoteLauncher.launch(NoteEditActivity.intentNew(this))
        }
        btnSelectionClose.setOnClickListener { adapter.exitSelection() }
        btnSelectAll.setOnClickListener {
            if (adapter.isAllVisibleSelected()) {
                adapter.exitSelection()
            } else {
                adapter.selectAllVisible()
            }
        }
        btnSelectionStick.setOnClickListener {
            val item = adapter.singleSelectedItem() ?: return@setOnClickListener
            viewModel.toggleStickyDirectly(item.note)
            adapter.exitSelection()
            Toast.makeText(
                this,
                if (item.sticky) "已取消置顶" else "已置顶",
                Toast.LENGTH_SHORT
            ).show()
        }
        btnSelectionDelete.setOnClickListener { confirmBatchDelete() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.listItems.collect { items ->
                        val snapshot = items.toList()
                        notesCount.text = "${items.size} 个便签"
                        val empty = items.isEmpty()
                        emptyView.isVisible = empty
                        recycler.visibility = if (empty) View.INVISIBLE else View.VISIBLE
                        emptyView.text = if (searchInput.text.isNullOrBlank()) {
                            "写下第一条随心记便签吧！"
                        } else {
                            "没有搜索到匹配的便签"
                        }

                        // 从编辑页返回会重订阅 Flow；内容没变就别 submitList，
                        // 否则回调里乱 notify 会打乱瀑布流，滑到顶才「重排」
                        if (snapshot == adapter.currentList) {
                            if (pendingEditReturnRefresh) {
                                reflowStaggeredToTop()
                                pendingEditReturnRefresh = false
                            }
                            return@collect
                        }

                        val previousCount = adapter.itemCount
                        adapter.submitList(snapshot) {
                            when {
                                snapshot.size < previousCount -> Unit
                                snapshot.size > previousCount || pendingEditReturnRefresh -> {
                                    reflowStaggeredToTop()
                                }
                                // 普通 Diff 已足够；禁止额外 notifyItemRangeChanged
                            }
                            pendingEditReturnRefresh = false
                        }
                    }
                }
                launch {
                    viewModel.isGridView.collect { isGrid ->
                        if (gridMode != isGrid) {
                            gridMode = isGrid
                            adapter.gridMode = isGrid
                            applyLayoutManager(isGrid)
                            updateToggleIcon()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingEditReturnRefresh) {
            refreshAfterEditReturn()
        }
    }

    /** 编辑返回：等最新列表落地后，强制瀑布流从左上角第一格重排 */
    private fun refreshAfterEditReturn() {
        if (!::recycler.isInitialized || !::adapter.isInitialized) return
        recycler.post {
            val latest = viewModel.listItems.value.toList()
            adapter.submitList(latest) {
                reflowStaggeredToTop()
                pendingEditReturnRefresh = false
            }
        }
    }

    private fun updateToggleIcon() {
        // 原 Compose：网格模式显示列表图标，列表模式显示网格图标
        btnToggle.setImageResource(
            if (gridMode) R.drawable.ic_view_list else R.drawable.ic_view_grid
        )
    }

    private fun applyLayoutManager(isGrid: Boolean) {
        noteItemDecoration?.let { recycler.removeItemDecoration(it) }
        noteItemDecoration = null

        val hPad = resources.getDimensionPixelSize(
            if (isGrid) R.dimen.common_two_column_recycler_horizontal_padding
            else R.dimen.common_single_column_recycler_horizontal_padding
        )
        recycler.setPadding(hPad, recycler.paddingTop, hPad, recycler.paddingBottom)

        recycler.layoutManager = if (isGrid) {
            // 对齐官方：common_grid_item_two_column_space=10dp 为卡间距；
            // ItemDecoration 左右各加一半，避免旧版 item margin=6dp 叠成 12dp 偏疏
            val gap = resources.getDimensionPixelSize(R.dimen.common_grid_item_two_column_space)
            val half = gap / 2
            val spanCount = 2
            val decoration = object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    outRect.set(half, half, half, half)
                    val pos = parent.getChildAdapterPosition(view)
                    if (pos >= 0 && pos / spanCount == 0) {
                        outRect.top = 0
                    }
                }
            }
            noteItemDecoration = decoration
            recycler.addItemDecoration(decoration)
            StaggeredGridLayoutManager(spanCount, StaggeredGridLayoutManager.VERTICAL).apply {
                gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS
            }
        } else {
            LinearLayoutManager(this)
        }
    }

    /**
     * 编辑/新建后 StaggeredGrid 常把首条派到右列，左上角空一格；
     * 仅 invalidateSpanAssignments 不够，需重建 LayoutManager 再 scrollToPositionWithOffset(0,0)。
     */
    private fun reflowStaggeredToTop() {
        findViewById<AppBarLayout>(R.id.app_bar).setExpanded(true, false)
        recycler.post {
            if (gridMode) {
                applyLayoutManager(true)
                val lm = recycler.layoutManager as? StaggeredGridLayoutManager
                if (adapter.itemCount > 0) {
                    lm?.scrollToPositionWithOffset(0, 0)
                }
                // 再补一帧，避免 Diff 后跨列状态回弹出左上空洞
                recycler.post {
                    val lm2 = recycler.layoutManager as? StaggeredGridLayoutManager
                    lm2?.invalidateSpanAssignments()
                    if (adapter.itemCount > 0) {
                        lm2?.scrollToPositionWithOffset(0, 0)
                    }
                }
            } else if (adapter.itemCount > 0) {
                recycler.scrollToPosition(0)
            }
        }
    }

    private fun updateSelectionUi(selectionMode: Boolean, selectedCount: Int) {
        selectionBar.isVisible = selectionMode
        headerActions.isVisible = !selectionMode
        fabAdd.isVisible = !selectionMode
        selectionBackCallback.isEnabled = selectionMode
        selectionCount.text = "已选 $selectedCount 项"
        btnSelectAll.text = if (adapter.isAllVisibleSelected()) "取消全选" else "全选"
        btnSelectionDelete.isEnabled = selectedCount > 0
        btnSelectionDelete.alpha = if (selectedCount > 0) 1f else 0.4f

        val single = if (selectedCount == 1) adapter.singleSelectedItem() else null
        btnSelectionStick.isVisible = single != null
        if (single != null) {
            btnSelectionStick.text = if (single.sticky) "取消置顶" else "置顶"
        }
    }

    private fun confirmBatchDelete() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("删除便签")
            .setMessage("确定删除选中的 ${ids.size} 条便签吗？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteNotesDirectly(ids)
                adapter.exitSelection()
                Toast.makeText(this, "已删除 ${ids.size} 条", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSyncDialog() {
        showSyncDialog = true
        composeOverlay.visibility = View.VISIBLE
        composeOverlay.setContent {
            MyApplicationTheme {
                var pwd by remember { mutableStateOf(backupPassword) }
                CloudSyncDialog(
                    viewModel = viewModel,
                    backupPassword = pwd,
                    onBackupPasswordChange = {
                        pwd = it
                        backupPassword = it
                    },
                    onDismiss = {
                        viewModel.resetSyncState()
                        hideSyncDialog()
                    },
                    onSaveFileExport = {
                        if (pwd.length < 6) {
                            Toast.makeText(this, "备份密码至少 6 位", Toast.LENGTH_SHORT).show()
                            return@CloudSyncDialog
                        }
                        pendingExportPassword = pwd
                        hideSyncDialog()
                        createJsonLauncher.launch("mi_notes_backup_${System.currentTimeMillis() / 1000}.bin")
                    },
                    onSelectFileImport = {
                        if (pwd.length < 6) {
                            Toast.makeText(this, "请输入导出时设置的备份密码（至少 6 位）", Toast.LENGTH_SHORT).show()
                            return@CloudSyncDialog
                        }
                        pendingImportPassword = pwd
                        openJsonLauncher.launch(
                            arrayOf("application/octet-stream", "application/json", "*/*")
                        )
                    }
                )
            }
        }
    }

    private fun hideSyncDialog() {
        showSyncDialog = false
        composeOverlay.setContent { }
        composeOverlay.visibility = View.GONE
    }
}
