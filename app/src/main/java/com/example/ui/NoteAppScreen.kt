package com.example.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.graphicsLayer
import com.example.data.XiaomiRichTextParser
import com.example.data.RichTextLine
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.NoteEntity
import com.example.ui.theme.MiNoteColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private const val NOTES_INITIAL_PAGE_SIZE = 20
private const val NOTES_PAGE_SIZE = 20

@Composable
fun NoteAppScreen(viewModel: NoteViewModel) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isGridView by viewModel.isGridView.collectAsStateWithLifecycle()
    val editingNote by viewModel.editingNote.collectAsStateWithLifecycle()

    // Local copy of notes to prevent active screen removal before exit transitions complete
    var activeEditingNote by remember { mutableStateOf<NoteEntity?>(null) }

    // 0f = 编辑页完全展示，1f = 滑出屏幕；使用 tween 避免 spring 回弹导致末尾抖动
    val isEditorOpen = editingNote != null
    val editorOffsetY by animateFloatAsState(
        targetValue = if (isEditorOpen) 0f else 1f,
        animationSpec = tween(
            durationMillis = if (isEditorOpen) 300 else 260,
            easing = FastOutSlowInEasing
        ),
        finishedListener = { value ->
            if (value >= 1f) {
                activeEditingNote = null
            }
        },
        label = "EditorOffset"
    )

    SideEffect {
        if (editingNote != null) {
            activeEditingNote = editingNote
        }
    }

    // Gracefully handle back presses by saving the active editing note instead of exiting the app
    BackHandler(enabled = editingNote != null) {
        viewModel.saveActiveNote()
    }

    val listState = rememberLazyListState()
    val gridState = rememberLazyStaggeredGridState()

    val statusBarHeightDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarHeightDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topPaddingDp = statusBarHeightDp + 130.dp

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showImportExportDialog by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf("") }
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }
    var pendingImportPassword by remember { mutableStateOf<String?>(null) }

    // Backup & Restore Launchers (Storage Access Framework)
    val createJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let {
            val password = pendingExportPassword ?: return@let
            coroutineScope.launch {
                try {
                    val exportResult = viewModel.exportEncryptedBackup(password)
                    if (exportResult.isFailure) {
                        Toast.makeText(
                            context,
                            "导出失败：${exportResult.exceptionOrNull()?.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(exportResult.getOrThrow())
                    }
                    Toast.makeText(context, "加密备份导出成功！", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "导出失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                } finally {
                    pendingExportPassword = null
                }
            }
        }
    }

    val openJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val password = pendingImportPassword ?: backupPassword
            coroutineScope.launch {
                try {
                    val fileBytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes()
                    } ?: throw IllegalStateException("无法读取文件")
                    val result = viewModel.importBackupFile(fileBytes, password)
                    if (result.isSuccess) {
                        Toast.makeText(
                            context,
                            "成功导入 ${result.getOrNull()} 条便签！",
                            Toast.LENGTH_SHORT
                        ).show()
                        showImportExportDialog = false
                    } else {
                        Toast.makeText(
                            context,
                            "导入失败：${result.exceptionOrNull()?.localizedMessage ?: "格式不正确"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "导入失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                } finally {
                    pendingImportPassword = null
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA)) // Pure minimalist light gray background
    ) {
        NotesScrollContent(
            notes = notes,
            isGridView = isGridView,
            topPadding = topPaddingDp,
            searchQuery = searchQuery,
            viewModel = viewModel,
            listState = listState,
            gridState = gridState,
            modifier = Modifier.fillMaxSize()
        )

        CollapsingNoteHeader(
            notesCount = notes.size,
            searchQuery = searchQuery,
            isGridView = isGridView,
            listState = listState,
            gridState = gridState,
            viewModel = viewModel,
            onOpenSyncDialog = { showImportExportDialog = true },
            modifier = Modifier.fillMaxWidth()
        )

        // Add note floating button in bottom end (Sleek minimalist round orange button)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 32.dp, end = 24.dp)
                .navigationBarsPadding()
        ) {
            FloatingActionButton(
                onClick = { viewModel.startNewNote() },
                containerColor = MiNoteColors.ActionYellow,
                contentColor = Color.White,
                shape = CircleShape, // Perfectly round
                modifier = Modifier
                    .size(56.dp)
                    .testTag("add_note_fab"),
                elevation = FloatingActionButtonDefaults.elevation(2.dp) // Soft lighter shadow
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新增便签",
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Slide-in Custom Editor Pane (using translationY to make transition incredibly smooth with zero content shifting)
        val activeNoteCopy = activeEditingNote
        if (activeNoteCopy != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = size.height * editorOffsetY.coerceIn(0f, 1f)
                        clip = true
                    }
            ) {
                NoteEditorScreen(
                    note = activeNoteCopy,
                    onSave = { viewModel.saveActiveNote() },
                    onDismiss = { viewModel.discardOrCancelActiveNote() },
                    onDelete = { viewModel.deleteActiveNote() },
                    onUpdateSnippetAndTitle = { s, t -> viewModel.updateActiveNoteSnippetAndTitle(s, t) },
                    onUpdateColor = { c -> viewModel.updateActiveNoteColor(c) },
                    onToggleSticky = { viewModel.toggleActiveNoteSticky() },
                    statusBarHeight = statusBarHeightDp,
                    navigationBarHeight = navigationBarHeightDp
                )
            }
        }

        if (showImportExportDialog) {
            CloudSyncDialog(
                viewModel = viewModel,
                backupPassword = backupPassword,
                onBackupPasswordChange = { backupPassword = it },
                onDismiss = {
                    viewModel.resetSyncState()
                    showImportExportDialog = false
                },
                onSaveFileExport = {
                    if (backupPassword.length < 6) {
                        Toast.makeText(context, "备份密码至少 6 位", Toast.LENGTH_SHORT).show()
                        return@CloudSyncDialog
                    }
                    pendingExportPassword = backupPassword
                    showImportExportDialog = false
                    createJsonLauncher.launch("mi_notes_backup_${System.currentTimeMillis() / 1000}.bin")
                },
                onSelectFileImport = {
                    if (backupPassword.length < 6) {
                        Toast.makeText(context, "请输入导出时设置的备份密码（至少 6 位）", Toast.LENGTH_SHORT).show()
                        return@CloudSyncDialog
                    }
                    pendingImportPassword = backupPassword
                    openJsonLauncher.launch(
                        arrayOf("application/octet-stream", "application/json", "*/*")
                    )
                }
            )
        }
    }
}

