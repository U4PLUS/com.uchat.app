@file:OptIn(ExperimentalMaterial3Api::class)

package com.uchat.app.ui

import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import javax.net.ssl.HttpsURLConnection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.uchat.app.data.ChatMessage
import com.uchat.app.data.CodeBlock
import com.uchat.app.data.MemoryEntry
import com.uchat.app.data.ModelConfig
import com.uchat.app.data.SendAttachment
import com.uchat.app.data.SshConfig
import com.uchat.app.data.TlsConfig
import com.uchat.app.data.ToolCallData
import com.uchat.app.data.extractMarkdownBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.uchat.app.data.parseMarkdown
import com.uchat.app.data.parseMarkdownSegments
import com.uchat.app.viewmodel.ChatUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF5F6368),
    onSecondary = Color.White,
    surface = Color.White,
    onSurface = Color(0xFF202124),
    surfaceVariant = Color(0xFFF1F3F4),
    onSurfaceVariant = Color(0xFF5F6368),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF202124),
    error = Color(0xFFD93025),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF003A70),
    primaryContainer = Color(0xFF004881),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF9AA0A6),
    onSecondary = Color.White,
    surface = Color(0xFF1F1F1F),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = Color(0xFF9AA0A6),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE8EAED),
    error = Color(0xFFF28B82),
    onError = Color(0xFF601410),
)

/** 强调色板：(浅色 primary, 深色 primary) */
private fun accentPalette(name: String): Pair<Long, Long> = when (name) {
    "green" -> 0xFF188038L to 0xFF81C995L
    "purple" -> 0xFF9334E6L to 0xFFC58AF9L
    "orange" -> 0xFFF29900L to 0xFFF9AB00L
    "teal" -> 0xFF00897BL to 0xFF80CBC4L
    "pink" -> 0xFFE91E63L to 0xFFF48FB1L
    else -> 0xFF1A73E8L to 0xFF8AB4F8L
}

