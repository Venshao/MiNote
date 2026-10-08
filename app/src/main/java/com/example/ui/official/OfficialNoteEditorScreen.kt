package com.example.ui.official

import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.NoteEntity
import com.example.data.NoteImageCache
import com.example.data.XiaomiRichTextParser
import com.example.ui.NoteViewModel
import com.example.ui.theme.MiNoteColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 对齐小米便签官方编辑体验：直接嵌入官方 noteeditor WebView，不重做 Compose 富文本。
 */
@Composable
fun OfficialNoteEditorScreen(
    note: NoteEntity,
    viewModel: NoteViewModel,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onUpdateSnippetAndTitle: (String, String) -> Unit,
    onUpdateColor: (Int) -> Unit,
    onToggleSticky: () -> Unit,
    statusBarHeight: Dp,
    navigationBarHeight: Dp
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colorPack = MiNoteColors.getColor(note.colorId)

    var titleDraft by remember(note.id) { mutableStateOf(note.title) }
    var snippetDraft by remember(note.id) { mutableStateOf(note.snippet) }
    var editorReady by remember { mutableStateOf(false) }
    var insertingImage by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun persistAndSave() {
        requestOfficialEditorSave(webView)
        onUpdateSnippetAndTitle(snippetDraft, titleDraft)
        onSave()
    }

    BackHandler(enabled = true) { persistAndSave() }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        insertingImage = true
        scope.launch {
            val fileId = withContext(Dispatchers.IO) {
                NoteImageCache.importFromUri(context, uri)
            }
            insertingImage = false
            if (fileId == null) {
                Toast.makeText(context, "图片导入失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val wv = webView
            if (wv != null) {
                val json = JSONObject.quote(fileId)
                wv.evaluateJavascript(
                    "(function(){try{if(typeof insertImageEnd==='function'){insertImageEnd([$json]);}else if(typeof insertImageWithPos==='function'){insertImageWithPos([$json]);}}catch(e){}})()",
                    null
                )
            }
            snippetDraft = XiaomiRichTextParser.appendImageTag(snippetDraft, fileId)
            onUpdateSnippetAndTitle(snippetDraft, titleDraft)
            viewModel.notifyImageCacheChanged()
            Toast.makeText(context, "已插入图片", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colorPack.bg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = statusBarHeight)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        requestOfficialEditorSave(webView)
                        onUpdateSnippetAndTitle(snippetDraft, titleDraft)
                        onDismiss()
                    },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = colorPack.text
                    )
                }

                Text(
                    text = if (note.title.isBlank() && note.snippet.isBlank()) "新建便签" else "编辑便签",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colorPack.text
                )

                IconButton(
                    onClick = { persistAndSave() },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("save_note_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "保存",
                        tint = MiNoteColors.ActionYellow,
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
                    .border(BorderStroke(1.dp, colorPack.border.copy(alpha = 0.45f)))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MiNoteColors.colors.forEachIndexed { index, pack ->
                            val selected = note.colorId == index
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(pack.bg)
                                    .border(
                                        BorderStroke(
                                            2.dp,
                                            if (selected) MiNoteColors.ActionYellow else Color.Transparent
                                        ),
                                        CircleShape
                                    )
                                    .clickable { onUpdateColor(index) }
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                pickImageLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !insertingImage
                        ) {
                            if (insertingImage) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MiNoteColors.ActionYellow
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = "插入图片",
                                    tint = colorPack.text.copy(alpha = 0.7f)
                                )
                            }
                        }
                        IconButton(onClick = onToggleSticky) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = "置顶",
                                tint = if (note.stickyTime > 0) {
                                    MiNoteColors.ActionYellow
                                } else {
                                    colorPack.text.copy(alpha = 0.45f)
                                }
                            )
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = Color(0xFFE74C3C).copy(alpha = 0.85f)
                            )
                        }
                    }
                }
                Box(modifier = Modifier.height(4.dp))
                Text(
                    text = if (editorReady) "官方编辑器已就绪" else "正在加载官方编辑器…",
                    fontSize = 11.sp,
                    color = colorPack.text.copy(alpha = 0.35f)
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(colorPack.bg)
        ) {
            OfficialWebNoteEditor(
                title = titleDraft,
                richText = snippetDraft,
                themeId = note.colorId,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(0.dp))
                    .testTag("official_web_editor"),
                onTitleChanged = { t ->
                    titleDraft = t
                    onUpdateSnippetAndTitle(snippetDraft, t)
                },
                onRichTextChanged = { body ->
                    snippetDraft = body
                    onUpdateSnippetAndTitle(body, titleDraft)
                },
                onReadyChanged = { editorReady = it },
                onWebViewCreated = { webView = it }
            )

            if (!editorReady) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colorPack.bg.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MiNoteColors.ActionYellow)
                        Box(modifier = Modifier.height(12.dp))
                        Text(
                            text = "加载小米官方编辑器…",
                            color = colorPack.text.copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