/** 便签列表/瀑布流：不读取滚动折叠状态，避免滑动时触发整页重绘 */
@Composable
private fun NotesScrollContent(
    notes: List<NoteEntity>,
    isGridView: Boolean,
    topPadding: Dp,
    searchQuery: String,
    viewModel: NoteViewModel,
    listState: LazyListState,
    gridState: LazyStaggeredGridState,
    modifier: Modifier = Modifier
) {
    if (notes.isEmpty()) {
        Box(
            modifier = modifier.padding(top = topPadding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Notes,
                    contentDescription = "空便签",
                    tint = Color(0xFFE5DED0),
                    modifier = Modifier.size(80.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (searchQuery.isEmpty()) "写下第一条随心记便签吧！" else "没有搜索到匹配的便签",
                    fontSize = 16.sp,
                    color = Color(0xFF8C8476),
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (searchQuery.isEmpty()) {
                        "点击底部的黄色圆钮添加便签，或点击右上角云同步从小米云端拉取便签。"
                    } else {
                        "更改关键词重试"
                    },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = 13.sp,
                    color = Color(0xFFB0A89A)
                )
            }
        }
        return
    }

    // 首屏只挂载前 20 条，接近底部时再追加，减轻初次打开与长列表测量压力
    var visibleCount by remember { mutableIntStateOf(NOTES_INITIAL_PAGE_SIZE) }

    LaunchedEffect(notes.size, searchQuery) {
        visibleCount = minOf(NOTES_INITIAL_PAGE_SIZE, notes.size)
    }

    val displayedNotes = remember(notes, visibleCount) {
        notes.take(visibleCount.coerceAtMost(notes.size))
    }

    LaunchedEffect(isGridView, notes.size) {
        val scrollFlow = if (isGridView) {
            snapshotFlow {
                val info = gridState.layoutInfo
                Pair(info.visibleItemsInfo.lastOrNull()?.index, info.totalItemsCount)
            }
        } else {
            snapshotFlow {
                val info = listState.layoutInfo
                Pair(info.visibleItemsInfo.lastOrNull()?.index, info.totalItemsCount)
            }
        }
        scrollFlow.collect { (lastVisible, totalItems) ->
            if (visibleCount < notes.size &&
                totalItems > 0 &&
                lastVisible != null &&
                lastVisible >= totalItems - 3
            ) {
                visibleCount = minOf(visibleCount + NOTES_PAGE_SIZE, notes.size)
            }
        }
    }

    if (isGridView) {
        LazyVerticalStaggeredGrid(
            state = gridState,
            columns = StaggeredGridCells.Fixed(2),
            contentPadding = PaddingValues(top = topPadding, bottom = 80.dp, start = 16.dp, end = 16.dp),
            verticalItemSpacing = 12.dp,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = modifier
        ) {
            items(
                items = displayedNotes,
                key = { it.id },
                contentType = { "note_card" }
            ) { note ->
                val openEditor = remember(note.id, note.modifyDate) { { viewModel.startEditing(note) } }
                val deleteNote = remember(note.id) { { viewModel.deleteNoteDirectly(note.id) } }
                val toggleSticky = remember(note.id, note.stickyTime) { { viewModel.toggleStickyDirectly(note) } }
                NoteCard(
                    note = note,
                    onClick = openEditor,
                    onDelete = deleteNote,
                    onToggleSticky = toggleSticky
                )
            }
        }
    } else {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = topPadding, bottom = 80.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = modifier
        ) {
            items(
                items = displayedNotes,
                key = { it.id },
                contentType = { "note_card" }
            ) { note ->
                val openEditor = remember(note.id, note.modifyDate) { { viewModel.startEditing(note) } }
                val deleteNote = remember(note.id) { { viewModel.deleteNoteDirectly(note.id) } }
                val toggleSticky = remember(note.id, note.stickyTime) { { viewModel.toggleStickyDirectly(note) } }
                NoteCard(
                    note = note,
                    onClick = openEditor,
                    onDelete = deleteNote,
                    onToggleSticky = toggleSticky
                )
            }
        }
    }
}