/** 依据 亮/暗 + 强调色 构建动态色板 */
private fun buildColors(dark: Boolean, accent: String): ColorScheme {
    val (lp, dp) = accentPalette(accent)
    val lightP = Color(lp); val darkP = Color(dp)
    return if (dark) DarkColors.copy(
        primary = darkP, onPrimary = Color.White,
        primaryContainer = Color(lp).copy(alpha = 0.35f), onPrimaryContainer = Color(0xFFD3E3FD)
    ) else LightColors.copy(
        primary = lightP, onPrimary = Color.White,
        primaryContainer = Color(lp).copy(alpha = 0.18f), onPrimaryContainer = Color(0xFF041E49)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIChatApp(uiState: ChatUiState, onIntent: (UiIntent) -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val darkNow = remember(uiState.config.themeMode, systemDark) {
        when (uiState.config.themeMode) {
            "light" -> false
            "dark" -> true
            else -> systemDark
        }
    }
    val colors = remember(uiState.config.themeMode, uiState.config.accentColor, darkNow) {
        buildColors(darkNow, uiState.config.accentColor)
    }
    MaterialTheme(colorScheme = colors) {
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(uiState.toastMessage) {
            uiState.toastMessage?.let { msg ->
                if (msg.startsWith("export:")) {
                    snackbarHostState.showSnackbar("导出内容已复制到剪贴板")
                } else {
                    snackbarHostState.showSnackbar(msg)
                }
                onIntent(UiIntent.ClearToast)
            }
        }

        if (uiState.toastMessage?.startsWith("export:") == true) {
            val exportContent = uiState.toastMessage!!.removePrefix("export:")
            ExportShareSheet(content = exportContent, onDismiss = { onIntent(UiIntent.ClearToast) })
        }

        // Agent 模式：高风险工具授权请求
        uiState.pendingApproval?.let { p ->
            AlertDialog(
                onDismissRequest = { onIntent(UiIntent.ApproveTool(false)) },
                title = { Text("工具授权请求") },
                text = {
                    Column {
                        Text("模型请求调用工具「${p.callName}」（危险等级 L${p.dangerLevel}）", fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(p.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p.arguments.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            ) {
                                Text(
                                    "参数：${p.arguments}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(8.dp),
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("允许后模型可执行该工具；拒绝将中止当前调用。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onIntent(UiIntent.ApproveTool(true)) }) {
                        Text("允许", color = MaterialTheme.colorScheme.primary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onIntent(UiIntent.ApproveTool(false)) }) { Text("拒绝") }
                }
            )
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background
        ) { pad ->
            Box(modifier = Modifier.fillMaxSize().padding(pad)) {
                if (uiState.showSettings) {
                    SettingsScreen(uiState, onIntent)
                } else {
                    ChatHome(uiState, onIntent)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Home：Drawer（对话列表 + 设置入口）+ 聊天页
// ─────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHome(uiState: ChatUiState, onIntent: (UiIntent) -> Unit) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // 左抽屉：窄幅 + 右侧描边分界（与主界面背景区分）
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
                    )
                    .clip(RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp))
            ) {
                DrawerContent(
                    uiState = uiState,
                    onIntent = onIntent,
                    onClose = { scope.launch { drawerState.close() } }
                )
            }
        }
    ) {
        ChatScreen(
            uiState = uiState,
            onIntent = onIntent,
            onOpenMenu = { scope.launch { drawerState.open() } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// Drawer 内容：对话列表 + 新建 + 设置入口
// ─────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrawerContent(uiState: ChatUiState, onIntent: (UiIntent) -> Unit, onClose: () -> Unit) {
    // 过滤空对话：只有真正聊过的会话才显示（避免历史垃圾空对话堆积）
    val sortedChats = uiState.chatList.filter { it.second.messages.isNotEmpty() }
        .sortedByDescending { it.second.lastUpdated }
    var renameTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 长按重命名对话框
    renameTarget?.let { (chatId, currentTitle) ->
        var titleInput by remember(chatId) { mutableStateOf(currentTitle) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    modifier = Modifier.fillMaxWidth().clipToBounds(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget = null
                    onIntent(UiIntent.UpdateChatTitle(chatId, titleInput))
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // 头部：品牌 + 新建对话
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Chat Client", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { onIntent(UiIntent.NewChat); onClose() }) {
                Icon(Icons.Default.Add, contentDescription = "新建对话", modifier = Modifier.size(22.dp))
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))

        if (sortedChats.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("还没有对话", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(sortedChats, key = { it.first }) { (chatId, chat) ->
                    DrawerChatItem(
                        chat = chat,
                        selected = chatId == uiState.currentChatId,
                        onClick = { onIntent(UiIntent.SelectChat(chatId)); onClose() },
                        onDelete = { onIntent(UiIntent.DeleteChat(chatId)) },
                        onRename = { renameTarget = chatId to chat.title }
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        // 底部：设置入口
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { onIntent(UiIntent.ShowSettings); onClose() },
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(12.dp))
                Text("设置", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerChatItem(
    chat: com.uchat.app.data.Chat,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除对话") },
            text = { Text("确定删除「${chat.title}」吗？此操作不可恢复。") },
            confirmButton = { TextButton(onClick = { showDeleteConfirm = false; onDelete() }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(10.dp)
            )
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onRename),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(chat.title, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${chat.messages.size} 条 · ${dateFormat.format(java.util.Date(chat.lastUpdated))}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun ExportShareSheet(content: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出聊天记录") },
        text = {
            Column {
                Text("以下内容已复制到剪贴板，可直接粘贴分享：", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        content.take(500) + if (content.length > 500) "..." else "",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setText(AnnotatedString(content))
                onDismiss()
            }) { Text("复制") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

// ─────────────────────────────────────────────────────────────────
// ─────────────────────────────────────────────────────────────────
// Chat Screen
// ─────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onIntent: (UiIntent) -> Unit,
    onOpenMenu: () -> Unit = {}
) {
    val activeModel = uiState.config.models.find { it.id == uiState.config.activeModelId }?.name
        ?: (if (uiState.config.models.isEmpty()) "选择模型" else "AI 聊天")
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var showMenuMsg by remember { mutableStateOf<ChatMessage?>(null) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }

    // 附件选择（SAF，无需权限）
    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { readImageAttachment(context, it)?.let { a -> onIntent(UiIntent.AddAttachment(a)) } }
    }
    val pickFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { readTextAttachment(context, it)?.let { a -> onIntent(UiIntent.AddAttachment(a)) } }
    }

    // Title edit dialog — 由 editingTitle 状态驱动（修复异步加载标题时的时序 bug）
    if (uiState.editingTitle != null) {
        var titleInput by remember(uiState.editingTitle) { mutableStateOf(uiState.editingTitle ?: "") }
        AlertDialog(
            onDismissRequest = { onIntent(UiIntent.CancelEditTitle) },
            title = { Text("修改对话标题") },
            text = {
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    modifier = Modifier.fillMaxWidth().clipToBounds(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    uiState.currentChatId?.let { onIntent(UiIntent.UpdateChatTitle(it, titleInput)) }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { onIntent(UiIntent.CancelEditTitle) }) { Text("取消") } }
        )
    }

    // Message action menu
    showMenuMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { showMenuMsg = null },
            title = { Text("消息操作", fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (msg.role == "user") {
                        ListItem(
                            headlineContent = { Text("编辑并重发") },
                            leadingContent = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp)) },
                            modifier = Modifier.clickable {
                                showMenuMsg = null
                                input = msg.content
                                onIntent(UiIntent.TruncateFrom(msg.id))
                            }
                        )
                        ListItem(
                            headlineContent = { Text("发送消息") },
                            leadingContent = { Icon(Icons.Default.Send, null, modifier = Modifier.size(20.dp)) },
                            modifier = Modifier.clickable { showMenuMsg = null; input = msg.content }
                        )
                    } else {
                        ListItem(
                            headlineContent = { Text("重新生成") },
                            leadingContent = { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(20.dp)) },
                            modifier = Modifier.clickable { showMenuMsg = null; onIntent(UiIntent.Regenerate) }
                        )
                    }
                    ListItem(
                        headlineContent = { Text("复制内容") },
                        leadingContent = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.clickable {
                            clipboardManager.setText(AnnotatedString(msg.content))
                            showMenuMsg = null
                        }
                    )
                    ListItem(
                        headlineContent = { Text("分享") },
                        leadingContent = { Icon(Icons.Default.Share, null, modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.clickable {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, msg.content)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "分享消息"))
                            showMenuMsg = null
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMenuMsg = null }) { Text("关闭") } }
        )
    }

    LaunchedEffect(uiState.messages.size, uiState.streamingContent?.length, uiState.generationError) {
        // 消息或流式内容变化时滚动到底部（含流式气泡位置）
        if (uiState.messages.isNotEmpty() || uiState.streamingContent != null) {
            val target = uiState.messages.size.coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }

    BackHandler {
        when {
            uiState.editingModel != null -> onIntent(UiIntent.HideSettings)
            uiState.showSettings -> onIntent(UiIntent.HideSettings)
            else -> { /* 留在当前对话 */ }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showModelMenu = true }.padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(
                                activeModel,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                            if (uiState.config.models.isEmpty()) {
                                DropdownMenuItem(text = { Text("还没有模型", fontSize = 13.sp) }, onClick = {})
                            } else {
                                uiState.config.models.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m.name, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        onClick = { showModelMenu = false; onIntent(UiIntent.SetActiveModel(m.id)) },
                                        leadingIcon = {
                                            if (m.id == uiState.config.activeModelId) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                                        }
                                    )
                                }
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("添加 / 管理模型") },
                                onClick = { showModelMenu = false; onIntent(UiIntent.ShowSettings) },
                                leadingIcon = { Icon(Icons.Default.Settings, null) }
                            )
                        }
                    }
                },
                windowInsets = WindowInsets(0.dp),
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Menu, contentDescription = "对话列表", modifier = Modifier.size(20.dp))
                    }
                },
                actions = {
                    // Regenerate
                    if (uiState.messages.isNotEmpty() && !uiState.isLoading) {
                        IconButton(onClick = { onIntent(UiIntent.Regenerate) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "重新生成", modifier = Modifier.size(20.dp))
                        }
                    }
                    // Clear
                    if (uiState.messages.isNotEmpty()) {
                        IconButton(onClick = { onIntent(UiIntent.ClearChat) }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "清空对话", modifier = Modifier.size(20.dp))
                        }
                    }
                    // More options
                    var showMore by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMore = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(
                                text = { Text("修改标题") },
                                onClick = { showMore = false; onIntent(UiIntent.StartEditTitle) },
                                leadingIcon = { Icon(Icons.Default.Edit, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("导出聊天") },
                                onClick = { showMore = false; onIntent(UiIntent.ExportChats) },
                                leadingIcon = { Icon(Icons.Default.Download, null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = { showMore = false; onIntent(UiIntent.ShowSettings) },
                                leadingIcon = { Icon(Icons.Default.Settings, null) }
                            )
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad)) {
            // 附件预览条（输入框上方）
            if (uiState.attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    uiState.attachments.forEachIndexed { idx, att ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
                            ) {
                                Icon(
                                    if (att.kind == "image") Icons.Default.Image else Icons.Default.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(att.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { onIntent(UiIntent.RemoveAttachment(idx)) }, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "移除", modifier = Modifier.size(13.dp))
                                }
                            }
                        }
                    }
                }
            }

            // 按"工作回合"分组：assistant(含工具调用) 与跟随的 tool 结果合并为一张工作卡片（DSH 风格）
            val msgGroups = remember(uiState.messages) { buildMsgGroups(uiState.messages) }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.messages.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.AutoMirrored.Filled.Chat, null, modifier = Modifier.size(46.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                                Spacer(modifier = Modifier.height(14.dp))
                                Text("Chat Client", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                                Spacer(modifier = Modifier.height(6.dp))
                                if (uiState.config.models.isEmpty()) {
                                    Text("还没有可用模型", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 14.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("点右上角 ⋮ → 设置 → 添加模型", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), fontSize = 12.sp)
                                } else {
                                    Text("输入消息，发送后自动开始新对话", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 13.5.sp)
                                }
                            }
                        }
                    }
                }

                items(msgGroups, key = { it.key }) { g ->
                    val callMsg = g.callMsg
                    val plain = g.msg
                    when {
                        callMsg != null -> ToolWorkCard(
                            callMsg = callMsg,
                            results = g.results,
                            isDark = uiState.config.darkMode,
                            fontSp = uiState.config.messageFontSize.sp
                        )
                        plain?.role == "tool" -> ToolResultCard(plain)
                        plain?.role == "system" -> SummaryBlock(plain)
                        plain != null -> MessageBubble(
                            msg = plain,
                            isDark = uiState.config.darkMode,
                            fontSp = uiState.config.messageFontSize.sp,
                            onLongClick = { showMenuMsg = plain }
                        )
                    }
                    // send_file 文件卡片：独立显示在消息末尾（AI 正文之后；无正文时在工具卡片之后）
                    g.files.forEach { (fName, fUrl) ->
                        FileCard(name = fName, url = fUrl)
                    }
                }

                // 工具调用进行中（Agent 模式）
                if (uiState.toolActivity != null) {
                    item(key = "toolActivity") {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (uiState.toolActivity.status == "running") {
                                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        if (uiState.toolActivity.status == "error") Icons.Default.Error else Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                        tint = if (uiState.toolActivity.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        if (uiState.toolActivity.status == "running") "正在调用工具 ${uiState.toolActivity.name}..." else "工具 ${uiState.toolActivity.name} 已返回",
                                        fontSize = 12.5.sp, fontWeight = FontWeight.Medium
                                    )
                                    if (uiState.toolActivity.status != "running" && uiState.toolActivity.detail.isNotBlank()) {
                                        Text(uiState.toolActivity.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }

                // 自动重试中（网络/5xx）：显示错误消息与重试进度
                if (uiState.retryInfo != null) {
                    item(key = "retryInfo") {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "请求失败：${uiState.retryInfo.message.take(80)}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        "5 秒后自动重试（第 ${uiState.retryInfo.attempt}/${uiState.retryInfo.maxAttempts} 次）",
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }

                // 流式输出中的实时气泡（打字机效果）
                if (uiState.streamingContent != null) {
                    item(key = "streaming") {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            StreamingBubble(
                                content = uiState.streamingContent.orEmpty(),
                                reasoning = uiState.streamingReasoning.orEmpty(),
                                isDark = uiState.config.darkMode,
                                fontSp = uiState.config.messageFontSize.sp,
                                onStop = { onIntent(UiIntent.StopGenerating) }
                            )
                        }
                    }
                }

                // AI 自主续写中（JSON 协议 done=false，AI 要求继续说）
                if (uiState.autoContinuing) {
                    item(key = "autoContinuing") {
                        Text(
                            "AI 还没说完，继续回复中…",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, bottom = 4.dp)
                        )
                    }
                }

                if (uiState.isLoading && uiState.streamingContent == null) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(32.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("AI 正在思考...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // 生成失败：保留现场，显示报错与重试
                if (uiState.generationError != null) {
                    item(key = "genError") {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Error, contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("连接中断，已保留当前进度", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                    Text(uiState.generationError.orEmpty(), fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { onIntent(UiIntent.Regenerate) }) {
                                    Text("重试", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Input area
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 附件按钮：图片 / 文件
                    Box {
                        IconButton(onClick = { showAttachMenu = true }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Add, contentDescription = "添加附件", modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("图片", fontSize = 13.5.sp) },
                                onClick = { showAttachMenu = false; pickImageLauncher.launch("image/*") },
                                leadingIcon = { Icon(Icons.Default.Image, null, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text("文件（文本/PDF/JSON）", fontSize = 13.5.sp) },
                                onClick = {
                                    showAttachMenu = false
                                    pickFileLauncher.launch(arrayOf("text/*", "application/json", "application/pdf", "application/xml", "text/csv"))
                                },
                                leadingIcon = { Icon(Icons.Default.Description, null, modifier = Modifier.size(18.dp)) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f).heightIn(max = 120.dp).clipToBounds(),
                        placeholder = { Text("输入消息...", fontSize = 14.sp) },
                                    shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (input.isNotBlank() && !uiState.isLoading) {
                                onIntent(UiIntent.SendMessage(input))
                                input = ""
                            }
                        })
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            if (input.isNotBlank() && !uiState.isLoading) {
                                onIntent(UiIntent.SendMessage(input))
                                input = ""
                            }
                        },
                        modifier = Modifier.size(44.dp),
                        enabled = input.isNotBlank() && !uiState.isLoading,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: ChatMessage,
    isDark: Boolean,
    fontSp: TextUnit,
    onLongClick: () -> Unit
) {
    val isUser = msg.role == "user"
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp).combinedClickable(
                onClick = {},
                onLongClick = onLongClick
            ),
            shape = RoundedCornerShape(if (isUser) 16.dp else 4.dp),
            color = bubbleColor
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 思考过程（reasoning）折叠块
                if (!isUser && !msg.reasoning.isNullOrBlank()) {
                    ThinkingBlock(reasoning = msg.reasoning.orEmpty(), isDark = isDark, defaultExpanded = false)
                    Spacer(modifier = Modifier.height(6.dp))
                }
                if (msg.content.isNotBlank()) {
                    if (isUser) {
                        Text(msg.content, fontSize = fontSp, color = textColor)
                    } else {
                        MarkdownContent(
                            content = msg.content,
                            isDark = isDark,
                            fontSize = fontSp,
                            textColor = textColor,
                            onCopied = { }
                        )
                    }
                }
                if (msg.durationMs > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${msg.durationMs / 1000.0}s",
                        fontSize = 10.sp,
                        color = textColor.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

/** 渲染分组：一个"工作回合" = assistant(含工具调用) + 跟随的工具结果；其余为独立消息 */
private data class ToolGroup(
    val key: Any,
    val callMsg: ChatMessage? = null,
    val results: List<ChatMessage> = emptyList(),
    val msg: ChatMessage? = null,
    val files: List<Pair<String, String>> = emptyList()
)

/** 把消息流按工作回合分组：带工具调用的 assistant 消息吸收其后紧跟的 role=tool 结果消息；send_file 的文件卡片挂到该回合 AI 正文之后 */
private fun buildMsgGroups(messages: List<ChatMessage>): List<ToolGroup> {
    val out = mutableListOf<ToolGroup>()
    var pendingCall: ChatMessage? = null
    val pendingResults = mutableListOf<ChatMessage>()
    val pendingFiles = mutableListOf<Pair<String, String>>()
    fun flush() {
        val c = pendingCall
        if (c != null) {
            // 无 AI 正文的回合：文件卡片挂在工具卡片之后（回合末尾）
            out.add(ToolGroup(key = c.id, callMsg = c, results = pendingResults.toList(), files = pendingFiles.toList()))
            pendingCall = null
            pendingResults.clear()
            pendingFiles.clear()
        }
    }
    for (m in messages) {
        when {
            m.role == "assistant" && !m.toolCalls.isNullOrEmpty() -> { flush(); pendingCall = m }
            m.role == "tool" && pendingCall != null -> {
                pendingResults.add(m)
                parseFileCard(m.content)?.let { pendingFiles.add(it) }
            }
            m.role == "assistant" && m.toolCalls.isNullOrEmpty() && pendingCall != null -> {
                // AI 正文：先结算工具回合（卡片移出），文件卡片挂在正文末尾
                val c = pendingCall
                if (c != null) {
                    out.add(ToolGroup(key = c.id, callMsg = c, results = pendingResults.toList()))
                    out.add(ToolGroup(key = m.id, msg = m, files = pendingFiles.toList()))
                } else {
                    out.add(ToolGroup(key = m.id, msg = m, files = pendingFiles.toList()))
                }
                pendingCall = null
                pendingResults.clear()
                pendingFiles.clear()
            }
            else -> { flush(); out.add(ToolGroup(key = m.id, msg = m)) }
        }
    }
    flush()
    return out
}

/**
 * 工具工作卡片（DSH 风格）：默认收起只显示"调用工具 + 工具名"；
 * 展开后依次展示每个工具的调用参数（AI 工作信息），下方紧跟该工具的返回结果。
 * AI 给用户的最终报告是卡片之后的正常气泡。
 */
@Composable
private fun ToolWorkCard(
    callMsg: ChatMessage,
    results: List<ChatMessage>,
    isDark: Boolean,
    fontSp: TextUnit
) {
    var expanded by remember { mutableStateOf(false) }
    val calls = callMsg.toolCalls.orEmpty()
    val resultById = results.associateBy { it.toolCallId }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).clickable { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "调用工具",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    if (expanded) "收起" else "展开",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            if (!expanded) {
                Text(
                    calls.joinToString("、") { it.name },
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                calls.forEachIndexed { index, call ->
                    val res = resultById[call.id] ?: results.getOrNull(index)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${index + 1}. ${call.name}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (call.arguments.isNotBlank()) {
                            Text(
                                call.arguments,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (res != null) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                "返回",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                            )
                            val fc = parseFileCard(res.content)
                            if (fc != null) {
                                // 文件卡片不显示在工具返回里，已移至消息末尾独立展示
                                Text(
                                    "已发送文件：${fc.first}",
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    res.content.ifBlank { "(无输出)" },
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (index != calls.lastIndex) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth().height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 工具执行结果卡片（role == "tool" 的消息） */
@Composable
private fun ToolResultCard(msg: ChatMessage) {
    var expanded by remember { mutableStateOf(false) }
    val preview = msg.content.ifBlank { "(无输出)" }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).clickable { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (preview.startsWith("错误")) Icons.Default.Error else Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = if (preview.startsWith("错误")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "工具 ${msg.toolName ?: "结果"}",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(if (expanded) "收起" else "展开", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
            Spacer(modifier = Modifier.height(3.dp))
            val fc = parseFileCard(msg.content)
            if (fc != null) {
                FileCard(name = fc.first, url = fc.second)
            } else {
                Text(
                    preview,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 12 else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 解析 send_file 工具结果协议：「【文件卡片】文件名|url」→ (name, url)；非文件卡片返回 null */
private fun parseFileCard(content: String): Pair<String, String>? {
    if (!content.startsWith("【文件卡片】")) return null
    val rest = content.removePrefix("【文件卡片】").trim()
    val sep = rest.indexOf('|')
    if (sep <= 0) return null
    val name = rest.substring(0, sep).trim()
    val url = rest.substring(sep + 1).trim()
    if (name.isBlank() || url.isBlank()) return null
    return name to url
}

/** 文件卡片：横 4:1 比例，左侧 1:1 文件类型图标 + 名称，点击提示并下载（系统下载器，通知栏显示进度） */
@Composable
private fun FileCard(name: String, url: String) {
    val context = LocalContext.current
    val (iconColor, iconLabel) = fileTypeInfo(name)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp)).clickable {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (dm == null) {
                Toast.makeText(context, "系统下载器不可用", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    val safe = name.replace(Regex("""[\\/:*?"<>|\s]"""), "_").ifBlank { "file" }
                    val req = DownloadManager.Request(Uri.parse(url))
                        .setTitle(name)
                        .setDescription("正在下载到「下载」目录…")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safe)
                    dm.enqueue(req)
                    Toast.makeText(context, "开始下载：$name", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "下载失败：${e.message ?: "无法启动下载"}", Toast.LENGTH_SHORT).show()
                }
            }
        },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().aspectRatio(4f).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧 1:1 文件类型图标
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(iconColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    iconLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // 名称与下载提示
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "点击下载",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/** 按扩展名返回文件类型图标的 (背景色, 缩写标签) */
private fun fileTypeInfo(name: String): Pair<Color, String> {
    val ext = name.substringAfterLast('.', "").lowercase().trim()
    return when (ext) {
        "pdf" -> Color(0xFFE53935) to "PDF"
        "zip", "rar", "7z", "tar", "gz", "tgz" -> Color(0xFFFB8C00) to "ZIP"
        "apk" -> Color(0xFF43A047) to "APK"
        "doc", "docx" -> Color(0xFF1E88E5) to "DOC"
        "xls", "xlsx", "csv" -> Color(0xFF2E7D32) to "XLS"
        "ppt", "pptx" -> Color(0xFFD84315) to "PPT"
        "png", "jpg", "jpeg", "gif", "webp", "bmp" -> Color(0xFF8E24AA) to "IMG"
        "mp3", "mp4", "wav", "flac", "aac", "ogg" -> Color(0xFF00897B) to "MED"
        "txt", "log", "md", "json", "xml", "sql", "yml", "yaml" -> Color(0xFF546E7A) to "TXT"
        "exe", "msi", "dmg", "deb", "rpm" -> Color(0xFF5E35B1) to "APP"
        else -> Color(0xFF455A64) to ext.uppercase().take(3).ifBlank { "FILE" }
    }
}

/** 早期对话摘要块（上下文压缩产物，role=system 的【早期对话摘要】消息）：默认收起，展开显示摘要全文 */
@Composable
private fun SummaryBlock(msg: ChatMessage) {
    var expanded by remember { mutableStateOf(false) }
    val preview = msg.content.removePrefix("【早期对话摘要】").ifBlank { msg.content }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).clickable { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "早期对话摘要（已压缩）",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(if (expanded) "收起" else "展开", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(preview, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Streaming Bubble（流式输出中的实时气泡，打字机效果）
// ─────────────────────────────────────────────────────────────────
@Composable
private fun StreamingBubble(
    content: String,
    reasoning: String,
    isDark: Boolean,
    fontSp: TextUnit,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier.widthIn(max = 320.dp),
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 流式思考过程：思考先行，默认展开
            if (reasoning.isNotBlank()) {
                ThinkingBlock(reasoning = reasoning, isDark = isDark, defaultExpanded = true)
                Spacer(modifier = Modifier.height(6.dp))
            }
            if (content.isBlank()) {
                Text(
                    if (reasoning.isBlank()) "AI 正在思考..." else "正在整理回答...",
                    fontSize = fontSp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                MarkdownContent(
                    content = content,
                    isDark = isDark,
                    fontSize = fontSp,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    onCopied = { }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "▍",
                    fontSize = fontSp,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                onClick = onStop,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("停止生成", fontSize = 12.sp)
            }
        }
    }
}

/** 思考过程折叠块（reasoning）：点击切换展开/收起 */
@Composable
private fun ThinkingBlock(reasoning: String, isDark: Boolean, defaultExpanded: Boolean) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    val accent = Color(0xFF00897B)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded },
        color = if (isDark) Color(0xFF16302E) else Color(0xFFE6F2F1),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(14.dp), tint = accent)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (expanded) "收起思考过程" else "查看思考过程",
                    fontSize = 11.sp,
                    color = accent
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = accent
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    reasoning,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Markdown Content with Copyable Code Blocks
// ─────────────────────────────────────────────────────────────────
@Composable
fun MarkdownContent(
    content: String,
    isDark: Boolean,
    textColor: Color,
    onCopied: () -> Unit,
    fontSize: TextUnit = 14.sp
) {
    // 先提取 markdown 资源块（图片 / 文件链接），干净文本按资源在原 markdown 中的位置交错渲染
    val (cleanText, blocks) = remember(content) { extractMarkdownBlocks(content) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var last = 0
        blocks.forEach { b ->
            if (b.cleanOffset > last) {
                MarkdownTextSection(
                    text = cleanText.substring(last, b.cleanOffset),
                    isDark = isDark,
                    textColor = textColor,
                    onCopied = onCopied,
                    fontSize = fontSize
                )
                last = b.cleanOffset
            }
            when (b.kind) {
                "image" -> MdImageBlock(url = b.url, alt = b.text, isDark = isDark)
                "file" -> FileLinkRow(name = b.text, url = b.url)
            }
        }
        if (last < cleanText.length) {
            MarkdownTextSection(
                text = cleanText.substring(last),
                isDark = isDark,
                textColor = textColor,
                onCopied = onCopied,
                fontSize = fontSize
            )
        }
    }
}

/** 渲染一段干净 markdown 文本：段落 + 行内链接（可点击）+ 代码块 */
@Composable
private fun MarkdownTextSection(
    text: String,
    isDark: Boolean,
    textColor: Color,
    onCopied: () -> Unit,
    fontSize: TextUnit
) {
    if (text.isBlank()) return
    val segments = remember(text, isDark) { parseMarkdownSegments(text, isDark) }
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.plainSegments.forEachIndexed { plainIdx, (annotated, _) ->
            val segLinks = segments.links.getOrNull(plainIdx).orEmpty()
            // ClickableText：点击命中段内链接位置时打开浏览器，其余位置无动作
            ClickableText(
                text = annotated,
                style = TextStyle(fontSize = fontSize, color = textColor),
                onClick = { offset ->
                    segLinks.firstOrNull { offset in it.start until it.end }?.let { lp ->
                        runCatching { uriHandler.openUri(lp.url) }
                    }
                }
            )
            // Render any code block that follows this plain segment
            if (plainIdx < segments.codeBlocks.size) {
                val block = segments.codeBlocks[plainIdx]
                CodeBlockCard(
                    code = block.code,
                    language = block.language,
                    isDark = isDark,
                    onCopy = {
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(block.code))
                        onCopied()
                    }
                )
            }
        }
    }
}

/** markdown 图片块：加载并显示 ![alt](url)（支持 http(s) / data:base64 / file://），点击查看大图 */
@Composable
private fun MdImageBlock(url: String, alt: String, isDark: Boolean) {
    val context = LocalContext.current
    var state by remember(url) { mutableStateOf<ImgUiState>(ImgUiState.Loading) }
    LaunchedEffect(url) {
        state = ImgUiState.Loading
        state = withContext(Dispatchers.IO) {
            val bmp = loadMarkdownImage(context, url)
            if (bmp != null) ImgUiState.Ready(bmp) else ImgUiState.Failed
        }
    }
    when (val s = state) {
        is ImgUiState.Loading -> Box(
            modifier = Modifier.fillMaxWidth().height(150.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        is ImgUiState.Failed -> Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Error, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.width(6.dp))
                Text("图片加载失败${if (alt.isNotBlank() && alt != "图片") "：$alt" else ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (url.startsWith("http")) {
                    Spacer(modifier = Modifier.width(8.dp))
                    val uriHandler = LocalUriHandler.current
                    Text(
                        "打开原图",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable { runCatching { uriHandler.openUri(url) } }
                    )
                }
            }
        }
        is ImgUiState.Ready -> {
            var zoom by remember { mutableStateOf(false) }
            Image(
                bitmap = s.bitmap.asImageBitmap(),
                contentDescription = alt,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { zoom = true }
            )
            if (zoom) ImageViewerDialog(bitmap = s.bitmap, alt = alt) { zoom = false }
        }
    }
}

private sealed interface ImgUiState {
    data object Loading : ImgUiState
    data class Ready(val bitmap: Bitmap) : ImgUiState
    data object Failed : ImgUiState
}

/** 大图查看对话框：点击任意处关闭 */
@Composable
private fun ImageViewerDialog(bitmap: Bitmap, alt: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black).clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = alt,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            )
        }
    }
}

/** markdown 文件链接块：[name](file.ext) → 可点击文件行（浏览器/系统打开） */
@Composable
private fun FileLinkRow(name: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { runCatching { uriHandler.openUri(url) } },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("打开", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
        }
    }
}

/** 加载 markdown 图片字节：data:base64 直接解码；file:// 读本地；http(s) 复用内置证书信任下载 */
private fun loadMarkdownImage(context: Context, url: String): Bitmap? {
    val bytes: ByteArray = when {
        url.startsWith("data:image") -> try {
            Base64.decode(url.substringAfter(','), Base64.DEFAULT)
        } catch (_: Exception) { return null }
        url.startsWith("file://") -> try {
            File(URLDecoder.decode(url.removePrefix("file://"), "UTF-8")).readBytes()
        } catch (_: Exception) { return null }
        url.startsWith("http") -> try {
            val conn = URL(url).openConnection() as? HttpURLConnection ?: return null
            if (conn is HttpsURLConnection) {
                TlsConfig.createSocketFactory(context)?.let { conn.sslSocketFactory = it }
            }
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) { conn.disconnect(); return null }
            conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
        } catch (_: Exception) { return null }
        else -> return null
    }
    return try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / sample > 2048 || o.outHeight / sample > 2048) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
    } catch (_: Exception) { null }
}

@Composable
private fun CodeBlockCard(
    code: String,
    language: String,
    isDark: Boolean,
    onCopy: () -> Unit
) {
    val codeBg = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)
    val borderColor = if (isDark) Color(0xFF3C3C3C) else Color(0xFFE0E0E0)
    val headerBg = if (isDark) Color(0xFF2D2D2D) else Color(0xFFEBEBEB)
    val headerTextColor = if (isDark) Color(0xFF9E9E9E) else Color(0xFF757575)
    var copied by remember { mutableStateOf(false) }

    // Reset copied state after 2 seconds
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = codeBg,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Language tag
                Text(
                    text = language.ifBlank { "代码" },
                    fontSize = 11.sp,
                    color = headerTextColor,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                // Copy button
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            onCopy()
                            copied = true
                        },
                    color = if (copied) {
                        if (isDark) Color(0xFF2E7D32) else Color(0xFFE8F5E9)
                    } else {
                        if (isDark) Color(0xFF37474F) else Color(0xFFE3F2FD)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = if (copied) "已复制" else "复制代码",
                            modifier = Modifier.size(13.dp),
                            tint = if (copied) {
                                if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                            } else {
                                if (isDark) Color(0xFF90CAF9) else Color(0xFF1565C0)
                            }
                        )
                        Text(
                            text = if (copied) "已复制" else "复制",
                            fontSize = 11.sp,
                            color = if (copied) {
                                if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                            } else {
                                if (isDark) Color(0xFF90CAF9) else Color(0xFF1565C0)
                            }
                        )
                    }
                }
            }

            // Code content — horizontally scrollable, no line wrapping
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(
                    text = code,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (isDark) Color(0xFFD4D4D4) else Color(0xFF1E1E1E),
                    lineHeight = 20.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Model Chips Bar
// ─────────────────────────────────────────────────────────────────
@Composable
fun ModelChipsBar(uiState: ChatUiState, onIntent: (UiIntent) -> Unit) {
    if (uiState.config.models.size <= 1) return
    // 横向可滑动：模型多时内容超宽，必须 horizontalScroll，否则被父容器裁剪且无法滑动
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        uiState.config.models.forEach { model ->
            FilterChip(
                selected = model.id == uiState.config.activeModelId,
                onClick = { onIntent(UiIntent.SetActiveModel(model.id)) },
                label = { Text(model.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = if (model.id == uiState.config.activeModelId) {
                    { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }
                } else null,
                modifier = Modifier.height(28.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Settings Sheet
// ─────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(uiState: ChatUiState, onIntent: (UiIntent) -> Unit) {
    var section by rememberSaveable { mutableStateOf<String?>(null) }

    // 返回层级：编辑表单 → 设置子页 → 设置主页 → 对话
    val goBack = {
        when {
            uiState.editingModel != null -> onIntent(UiIntent.CancelEditModel)
            section != null -> section = null
            else -> onIntent(UiIntent.HideSettings)
        }
    }
    BackHandler(onBack = goBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(
                    when {
                        uiState.editingModel != null -> "编辑模型"
                        section == "models" -> "模型"
                        section == "agent" -> "Agent 工具"
                        section == "memories" -> "记忆"
                        section == "appearance" -> "外观"
                        section == "data" -> "数据"
                        section == "about" -> "关于"
                        section == "crashlog" -> "崩溃日志"
                        else -> "设置"
                    },
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                ) },
                navigationIcon = {
                    IconButton(onClick = goBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(20.dp))
                    }
                },
                windowInsets = WindowInsets(0.dp),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when {
                uiState.editingModel != null -> ModelEditForm(uiState, onIntent)
                section == null -> SettingsHomeList(uiState, onIntent, onOpen = { section = it })
                else -> SettingsSectionBody(section, uiState, onIntent)
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/** 设置主页：分组卡片列表 */
@Composable
private fun SettingsHomeList(uiState: ChatUiState, onIntent: (UiIntent) -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "1.0.0"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsRow(
            icon = Icons.Default.SmartToy,
            title = "模型",
            subtitle = if (uiState.config.models.isEmpty()) "还没有模型，去添加" else "共 ${uiState.config.models.size} 个模型",
            onClick = { onOpen("models") }
        )
        SettingsRow(
            icon = Icons.Default.Bolt,
            title = "Agent 工具",
            subtitle = "常开 · 授权阈值 ${thresholdLabel(uiState.config.toolApprovalThreshold)} · 续写上限 ${if (uiState.config.maxAutoRounds == 0) "不限" else "${uiState.config.maxAutoRounds} 轮"}",
            onClick = { onOpen("agent") }
        )
        SettingsRow(
            icon = Icons.Default.Favorite,
            title = "记忆",
            subtitle = "AI 跨会话记住你 · 共 ${uiState.memories.size} 条",
            onClick = { onOpen("memories"); onIntent(UiIntent.LoadMemories) }
        )
        SettingsRow(
            icon = Icons.Default.Palette,
            title = "外观",
            subtitle = "${themeLabel(uiState.config.themeMode)} · 强调色 ${accentLabel(uiState.config.accentColor)}",
            onClick = { onOpen("appearance") }
        )
        SettingsRow(
            icon = Icons.Default.Storage,
            title = "数据",
            subtitle = "导出对话 / 备份 / 清空",
            onClick = { onOpen("data") }
        )
        SettingsRow(
            icon = Icons.Default.BugReport,
            title = "崩溃日志",
            subtitle = "查看/复制最近一次崩溃的堆栈",
            onClick = { onOpen("crashlog") }
        )
        SettingsRow(
            icon = Icons.Default.Info,
            title = "关于",
            subtitle = "Chat Client v$appVersion",
            onClick = { onOpen("about") }
        )
    }
}

/** 设置子页内容 */
@Composable
private fun SettingsSectionBody(section: String?, uiState: ChatUiState, onIntent: (UiIntent) -> Unit) {
    when (section) {
        "models" -> ModelsTabContent(uiState, onIntent)
        "agent" -> AgentTabContent(uiState, onIntent)
        "memories" -> MemoriesTabContent(uiState, onIntent)
        "appearance" -> AppearanceTabContent(uiState, onIntent)
        "data" -> DataTabContent(uiState, onIntent)
        "crashlog" -> CrashLogTabContent()
        "about" -> AboutTabContent()
    }
}

/** 崩溃日志：展示 crash.log（可复制用于反馈） */
@Composable
private fun CrashLogTabContent() {
    val context = LocalContext.current
    val text = remember {
        runCatching { File(context.filesDir, "crash.log").readText() }
            .getOrNull() ?: "暂无崩溃日志"
    }
    Column(modifier = Modifier.padding(4.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        val clipboard = LocalClipboardManager.current
        Button(
            onClick = { clipboard.setText(AnnotatedString(text)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("复制崩溃日志") }
    }
}

private fun thresholdLabel(level: Int): String = when (level) {
    2 -> "敏感级起确认"
    4 -> "全部自动执行"
    else -> "仅高风险确认"
}

/** 记忆设置子页：查看 / 删除 AI 跨会话保存的长期记忆 */
@Composable
private fun MemoriesTabContent(uiState: ChatUiState, onIntent: (UiIntent) -> Unit) {
    LaunchedEffect(Unit) { onIntent(UiIntent.LoadMemories) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                "AI 会在对话中自动判断并保存关于你的重要信息（偏好 / 身份 / 习惯 / 事实），跨会话有效，并随每轮对话注入给 AI。你可以在这里查看、删除或清空。",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (uiState.memories.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "还没有记忆。告诉 AI 一些你的偏好或信息（例如「记住，我喜欢简洁的回答」），它会自动保存到这里的。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp)
                )
            }
        } else {
            uiState.memories.forEach { m ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).padding(vertical = 3.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when (m.importance) {
                                        "high" -> "高"
                                        "low" -> "低"
                                        else -> "普通"
                                    },
                                    fontSize = 10.5.sp,
                                    color = when (m.importance) {
                                        "high" -> MaterialTheme.colorScheme.error
                                        "low" -> MaterialTheme.colorScheme.onSurfaceVariant
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(m.updatedAt)),
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(m.content, fontSize = 13.sp)
                        }
                        IconButton(onClick = { onIntent(UiIntent.DeleteMemory(m.id)) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除这条记忆", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = { onIntent(UiIntent.ClearMemories) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
            ) { Text("清空全部记忆") }
        }
    }
}

/** Agent 工具设置子页：AI 自主续写轮数 + 授权阈值 + 可用工具 */
@Composable
private fun AgentTabContent(uiState: ChatUiState, onIntent: (UiIntent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("AI 自主续写轮数", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "回复走 JSON 协议（content + done）。AI 说还没说完（done=false）时，客户端自动请求其继续输出，像真人一样连说多段，直到 done=true 结束。0 = 不限（有失控风险）。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                SingleLineRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        0 to "不限",
                        3 to "3 轮",
                        5 to "5 轮",
                        10 to "10 轮",
                        20 to "20 轮"
                    ).forEach { (rounds, label) ->
                        FilterChip(
                            selected = uiState.config.maxAutoRounds == rounds,
                            onClick = { onIntent(UiIntent.SetMaxAutoRounds(rounds)) },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("上下文压缩阈值", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "历史消息估算超过该值时，最旧部分会用 AI 摘要压缩成要点（摘要失败自动回退丢弃旧消息），避免超出模型上下文。0 = 关闭。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                SingleLineRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        0 to "关闭",
                        32 to "32k",
                        64 to "64k",
                        128 to "128k",
                        256 to "256k"
                    ).forEach { (k, label) ->
                        FilterChip(
                            selected = uiState.config.contextLimitK == k,
                            onClick = { onIntent(UiIntent.SetContextLimitK(k)) },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("工具授权阈值", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("调用危险等级 ≥ 阈值的工具前，需你点击确认。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(10.dp))
                SingleLineRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        2 to "敏感级起确认",
                        3 to "仅高风险确认",
                        4 to "全部自动执行"
                    ).forEach { (level, label) ->
                        FilterChip(
                            selected = uiState.config.toolApprovalThreshold == level,
                            onClick = { onIntent(UiIntent.SetApprovalThreshold(level)) },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // SSH 服务器配置（ssh_execute 工具）
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("SSH 服务器", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "配置后模型可通过 ssh_execute 工具连接远程服务器执行命令（危险等级 3，调用前需你确认）。密码与私钥二选一（私钥优先）。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                var ssh by remember(uiState.config.sshConfig) { mutableStateOf(uiState.config.sshConfig) }
                var portText by remember(uiState.config.sshConfig) { mutableStateOf(ssh.port.toString()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = ssh.enabled, onCheckedChange = { ssh = ssh.copy(enabled = it) })
                    Text("启用 SSH 工具", fontSize = 14.sp)
                }
                EditField("主机地址", ssh.host, { ssh = ssh.copy(host = it) }, placeholder = "192.168.1.10 或 example.com")
                EditField("端口", portText, {
                    portText = it
                    it.toIntOrNull()?.let { p -> ssh = ssh.copy(port = p) }
                }, placeholder = "22")
                EditField("用户名", ssh.username, { ssh = ssh.copy(username = it) }, placeholder = "root")
                EditField("密码", ssh.password, { ssh = ssh.copy(password = it) }, placeholder = "留空则用私钥认证", isPassword = true)
                EditField("私钥（OpenSSH 格式）", ssh.privateKey, { ssh = ssh.copy(privateKey = it) }, placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----", multiLine = true)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onIntent(UiIntent.SaveSshConfig(ssh)) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("保存 SSH 配置")
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("已内置工具", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(6.dp))
                listOf(
                    "calculator" to "数学表达式计算（含函数与常量）",
                    "current_time" to "当前日期与时间",
                    "run_shell" to "内置 busybox 沙箱：白名单命令，可联网(wget)（4 ABI 全兼容）",
                    "web_search" to "联网搜索互联网（Firecrawl 免费额度，无需 API Key，失败自动降级 Keenable）",
                    "fetch_page" to "抓取公开网页正文（Markdown 截断，用于阅读搜索结果）",
                    "remember_memory" to "把关于用户的重要信息保存进长期记忆（跨会话有效）",
                    "recall_memories" to "检索长期记忆中关于用户的已知信息",
                    "send_file" to "向用户发送文件（聊天中展示为文件卡片）",
                    "ssh_execute" to "通过 SSH 连接已配置的远程服务器执行命令（需在设置页配置）"
                ).forEach { (name, desc) ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("🔧 $name", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                        Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 18.dp, top = 1.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 0.5.dp,
        tonalElevation = 0.5.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标圆角色块
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            ) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(subtitle, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
    }
}

private fun themeLabel(mode: String): String = when (mode) {
    "light" -> "浅色"
    "dark" -> "深色"
    else -> "跟随系统"
}

private fun accentLabel(color: String): String = when (color) {
    "green" -> "绿"; "purple" -> "紫"; "orange" -> "橙"; "teal" -> "青"; "pink" -> "粉"
    else -> "蓝"
}



@Composable
private fun ModelsTabContent(uiState: ChatUiState, onIntent: (UiIntent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(12.dp))

        uiState.config.models.forEach { model ->
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onIntent(UiIntent.StartEditModel(model)) },
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(model.name.ifBlank { "未命名" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(model.model.ifBlank { "未设置模型" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (model.id == uiState.config.activeModelId) {
                        Icon(Icons.Default.Check, contentDescription = "当前使用", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onIntent(UiIntent.StartAddModel) },
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("添加模型", fontSize = 14.sp)
        }
    }
}

@Composable
private fun AppearanceTabContent(uiState: ChatUiState, onIntent: (UiIntent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 主题模式：浅色 / 深色 / 跟随系统
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BrightnessMedium, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("主题模式", fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(10.dp))
                SingleLineRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "light" to "浅色",
                        "dark" to "深色",
                        "system" to "跟随系统"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = uiState.config.themeMode == mode,
                            onClick = { onIntent(UiIntent.SetThemeMode(mode)) },
                            label = { Text(label, fontSize = 13.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 强调色
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Palette, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("强调色", fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    listOf(
                        "blue" to 0xFF1A73E8L, "green" to 0xFF188038L, "purple" to 0xFF9334E6L,
                        "orange" to 0xFFF29900L, "teal" to 0xFF00897BL, "pink" to 0xFFE91E63L
                    ).forEach { (name, rgb) ->
                        val color = Color(rgb)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .clickable { onIntent(UiIntent.SetAccentColor(name)) }
                                    .then(
                                        if (uiState.config.accentColor == name)
                                            Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        else Modifier
                                    )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(name, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FormatSize, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("消息字号", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${uiState.config.messageFontSize.toInt()}sp", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = uiState.config.messageFontSize,
                    onValueChange = { onIntent(UiIntent.SetMessageFontSize(it)) },
                    valueRange = 12f..20f,
                    steps = 7,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DataTabContent(uiState: ChatUiState, onIntent: (UiIntent) -> Unit) {
    val context = LocalContext.current
    var showClearConfirm by remember { mutableStateOf(false) }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val payload = uiState.exportPayload
        if (uri != null && payload != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(payload.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                }
            } catch (_: Exception) {
            }
        }
        onIntent(UiIntent.ClearExportPayload)
    }

    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = try {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (_: Exception) { null }
            onIntent(UiIntent.ImportBackup(text.orEmpty()))
        }
    }

    // exportPayload 就绪后自动弹出系统"创建文件"对话框
    LaunchedEffect(uiState.exportPayload) {
        if (uiState.exportPayload != null) {
            createDocLauncher.launch("ai_chat_backup_${System.currentTimeMillis()}.json")
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空所有数据") },
            text = { Text("将删除全部模型配置与聊天记录，此操作不可恢复。建议先导出备份。") },
            confirmButton = {
                TextButton(onClick = { showClearConfirm = false; onIntent(UiIntent.ClearAllData) }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } }
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "备份包含全部模型配置与聊天记录，用于换机迁移或误删恢复。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { onIntent(UiIntent.ExportBackup) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("导出备份（JSON）", fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { openDocLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*")) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Upload, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("导入恢复", fontSize = 14.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = { showClearConfirm = true },
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("清空所有数据", fontSize = 14.sp)
        }
    }
}

@Composable
private fun AboutTabContent() {
    val context = LocalContext.current
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "1.0.0"
    }
    val uriHandler = LocalUriHandler.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Chat Client", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("版本 v$appVersion", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "支持 OpenAI 兼容 API，流式输出（打字机效果），多模型切换，Agent 模式（默认常开：模型可自主调用工具、像真人一样连说多轮），备份/恢复，本地数据存储。\n内置 ISRG Root X1 证书信任，兼容 Android 5+ 旧设备的 HTTPS 连接。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "GitHub 开源仓库",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/U4PLUS/com.uchat.app")
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text("By U400A1/DeepSeek", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Model Edit Form
// ─────────────────────────────────────────────────────────────────
@Composable
fun ModelEditForm(uiState: ChatUiState, onIntent: (UiIntent) -> Unit) {
    val model = uiState.editingModel ?: return
    val apiTestResult = uiState.apiTestResult

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (uiState.isNewModel) "添加新模型" else "编辑模型",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = { onIntent(UiIntent.HideSettings) }) {
                Icon(Icons.Default.Close, contentDescription = "取消")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        EditField("配置名称", model.name, { onIntent(UiIntent.UpdateEditingModel(model.copy(name = it))) }, placeholder = "例如：DeepSeek")
        EditField("API 地址", model.apiUrl, { onIntent(UiIntent.UpdateEditingModel(model.copy(apiUrl = it))) }, placeholder = "https://api.deepseek.com/v1")
        EditField("API Key", model.apiKey, { onIntent(UiIntent.UpdateEditingModel(model.copy(apiKey = it))) }, placeholder = "sk-xxxx...", isPassword = true)

        // 模型名称 + 右侧 1:1 获取模型按钮（点击获取，弹出列表选择填入）
        // 底部对齐：按钮(56dp)与输入框(56dp)等高，底边对齐即中心对齐，避免按钮偏低
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Text("模型名称", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = model.model,
                    onValueChange = { onIntent(UiIntent.UpdateEditingModel(model.copy(model = it))) },
                    modifier = Modifier.fillMaxWidth().clipToBounds(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    placeholder = { Text("deepseek-chat", fontSize = 14.sp) }
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (uiState.fetchingModels) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                    .clickable(enabled = !uiState.fetchingModels && model.apiUrl.isNotBlank() && model.apiKey.isNotBlank()) {
                        onIntent(UiIntent.FetchModels)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (uiState.fetchingModels) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "获取模型列表",
                        modifier = Modifier.size(22.dp),
                        tint = if (model.apiUrl.isNotBlank() && model.apiKey.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    )
                }
            }
        }

        // Temperature slider
        Column(modifier = Modifier.padding(vertical = 5.dp)) {
            Text("温度: ${String.format("%.1f", model.temperature)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = model.temperature,
                onValueChange = { onIntent(UiIntent.UpdateEditingModel(model.copy(temperature = it))) },
                valueRange = 0f..2f,
                steps = 19,
                modifier = Modifier.fillMaxWidth()
            )
        }

        EditField("系统提示词", model.systemPrompt, { onIntent(UiIntent.UpdateEditingModel(model.copy(systemPrompt = it))) }, placeholder = "可选，设置 AI 的角色", multiLine = true)

        // 自定义请求头 / 请求体：本地编辑列表，保存/测试/预览时同步回 model
        var headerList by remember(model.id) { mutableStateOf(model.customHeaders.toList()) }
        KeyValueEditor(
            title = "自定义请求头",
            subtitle = "附加到每次请求的 HTTP 请求头，如 Authorization、X-API-Key 等",
            items = headerList,
            keyPlaceholder = "请求头名",
            valuePlaceholder = "值",
            addLabel = "添加请求头",
            onUpdate = { headerList = it }
        )
        var bodyList by remember(model.id) { mutableStateOf(model.customBodyParams.toList()) }
        KeyValueEditor(
            title = "自定义请求体",
            subtitle = "附加到请求体 JSON 顶层的参数，可覆盖默认参数；值支持数字/布尔（如 max_tokens=2048）",
            items = bodyList,
            keyPlaceholder = "参数名",
            valuePlaceholder = "值",
            addLabel = "添加参数",
            onUpdate = { bodyList = it }
        )

        Row(modifier = Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = model.stream,
                onCheckedChange = { onIntent(UiIntent.UpdateEditingModel(model.copy(stream = it))) }
            )
            Text("流式输出", fontSize = 14.sp)
        }

        // 视觉模型
        Row(modifier = Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = model.vision,
                onCheckedChange = { onIntent(UiIntent.UpdateEditingModel(model.copy(vision = it))) }
            )
            Column {
                Text("视觉模型", fontSize = 14.sp)
                Text("关闭时发送图片会转为沙箱文件形式交给模型处理", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 思考模式：自动识别 / 强制开启 / 关闭
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("深度思考", fontSize = 14.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (model.thinkingPolicy == "auto") "自动识别：支持思考的模型自动启用" else "思考过程单独展示",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            SingleLineRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("auto" to "自动", "on" to "开启", "off" to "关闭").forEach { (mode, label) ->
                    FilterChip(
                        selected = model.thinkingPolicy == mode,
                        onClick = { onIntent(UiIntent.UpdateEditingModel(model.copy(thinkingPolicy = mode))) },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }
        }

        // 思考格式 + 思考强度：仅"开启"时显示（自动/关闭均不干预思考参数，交给 API 决策）
        if (model.thinkingPolicy == "on") {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp)) {
                Text("思考格式", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                SingleLineRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "auto" to "自动",
                        "deepseek" to "DeepSeek",
                        "openai" to "OpenAI",
                        "qwen" to "Qwen",
                        "bool" to "布尔"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = model.thinkingMode == mode,
                            onClick = { onIntent(UiIntent.UpdateEditingModel(model.copy(thinkingMode = mode))) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text("思考强度", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                SingleLineRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "auto" to "自动",
                        "low" to "低",
                        "medium" to "中",
                        "high" to "强"
                    ).forEach { (eff, label) ->
                        FilterChip(
                            selected = model.thinkingEffort == eff,
                            onClick = { onIntent(UiIntent.UpdateEditingModel(model.copy(thinkingEffort = eff))) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "强度仅在 OpenAI / DeepSeek 等支持 reasoning_effort 的模型上生效",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        // API Test
        if (!uiState.isNewModel || model.apiKey.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    // 测试前先把本地请求头列表同步进 model
                    onIntent(
                        UiIntent.UpdateEditingModel(
                            model.copy(
                                    customHeaders = headerList.mapNotNull { (hk, hv) ->
                                        hk.trim().takeIf { it.isNotEmpty() }?.let { it to hv }
                                    }.toMap(),
                                    customBodyParams = bodyList.mapNotNull { (bk, bv) ->
                                        bk.trim().takeIf { it.isNotEmpty() }?.let { it to bv }
                                    }.toMap()
                                )
                        )
                    )
                    onIntent(UiIntent.TestApi)
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                enabled = !uiState.isTestingApi && model.apiUrl.isNotBlank() && model.apiKey.isNotBlank()
            ) {
                if (uiState.isTestingApi) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("测试中...")
                } else {
                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("测试连接")
                }
            }

            apiTestResult?.let { result ->
                Spacer(modifier = Modifier.height(6.dp))
                ApiTestResultRow(result)
            }

            // 查看请求体：预览将要发送的请求体与请求头（验证自定义请求头 / 思考参数）
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    // 预览前先把本地请求头列表同步进 model
                    onIntent(
                        UiIntent.UpdateEditingModel(
                            model.copy(
                                    customHeaders = headerList.mapNotNull { (hk, hv) ->
                                        hk.trim().takeIf { it.isNotEmpty() }?.let { it to hv }
                                    }.toMap(),
                                    customBodyParams = bodyList.mapNotNull { (bk, bv) ->
                                        bk.trim().takeIf { it.isNotEmpty() }?.let { it to bv }
                                    }.toMap()
                                )
                        )
                    )
                    onIntent(UiIntent.PreviewRequestBody)
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                enabled = model.apiUrl.isNotBlank() && model.apiKey.isNotBlank()
            ) {
                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("查看请求体")
            }
        }

        // 模型选择弹窗：获取模型后弹出列表，选择即填入模型名称
        if (uiState.availableModels.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { onIntent(UiIntent.ClearAvailableModels) },
                title = { Text("选择模型") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        if (uiState.availableModels.isEmpty()) {
                            Text("没有获取到模型", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            uiState.availableModels.forEach { available ->
                                TextButton(
                                    onClick = {
                                        onIntent(UiIntent.UpdateEditingModel(model.copy(model = available)))
                                        onIntent(UiIntent.ClearAvailableModels)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        available,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { onIntent(UiIntent.ClearAvailableModels) }) { Text("取消") }
                }
            )
        }

        // 请求体预览弹窗
        uiState.requestBodyPreview?.let { preview ->
            Dialog(onDismissRequest = { onIntent(UiIntent.ClearRequestPreview) }) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "请求体预览",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onIntent(UiIntent.ClearRequestPreview) }) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            preview,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!uiState.isNewModel) {
                OutlinedButton(
                    onClick = { onIntent(UiIntent.DeleteEditingModel) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除")
                }
            }
            Button(
                onClick = {
                    // 保存前把本地请求头列表同步进 model
                    onIntent(
                        UiIntent.UpdateEditingModel(
                            model.copy(
                                    customHeaders = headerList.mapNotNull { (hk, hv) ->
                                        hk.trim().takeIf { it.isNotEmpty() }?.let { it to hv }
                                    }.toMap(),
                                    customBodyParams = bodyList.mapNotNull { (bk, bv) ->
                                        bk.trim().takeIf { it.isNotEmpty() }?.let { it to bv }
                                    }.toMap()
                                )
                        )
                    )
                    onIntent(UiIntent.SaveEditingModel)
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun KeyValueEditor(
    title: String,
    subtitle: String,
    items: List<Pair<String, String>>,
    keyPlaceholder: String,
    valuePlaceholder: String,
    addLabel: String,
    onUpdate: (List<Pair<String, String>>) -> Unit
) {
    Spacer(modifier = Modifier.height(10.dp))
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(2.dp))
        Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        Spacer(modifier = Modifier.height(4.dp))
        items.forEachIndexed { index, (k, v) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = k,
                    onValueChange = { nk -> onUpdate(items.toMutableList().also { it[index] = nk to v }) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    placeholder = { Text(keyPlaceholder, fontSize = 12.sp) },
                    textStyle = TextStyle(fontSize = 12.sp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                OutlinedTextField(
                    value = v,
                    onValueChange = { nv -> onUpdate(items.toMutableList().also { it[index] = k to nv }) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    placeholder = { Text(valuePlaceholder, fontSize = 12.sp) },
                    textStyle = TextStyle(fontSize = 12.sp)
                )
                IconButton(
                    onClick = { onUpdate(items.filterIndexed { j, _ -> j != index }) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        TextButton(
            onClick = { onUpdate(items + ("" to "")) },
            modifier = Modifier.padding(top = 2.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(addLabel, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    isPassword: Boolean = false,
    multiLine: Boolean = false
) {
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().clipToBounds().then(if (multiLine) Modifier.height(90.dp) else Modifier),
            singleLine = !multiLine,
            shape = RoundedCornerShape(10.dp),
            placeholder = { Text(placeholder, fontSize = 14.sp) },
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None
        )
    }
}

@Composable
private fun ApiTestResultRow(result: com.uchat.app.data.ApiTestResult) {
    val (icon, color) = if (result.success) Icons.Default.CheckCircle to Color(0xFF1E8E3E) else Icons.Default.Error to MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(result.message, fontSize = 12.sp, color = color)
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Empty State
// ─────────────────────────────────────────────────────────────────
@Composable
private fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(12.dp))
            Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────
/** 附件读取辅助：图片（白名单格式，≤4MB → base64）与文本类文件（白名单 MIME，≤1MB → 原文） */
private val ALLOWED_IMAGE_MIME = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif")
private val ALLOWED_TEXT_MIME = setOf(
    "text/plain", "text/markdown", "text/x-markdown", "text/html", "text/csv",
    "text/yaml", "text/x-yaml", "application/json", "application/pdf", "application/xml", "application/x-yaml"
)

private fun readImageAttachment(context: Context, uri: Uri): SendAttachment? {
    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
    if (mime !in ALLOWED_IMAGE_MIME) return null
    val bytes = readBytesSafe(context, uri) ?: return null
    if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) return null  // ≤4MB
    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    return SendAttachment("image", nameOf(context, uri) ?: "图片", mime, b64, bytes.size.toLong())
}

private fun readTextAttachment(context: Context, uri: Uri): SendAttachment? {
    val mime = context.contentResolver.getType(uri) ?: ""
    if (mime !in ALLOWED_TEXT_MIME && !mime.startsWith("text/")) return null
    val bytes = readBytesSafe(context, uri) ?: return null
    if (bytes.isEmpty() || bytes.size > 1024 * 1024) return null  // ≤1MB
    return SendAttachment("text", nameOf(context, uri) ?: "文件", mime, String(bytes, Charsets.UTF_8), bytes.size.toLong())
}

private fun readBytesSafe(context: Context, uri: Uri): ByteArray? = try {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
} catch (_: Exception) { null }

private fun nameOf(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (_: Exception) { null }

@Composable
private fun SingleLineRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable RowScope.() -> Unit
) {
    Row(modifier = modifier, horizontalArrangement = horizontalArrangement, content = content)
}

// ─────────────────────────────────────────────────────────────────
// UiIntent Sealed Class
// ─────────────────────────────────────────────────────────────────
sealed class UiIntent {
    data object ShowSettings : UiIntent()
    data object HideSettings : UiIntent()
    data object NewChat : UiIntent()
    data object GoToList : UiIntent()
    data class SelectChat(val chatId: String) : UiIntent()
    data class DeleteChat(val chatId: String) : UiIntent()
    data class SendMessage(val text: String) : UiIntent()
    data class SetActiveModel(val modelId: String) : UiIntent()
    data object ToggleDarkMode : UiIntent()
    data class SetMessageFontSize(val size: Float) : UiIntent()
    data object StartAddModel : UiIntent()
    data class StartEditModel(val model: ModelConfig) : UiIntent()
    data class UpdateEditingModel(val model: ModelConfig) : UiIntent()
    data object SaveEditingModel : UiIntent()
    data object CancelEditModel : UiIntent()
    data object DeleteEditingModel : UiIntent()
    data object FetchModels : UiIntent()
    data object ClearAvailableModels : UiIntent()
    data object PreviewRequestBody : UiIntent()
    data object ClearRequestPreview : UiIntent()
    data object TestApi : UiIntent()
    data object ClearToast : UiIntent()
    data object ExportChats : UiIntent()
    data object ClearChat : UiIntent()
    data object Regenerate : UiIntent()
    data object StopGenerating : UiIntent()
    data class TruncateFrom(val messageId: Long) : UiIntent()
    data object StartEditTitle : UiIntent()
    data object CancelEditTitle : UiIntent()
    data class UpdateChatTitle(val chatId: String, val title: String) : UiIntent()
    data object ExportBackup : UiIntent()
    data object ClearExportPayload : UiIntent()
    data class ImportBackup(val json: String) : UiIntent()
    data object ClearAllData : UiIntent()
    data class AddAttachment(val attachment: SendAttachment) : UiIntent()
    data class RemoveAttachment(val index: Int) : UiIntent()
    data class SetThemeMode(val mode: String) : UiIntent()
    data class SetAccentColor(val color: String) : UiIntent()
    data class SetApprovalThreshold(val level: Int) : UiIntent()
    data class SetMaxAutoRounds(val rounds: Int) : UiIntent()
    data class SetContextLimitK(val k: Int) : UiIntent()
    data class SaveSshConfig(val sshConfig: SshConfig) : UiIntent()
    data object LoadMemories : UiIntent()
    data class DeleteMemory(val id: String) : UiIntent()
    data object ClearMemories : UiIntent()
    data class ApproveTool(val allow: Boolean) : UiIntent()
}
