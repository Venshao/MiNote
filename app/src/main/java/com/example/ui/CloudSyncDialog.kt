package com.example.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF44474E),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(
                                Icons.Default.DownloadForOffline,
                                contentDescription = "导出",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("导出", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onSelectFileImport,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF6700),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("backup_import_button"),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(
                                Icons.Default.UploadFile,
                                contentDescription = "导入",
                                modifier = Modifier.size(18.dp)
                            )
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
                    var cookieText by remember { mutableStateOf("") }
                    var showPasteCookie by remember { mutableStateOf(false) }
                    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
                    val context = LocalContext.current

                    val loginLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        if (result.resultCode != android.app.Activity.RESULT_OK) {
                            return@rememberLauncherForActivityResult
                        }
                        val cookie = result.data
                            ?.getStringExtra(MiCloudLoginActivity.EXTRA_COOKIE)
                            ?.trim()
                            .orEmpty()
                        if (cookie.isBlank()) {
                            Toast.makeText(context, "未获取到 Cookie", Toast.LENGTH_SHORT).show()
                            return@rememberLauncherForActivityResult
                        }
                        cookieText = cookie
                        viewModel.startCloudSync(cookie)
                    }

                    Text(
                        text = "登录小米账号后即可同步云端便签到本地。",
                        fontSize = 13.sp,
                        color = Color(0xFF44474E),
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    when (val state = syncState) {
                        is NoteViewModel.SyncState.Idle -> {
                            Button(
                                onClick = {
                                    loginLauncher.launch(MiCloudLoginActivity.createIntent(context))
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF6700),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(
                                    Icons.Default.CloudDownload,
                                    contentDescription = "登录并同步",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("登录小米账号并同步", fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            TextButton(
                                onClick = { showPasteCookie = !showPasteCookie },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (showPasteCookie) {
                                        "收起手动粘贴 Cookie"
                                    } else {
                                        "备用：手动粘贴 Cookie"
                                    },
                                    fontSize = 12.sp,
                                    color = Color(0xFF74777F)
                                )
                            }

                            if (showPasteCookie) {
                                Text(
                                    text = "登录 i.mi.com，F12 → Network → 任意请求 → Request Headers 里复制 Cookie。",
                                    fontSize = 11.sp,
                                    color = Color(0xFF74777F),
                                    lineHeight = 16.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = cookieText,
                                    onValueChange = { cookieText = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(80.dp),
                                    placeholder = { Text("粘贴 Cookie…", fontSize = 12.sp) },
                                    textStyle = TextStyle(
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFFFF6700),
                                        unfocusedBorderColor = Color(0xFFE1E3E8),
                                        focusedContainerColor = Color(0xFFF8F9FA),
                                        unfocusedContainerColor = Color(0xFFF8F9FA)
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { viewModel.startCloudSync(cookieText) },
                                    enabled = cookieText.isNotBlank(),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFFFF6700)
                                    ),
                                    border = BorderStroke(1.dp, Color(0xFFFF6700)),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text("用粘贴的 Cookie 同步", fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedButton(
                                onClick = { onDismiss() },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFF1C1B1F)
                                ),
                                border = BorderStroke(1.dp, Color(0xFFE1E3E8)),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("关闭")
                            }
                        }

                        is NoteViewModel.SyncState.Syncing -> {
                            val isFetchingList =
                                state.phase == NoteViewModel.SyncPhase.FETCHING_LIST
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFFFECE0).copy(alpha = 0.5f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp)
                                ) {
                                    Text(
                                        text = if (isFetchingList) {
                                            "正在同步便签…"
                                        } else {
                                            "正在下载便签内容…"
                                        },
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
                                            state.currentProgress.toFloat() /
                                                state.totalCount.toFloat()
                                        } else {
                                            0f
                                        }
                                        LinearProgressIndicator(
                                            progress = { progress },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = Color(0xFFFF6700),
                                            trackColor = Color(0xFFE1E3E8)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "已完成 ${state.currentProgress} / ${state.totalCount}",
                                            fontSize = 12.sp,
                                            color = Color(0xFF1C1B1F)
                                        )
                                        Text(
                                            text = state.currentNoteTitle,
                                            fontSize = 11.sp,
                                            color = Color(0xFF74777F),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = { viewModel.cancelCloudSync() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Red,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("停止同步", fontWeight = FontWeight.Bold)
                            }
                        }

                        is NoteViewModel.SyncState.Success -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFD4EDDA)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "成功",
                                            tint = Color(0xFF28A745),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "同步完成",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF155724)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "共 ${state.listCount} 条便签" +
                                            if (state.detailFailureCount > 0) {
                                                "，${state.detailFailureCount} 条未完成"
                                            } else {
                                                ""
                                            },
                                        fontSize = 13.sp,
                                        color = Color(0xFF155724)
                                    )
                                    if (state.remainingCount > 0) {
                                        Text(
                                            text = "还有 ${state.remainingCount} 条可稍后继续同步",
                                            fontSize = 12.sp,
                                            color = Color(0xFF6C757D)
                                        )
                                    }
                                    if (state.cookieExpired) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "登录已过期，请重新登录后再同步。",
                                            fontSize = 12.sp,
                                            color = Color(0xFF856404)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = { viewModel.resetSyncState() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF6700),
                                    contentColor = Color.White
                                ),
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
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFF8D7DA)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Error,
                                            contentDescription = "错误",
                                            tint = Color(0xFFDC3545),
                                            modifier = Modifier.size(20.dp)
                                        )
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
                                onClick = { viewModel.resetSyncState() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF6700),
                                    contentColor = Color.White
                                ),
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