/** 顶部折叠标题栏：滚动状态仅在此重组，不拖累列表项 */
@Composable
private fun CollapsingNoteHeader(
    notesCount: Int,
    searchQuery: String,
    isGridView: Boolean,
    listState: LazyListState,
    gridState: LazyStaggeredGridState,
    viewModel: NoteViewModel,
    onOpenSyncDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val collapseFraction by remember(isGridView, notesCount) {
        derivedStateOf {
            val threshold = 220f
            if (notesCount == 0) return@derivedStateOf 0f
            val rawOffset = if (isGridView) {
                gridState.firstVisibleItemIndex * 150f + gridState.firstVisibleItemScrollOffset.toFloat()
            } else {
                listState.firstVisibleItemIndex * 150f + listState.firstVisibleItemScrollOffset.toFloat()
            }
            (rawOffset / threshold).coerceIn(0f, 1f)
        }
    }
    val smoothCollapseFraction = collapseFraction * collapseFraction * (3f - 2f * collapseFraction)
    val headerContentHeight = (130 - 70 * smoothCollapseFraction).dp

    Box(
        modifier = modifier
            .background(Color(0xFFF8F9FA))
            .statusBarsPadding()
            .height(headerContentHeight)
    ) {
        val titleTopPadding = (16 - 2 * smoothCollapseFraction).dp
        val titleFontSize = (28 - 8 * smoothCollapseFraction).sp

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = titleTopPadding)
        ) {
            Text(
                text = "笔记",
                fontSize = titleFontSize,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1C1B1F),
                letterSpacing = (-0.5).sp
            )
            if (collapseFraction < 0.4f) {
                Text(
                    text = "$notesCount 个便签",
                    fontSize = 13.sp,
                    color = Color(0xFF74777F).copy(alpha = (1f - collapseFraction * 2.5f).coerceIn(0f, 1f)),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (collapseFraction < 0.5f) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 24.dp, top = (titleTopPadding - 4.dp).coerceAtLeast(0.dp))
                    .height(36.dp)
                    .graphicsLayer { alpha = (1f - smoothCollapseFraction * 2f).coerceIn(0f, 1f) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onOpenSyncDialog,
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFFEFF1F3), CircleShape)
                        .testTag("backup_dialog_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ImportExport,
                        contentDescription = "云同步",
                        tint = Color(0xFF44474E),
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = { viewModel.toggleLayout() },
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFFEFF1F3), CircleShape)
                        .testTag("layout_toggle_button")
                ) {
                    Icon(
                        imageVector = if (isGridView) Icons.Default.FormatListBulleted else Icons.Default.GridView,
                        contentDescription = "切换视图",
                        tint = Color(0xFF44474E),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (collapseFraction < 0.9f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
                    .graphicsLayer {
                        alpha = (1f - smoothCollapseFraction * 1.5f).coerceIn(0f, 1f)
                        translationY = -25f * smoothCollapseFraction
                    }
            ) {
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("search_input_field"),
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        color = Color(0xFF1C1B1F)
                    ),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFFEFF1F3), RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "搜索",
                                tint = Color(0xFF74777F),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "搜索便签内容或标题...",
                                        color = Color(0xFF74777F),
                                        fontSize = 15.sp
                                    )
                                }
                                innerTextField()
                            }
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { viewModel.setSearchQuery("") },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "清除",
                                        tint = Color(0xFF74777F),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

// 列表卡片：轻量预览，避免全文富文本解析与复杂行布局
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    note: NoteEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onToggleSticky: () -> Unit
) {
    val colorPack = MiNoteColors.getColor(note.colorId)
    val formattedDate = remember(note.modifyDate) { formatMiNoteDate(note.modifyDate) }
    var showQuickMenu by remember { mutableStateOf(false) }

    val preview = remember(note.id, note.snippet, note.title) {
        XiaomiRichTextParser.buildNotePreview(note.title, note.snippet, maxBodyLines = 3)
    }

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("note_item_card_${note.id}")
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showQuickMenu = true }
                ),
            shape = RoundedCornerShape(24.dp), // Large elegant rounded corner 'rounded-[24px]'
            colors = CardDefaults.cardColors(containerColor = colorPack.bg),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 0.dp // Clean minimalism: shadows completely removed
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp) // Generous padding for clean minimalist feel
            ) {
                // Header (Title + Sticky Pin icon)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = preview.displayTitle,
                        fontSize = 15.sp, // Matching exact text size of HTML card header
                        fontWeight = FontWeight.Medium,
                        color = colorPack.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (note.stickyTime > 0) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "置顶便签",
                            tint = Color(0xFFFF6700), // Elegant orange pin
                            modifier = Modifier
                                .size(16.dp)
                                .padding(start = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                if (preview.bodyText.isNotBlank()) {
                    Text(
                        text = preview.bodyText,
                        fontSize = 13.sp,
                        color = Color(0xFF44474E),
                        lineHeight = 18.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = "暂无附加内容",
                        fontSize = 13.sp,
                        color = Color(0xFF74777F).copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Footer timestamp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formattedDate,
                        fontSize = 10.sp, // Match 'text-[10px]'
                        color = Color(0xFF74777F), // Muted date timestamp color
                        fontWeight = FontWeight.Medium
                    )

                    // Compact tag signaling "sticky"
                    if (note.stickyTime > 0) {
                        Box(
                            modifier = Modifier
                                .background(colorPack.accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "置顶",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = colorPack.accent
                            )
                        }
                    }
                }
            }
        }

        // Tactile Quick Action Dropdown on Long Click
        DropdownMenu(
            expanded = showQuickMenu,
            onDismissRequest = { showQuickMenu = false },
            modifier = Modifier.background(Color.White)
        ) {
            DropdownMenuItem(
                text = { Text(if (note.stickyTime > 0) "取消置顶" else "置顶便签", color = Color(0xFF1C1B1F)) },
                leadingIcon = {
                    Icon(
                        imageVector = if (note.stickyTime > 0) Icons.Default.StarBorder else Icons.Default.Star,
                        contentDescription = "置顶",
                        tint = Color(0xFFFF6700)
                    )
                },
                onClick = {
                    onToggleSticky()
                    showQuickMenu = false
                }
            )

            DropdownMenuItem(
                text = { Text("删除便签", color = Color(0xFFE74C3C)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "删除",
                        tint = Color(0xFFE74C3C)
                    )
                },
                onClick = {
                    onDelete()
                    showQuickMenu = false
                }
            )
        }
    }
}

// Gorgeous High-Concentration Writing Screen with ruled-lines paper backdrop simulation
@Composable
fun NoteEditorScreen(
    note: NoteEntity,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onUpdateSnippetAndTitle: (String, String) -> Unit,
    onUpdateColor: (Int) -> Unit,
    onToggleSticky: () -> Unit,
    statusBarHeight: androidx.compose.ui.unit.Dp,
    navigationBarHeight: androidx.compose.ui.unit.Dp
) {
    val colorPack = MiNoteColors.getColor(note.colorId)
    val formattedDate = remember(note.modifyDate) { formatMiNoteDate(note.modifyDate) }

    // Prevent exiting the app, save note instead when backing out of the editor screen
    BackHandler(enabled = true) {
        onSave()
    }

    // Draft local memory to allow fluid, buttery typings and safe cancellation checks
    var titleDraft by remember(note.id) { mutableStateOf(note.title) }
    
    // Parse the XML into structured lines to represent true reactive checklists in the editor
    val linesState = remember(note.id) {
        val parsed = XiaomiRichTextParser.parse(note.snippet)
        val initialList = if (parsed.isEmpty()) {
            listOf(RichTextLine(isCheckbox = false, checked = false, text = ""))
        } else {
            parsed
        }
        mutableStateListOf(*initialList.toTypedArray())
    }

    var activeFocusId by remember { mutableStateOf<String?>(null) }
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var focusNewLineId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(focusNewLineId) {
        focusNewLineId?.let { id ->
            val requester = focusRequesters.getOrPut(id) { FocusRequester() }
            try {
                requester.requestFocus()
            } catch (e: Exception) {
                // ignore transient attachments
            }
            focusNewLineId = null
        }
    }

    val charCount = linesState.sumOf { it.text.length }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colorPack.bg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = statusBarHeight)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Header navigation
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = colorPack.text
                    )
                }

                // Header info
                Text(
                    text = if (note.title.isBlank() && note.snippet.isBlank()) "新建便签" else "编辑便签",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorPack.text
                )

                // Save button
                IconButton(
                    onClick = {
                        onUpdateSnippetAndTitle(XiaomiRichTextParser.toRichTextFromLines(linesState), titleDraft)
                        onSave()
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("save_note_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "保存",
                        tint = colorPack.accent,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = navigationBarHeight)
                    .background(colorPack.bg)
                    .border(BorderStroke(1.dp, colorPack.border.copy(alpha = 0.5f)))
                    .padding(vertical = 12.dp, horizontal = 20.dp)
            ) {
                // Quick Color palette selector row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "便签色彩",
                        fontSize = 12.sp,
                        color = colorPack.text.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Bold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MiNoteColors.colors.forEachIndexed { index, pack ->
                            val isSelected = note.colorId == index
                            val animatedPillBorderColor by animateColorAsState(
                                targetValue = if (isSelected) pack.accent else Color.Transparent,
                                label = "border"
                            )

                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(pack.bg)
                                    .border(
                                        BorderStroke(2.dp, animatedPillBorderColor),
                                        CircleShape
                                    )
                                    .clickable { onUpdateColor(index) }
                                    .testTag("color_pill_$index")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Actions tray holding Sticky toggler, deletion options
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Quick Toggle pin status
                        IconButton(
                            onClick = onToggleSticky,
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("sticky_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (note.stickyTime > 0) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = "置顶便签",
                                tint = if (note.stickyTime > 0) Color(0xFFF1C40F) else colorPack.text.copy(alpha = 0.4f)
                            )
                        }

                        // Share / copy content action
                        val clipboardManager = LocalClipboardManager.current
                        val context = LocalContext.current
                        IconButton(
                            onClick = {
                                val currentSnippetStr = XiaomiRichTextParser.toEditableText(XiaomiRichTextParser.toRichTextFromLines(linesState))
                                val fullShare = (if (titleDraft.isNotBlank()) "【$titleDraft】\n" else "") + currentSnippetStr
                                clipboardManager.setText(AnnotatedString(fullShare))
                                Toast.makeText(context, "便签内容已成功复制到剪贴板！", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "分享复制",
                                tint = colorPack.text.copy(alpha = 0.6f)
                            )
                        }

                        // Todo Checklist conversion support (Convert active line to checkbox or back)
                        IconButton(
                            onClick = {
                                if (linesState.isNotEmpty()) {
                                    val targetIndex = linesState.indexOfFirst { it.id == activeFocusId }.coerceAtLeast(0)
                                    val currentLine = linesState[targetIndex]
                                    linesState[targetIndex] = currentLine.copy(
                                        isCheckbox = !currentLine.isCheckbox,
                                        checked = false
                                    )
                                    onUpdateSnippetAndTitle(XiaomiRichTextParser.toRichTextFromLines(linesState), titleDraft)
                                }
                            },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckBox,
                                contentDescription = "插入待办",
                                tint = colorPack.text.copy(alpha = 0.6f)
                            )
                        }

                        // Right alignment support (Toggle <right> aligning for active line)
                        IconButton(
                            onClick = {
                                if (linesState.isNotEmpty()) {
                                    val targetIndex = linesState.indexOfFirst { it.id == activeFocusId }.coerceAtLeast(0)
                                    val currentLine = linesState[targetIndex]
                                    linesState[targetIndex] = currentLine.copy(
                                        isRightAligned = !currentLine.isRightAligned
                                    )
                                    onUpdateSnippetAndTitle(XiaomiRichTextParser.toRichTextFromLines(linesState), titleDraft)
                                }
                            },
                            modifier = Modifier.size(44.dp)
                        ) {
                            val activeIndex = if (linesState.isNotEmpty()) linesState.indexOfFirst { it.id == activeFocusId }.coerceAtLeast(0) else 0
                            val isRight = if (linesState.isNotEmpty() && activeIndex < linesState.size) linesState[activeIndex].isRightAligned else false
                            Icon(
                                imageVector = Icons.Default.FormatAlignRight,
                                contentDescription = "右对齐",
                                tint = if (isRight) Color(0xFFFF6700) else colorPack.text.copy(alpha = 0.6f)
                            )
                        }
                    }

                    // Delete active note
                    var deleteConfirmShow by remember { mutableStateOf(false) }
                    if (deleteConfirmShow) {
                        Button(
                            onClick = {
                                onDelete()
                                deleteConfirmShow = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE74C3C)),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("确认删除", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        IconButton(
                            onClick = { deleteConfirmShow = true },
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("delete_note_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "删除便签",
                                tint = Color(0xFFE74C3C)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        // Clean writing canvas (Ruled lines paper background canvas removed)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(colorPack.bg)
                .padding(horizontal = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Word count & update detail tag
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "最后修改于: $formattedDate",
                        fontSize = 11.sp,
                        color = colorPack.text.copy(alpha = 0.4f),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "共 $charCount 字",
                        fontSize = 11.sp,
                        color = colorPack.text.copy(alpha = 0.4f),
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Notebook Title Input Field
                BasicTextField(
                    value = titleDraft,
                    onValueChange = {
                        titleDraft = it
                        onUpdateSnippetAndTitle(XiaomiRichTextParser.toRichTextFromLines(linesState), it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_title_input"),
                    textStyle = TextStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorPack.text
                    ),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        if (titleDraft.isBlank()) {
                            Text(
                                text = "标题",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = colorPack.text.copy(alpha = 0.3f)
                            )
                        }
                        innerTextField()
                    }
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Notebook Content/Snippet input Field (Rendered as independent interactive checklist rows!)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            if (linesState.isNotEmpty()) {
                                val lastLineId = linesState.last().id
                                focusRequesters[lastLineId]?.requestFocus()
                            }
                        }
                ) {
                    linesState.forEachIndexed { index, line ->
                        key(line.id) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                if (line.isCheckbox) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(22.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .border(
                                                1.5.dp,
                                                MiNoteColors.ActionYellow,
                                                RoundedCornerShape(6.dp)
                                            )
                                            .background(
                                                if (line.checked) MiNoteColors.ActionYellow
                                                else Color.Transparent
                                            )
                                            .clickable {
                                                linesState[index] = line.copy(checked = !line.checked)
                                                onUpdateSnippetAndTitle(XiaomiRichTextParser.toRichTextFromLines(linesState), titleDraft)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (line.checked) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "已完成",
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                }

                                // The text field for this specific line
                                BasicTextField(
                                    value = line.text,
                                    onValueChange = { newText ->
                                        val hasNewline = newText.contains('\n')
                                        if (hasNewline) {
                                            // Split line at newline
                                            val parts = newText.split('\n')
                                            val firstPart = parts.getOrNull(0) ?: ""
                                            val secondPart = parts.getOrNull(1) ?: ""
                                            
                                            // Update active line with first part
                                            linesState[index] = line.copy(text = firstPart)
                                            
                                            // Create a new line that inherits current line's checkbox configuration
                                            val newLine = RichTextLine(
                                                isCheckbox = line.isCheckbox,
                                                checked = false,
                                                text = secondPart,
                                                isRightAligned = line.isRightAligned
                                            )
                                            linesState.add(index + 1, newLine)
                                            
                                            onUpdateSnippetAndTitle(XiaomiRichTextParser.toRichTextFromLines(linesState), titleDraft)
                                            
                                            // Auto-focus next line
                                            focusNewLineId = newLine.id
                                        } else {
                                            linesState[index] = line.copy(text = newText)
                                            onUpdateSnippetAndTitle(XiaomiRichTextParser.toRichTextFromLines(linesState), titleDraft)
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(focusRequesters.getOrPut(line.id) { FocusRequester() })
                                        .onFocusChanged { state ->
                                            if (state.isFocused) {
                                                activeFocusId = line.id
                                            }
                                        }
                                        .onKeyEvent { keyEvent ->
                                            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Backspace) {
                                                if (line.text.isEmpty()) {
                                                    if (line.isCheckbox) {
                                                        linesState[index] = line.copy(isCheckbox = false)
                                                        onUpdateSnippetAndTitle(XiaomiRichTextParser.toRichTextFromLines(linesState), titleDraft)
                                                        true
                                                    } else if (index > 0) {
                                                        val prevLine = linesState[index - 1]
                                                        linesState.removeAt(index)
                                                        focusNewLineId = prevLine.id
                                                        onUpdateSnippetAndTitle(XiaomiRichTextParser.toRichTextFromLines(linesState), titleDraft)
                                                        true
                                                    } else {
                                                        false
                                                    }
                                                } else {
                                                    false
                                                }
                                            } else {
                                                false
                                            }
                                        }
                                        .testTag("note_line_input_$index"),
                                    textStyle = TextStyle(
                                        fontSize = 16.sp,
                                        color = if (line.isCheckbox && line.checked) Color.Gray else colorPack.text,
                                        textDecoration = if (line.isCheckbox && line.checked) TextDecoration.LineThrough else TextDecoration.None,
                                        lineHeight = 24.sp,
                                        textAlign = if (line.isRightAligned) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start
                                    ),
                                    decorationBox = { innerTextField ->
                                        if (line.text.isEmpty() && linesState.size == 1) {
                                            Text(
                                                text = "写下您的备忘或便签内容吧...",
                                                fontSize = 16.sp,
                                                color = colorPack.text.copy(alpha = 0.25f)
                                            )
                                        }
                                        innerTextField()
                                    }
                                )

                                // Fast checklist line deletion button
                                if (line.isCheckbox) {
                                    IconButton(
                                        onClick = {
                                            if (line.text.isEmpty()) {
                                                if (linesState.size > 1) {
                                                    linesState.removeAt(index)
                                                    focusNewLineId = linesState[(index - 1).coerceAtLeast(0)].id
                                                } else {
                                                    linesState[index] = line.copy(isCheckbox = false)
                                                }
                                            } else {
                                                linesState[index] = line.copy(isCheckbox = false)
                                            }
                                            onUpdateSnippetAndTitle(XiaomiRichTextParser.toRichTextFromLines(linesState), titleDraft)
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "删除待办",
                                            tint = colorPack.text.copy(alpha = 0.3f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Dialog for cloud sync and local backup export
@Composable
fun CloudSyncDialog(
    viewModel: NoteViewModel,
    backupPassword: String,
    onBackupPasswordChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSaveFileExport: () -> Unit,
    onSelectFileImport: () -> Unit
) {
    var dialogTabId by remember { mutableStateOf(0) } // 0 = Cloud Sync, 1 = Backup
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 4.dp,
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFE1E3E8)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "同步与备份",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1C1B1F)
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFEFF1F3), RoundedCornerShape(12.dp))
                        .padding(4.dp)
                ) {
                    listOf("云同步", "备份").forEachIndexed { index, title ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (dialogTabId == index) Color(0xFFFF6700) else Color.Transparent)
                                .clickable { dialogTabId = index }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (dialogTabId == index) Color.White else Color(0xFF74777F)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (dialogTabId == 1) {
                    Text(
                        text = "本地数据库已加密存储。导出备份为 AES 加密文件（.bin），换机导入时需输入相同密码。同 ID 便签会被覆盖更新。",
                        fontSize = 13.sp,
                        color = Color(0xFF44474E),
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = backupPassword,
                        onValueChange = onBackupPasswordChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("备份密码") },
                        placeholder = { Text("至少 6 位，导出与导入须一致") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFF6700),
                            unfocusedBorderColor = Color(0xFFE1E3E8),
                            focusedContainerColor = Color(0xFFF8F9FA),
                            unfocusedContainerColor = Color(0xFFF8F9FA)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onSaveFileExport,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF44474E), contentColor = Color.White),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.DownloadForOffline, contentDescription = "导出", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("导出", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onSelectFileImport,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6700), contentColor = Color.White),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("backup_import_button"),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = "导入", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("一键导入", fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1C1B1F)),
                        border = BorderStroke(1.dp, Color(0xFFE1E3E8)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("关闭")
                    }
                } else {
                    val prefs = remember(context) { context.getSharedPreferences("mi_sync_prefs", android.content.Context.MODE_PRIVATE) }
                    var cookieText by remember { mutableStateOf(prefs.getString("pasted_cookie", "") ?: "") }
                    val syncState by viewModel.syncState.collectAsStateWithLifecycle()

                    Text(
                        text = "粘贴浏览器登录 i.mi.com 后抓包得到的 Cookie，即可自动拉取云端便签列表并补全每条便签的完整内容。",
                        fontSize = 13.sp,
                        color = Color(0xFF44474E),
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "💡 获取方式：登录 i.mi.com 并进入便签，按 F12 打开开发者工具，在 Network 中找到 note/ 开头的请求，复制 Request Headers 中的完整 Cookie。",
                        fontSize = 11.sp,
                        color = Color(0xFF74777F),
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = cookieText,
                        onValueChange = { newValue ->
                            cookieText = newValue
                            prefs.edit().putString("pasted_cookie", newValue).apply()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        placeholder = { Text("在此粘贴登录后的 Cookie 并开始同步...", fontSize = 12.sp) },
                        textStyle = TextStyle(fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFF6700),
                            unfocusedBorderColor = Color(0xFFE1E3E8),
                            focusedContainerColor = Color(0xFFF8F9FA),
                            unfocusedContainerColor = Color(0xFFF8F9FA)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        enabled = syncState is NoteViewModel.SyncState.Idle
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    when (val state = syncState) {
                        is NoteViewModel.SyncState.Idle -> {
                            Button(
                                onClick = { viewModel.startCloudSync(cookieText) },
                                enabled = cookieText.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6700), contentColor = Color.White),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = "开始同步", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("开始同步全部便签", fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedButton(
                                onClick = { onDismiss() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1C1B1F)),
                                border = BorderStroke(1.dp, Color(0xFFE1E3E8)),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("关闭")
                            }
                        }
                        is NoteViewModel.SyncState.Syncing -> {
                            val isFetchingList = state.phase == NoteViewModel.SyncPhase.FETCHING_LIST
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFECE0).copy(alpha = 0.5f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp)
                                ) {
                                    Text(
                                        text = if (isFetchingList) "正在获取云端便签列表..." else "正在同步便签详情 (请勿关闭窗口)...",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF6700)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    if (isFetchingList) {
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = Color(0xFFFF6700),
                                            trackColor = Color(0xFFE1E3E8)
                                        )
                                    } else {
                                        val progress = if (state.totalCount > 0) {
                                            state.currentProgress.toFloat() / state.totalCount.toFloat()
                                        } else 0f
                                        LinearProgressIndicator(
                                            progress = progress,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = Color(0xFFFF6700),
                                            trackColor = Color(0xFFE1E3E8)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "列表已拉取 ${state.listFetchedCount} 条 · 详情 ${state.currentProgress} / ${state.totalCount}",
                                            fontSize = 12.sp,
                                            color = Color(0xFF1C1B1F)
                                        )
                                        Text(
                                            text = "正在处理: ${state.currentNoteTitle}",
                                            fontSize = 11.sp,
                                            color = Color(0xFF74777F),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "详情成功: ${state.successCount} | 失败: ${state.failureCount}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF44474E)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = { viewModel.cancelCloudSync() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("停止同步并取消", fontWeight = FontWeight.Bold)
                            }
                        }
                        is NoteViewModel.SyncState.Success -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFD4EDDA))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = "成功", tint = Color(0xFF28A745), modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "一键同步完成！",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF155724)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "列表同步: ${state.listCount} 条便签",
                                        fontSize = 13.sp,
                                        color = Color(0xFF155724)
                                    )
                                    Text(
                                        text = "详情成功: ${state.detailSuccessCount} · 失败: ${state.detailFailureCount}",
                                        fontSize = 12.sp,
                                        color = Color(0xFF6C757D)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    viewModel.resetSyncState()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6700), contentColor = Color.White),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("完成并返回", fontWeight = FontWeight.Bold)
                            }
                        }
                        is NoteViewModel.SyncState.Error -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8D7DA))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Error, contentDescription = "错误", tint = Color(0xFFDC3545), modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "同步出错",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF721C24)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = state.message,
                                        fontSize = 13.sp,
                                        color = Color(0xFF721C24)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    viewModel.resetSyncState()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6700), contentColor = Color.White),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("重试", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Custom Date Formatter targeting cozy Mi Notes aesthetic timing layouts
fun formatMiNoteDate(timestamp: Long): String {
    val date = Date(timestamp)
    val now = Date()
    val sdfToday = SimpleDateFormat("HH:mm", Locale.getDefault())
    val sdfThisYear = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
    val sdfFull = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    val diff = now.time - timestamp
    val oneDayMs = 24 * 60 * 60 * 1000L

    return when {
        diff in 0 until oneDayMs && date.date == now.date -> "今天 ${sdfToday.format(date)}"
        diff in oneDayMs until (2 * oneDayMs) -> "昨天 ${sdfToday.format(date)}"
        else -> {
            val yearSdf = SimpleDateFormat("yyyy", Locale.getDefault())
            if (yearSdf.format(date) == yearSdf.format(now)) {
                sdfThisYear.format(date)
            } else {
                sdfFull.format(date)
            }
        }
    }
}
