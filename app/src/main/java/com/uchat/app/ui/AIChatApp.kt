@file:OptIn(ExperimentalMaterial3Api::class)

package com.uchat.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import java.io.File
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
import com.uchat.app.data.ModelConfig
import com.uchat.app.data.OpenCodeFreeModel
import com.uchat.app.data.OpenCodeSpeedResult
import com.uchat.app.data.SendAttachment
import com.uchat.app.data.ToolCallData
import kotlinx.coroutines.launch
import com.uchat.app.data.parseMarkdown
import com.uchat.app.data.parseMarkdownSegments
import com.uchat.app.viewmodel.ChatUiState
import java.text.SimpleDateFormat

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
                            if (uiState.config.superChat) {
                                Text("⚡", fontSize = 13.sp, modifier = Modifier.padding(end = 2.dp))
                            }
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
                    // Super Chat 开关（免费车道不支持 → 提示）
                    var showSuperToolTip by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = {
                            val isFree = uiState.config.models.find { it.id == uiState.config.activeModelId }?.freeGatewayFingerprint == true
                            if (isFree) showSuperToolTip = true
                            else onIntent(UiIntent.ToggleSuperChat)
                        }) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = "Super Chat",
                                tint = if (uiState.config.superChat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (showSuperToolTip) {
                            AlertDialog(
                                onDismissRequest = { showSuperToolTip = false },
                                title = { Text("无法开启 Super Chat") },
                                text = { Text("当前使用的是免费车道模型（强制 tool_choice=none），不支持工具调用。请更换为支持 function calling 的模型后再使用 Agent 模式。") },
                                confirmButton = { TextButton(onClick = { showSuperToolTip = false }) { Text("知道了") } }
                            )
                        }
                    }
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
                                    Text("点右上角 ⋮ → 设置 → 获取免费模型，或手动添加", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), fontSize = 12.sp)
                                } else {
                                    Text("输入消息，发送后自动开始新对话", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 13.5.sp)
                                }
                            }
                        }
                    }
                }

                items(uiState.messages, key = { it.id }) { msg ->
                    if (msg.role == "tool") {
                        ToolResultCard(msg)
                    } else {
                        MessageBubble(
                            msg = msg,
                            isDark = uiState.config.themeMode != "light",
                            fontSp = uiState.config.messageFontSize.sp,
                            toolCalls = msg.toolCalls ?: emptyList(),
                            onLongClick = { showMenuMsg = msg }
                        )
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
    onLongClick: () -> Unit,
    toolCalls: List<ToolCallData> = emptyList()
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
                // Agent 模式：工具调用记录（可展开）
                if (toolCalls.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    for (call in toolCalls) {
                        var expanded by remember { mutableStateOf(false) }
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🔧 ${call.name}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = textColor)
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(if (expanded) "收起" else "参数", fontSize = 10.sp, color = textColor.copy(alpha = 0.6f))
                                }
                                if (expanded && call.arguments.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(call.arguments, fontSize = 10.5.sp, color = textColor.copy(alpha = 0.8f), maxLines = 6, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
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
    val segments = remember(content, isDark) { parseMarkdownSegments(content, isDark) }
    val clipboardManager = LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.plainSegments.forEachIndexed { plainIdx, (annotated, _) ->
            // Check if there's a code block immediately after this plain text
            // plainIdx maps to code block at same index (codeBlocks list aligns with interleaving)
            Text(
                text = annotated,
                fontSize = fontSize,
                color = textColor
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
                        section == "superchat" -> "Super Chat"
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
            title = "Super Chat（Agent 模式）",
            subtitle = if (uiState.config.superChat) "已开启 · 授权阈值 ${thresholdLabel(uiState.config.toolApprovalThreshold)}" else "未开启（模型可自主调用工具）",
            onClick = { onOpen("superchat") }
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
        "superchat" -> SuperChatTabContent(uiState, onIntent)
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

/** Super Chat（Agent 模式）设置子页：开关 + 授权阈值 + 可用工具 */
@Composable
private fun SuperChatTabContent(uiState: ChatUiState, onIntent: (UiIntent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Bolt, null, modifier = Modifier.size(20.dp), tint = if (uiState.config.superChat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Super Chat（Agent 模式）", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        "开启后模型自主决策：先调用工具、查看结果、再继续推进，直到完成任务后结束回合。不适用于 free 免费车道模型（其接口强制 tool_choice=none）。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = uiState.config.superChat, onCheckedChange = { onIntent(UiIntent.ToggleSuperChat) })
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
                    "run_shell" to "内置 busybox 沙箱：白名单命令，可联网(wget)（4 ABI 全兼容）"
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
        // ── OpenCode 免费模型：获取 → 测速 → 用户自行添加 ──
        when {
            uiState.fetchingFreeModels -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (uiState.speedTestProgress != null)
                            "正在实测速度 ${uiState.speedTestProgress}（测完自动隐藏不可用的）..."
                        else "正在获取免费模型列表...",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            uiState.freeModelError != null -> {
                Surface(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        uiState.freeModelError,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { onIntent(UiIntent.FetchOpenCodeModels) },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("重新获取", fontSize = 13.sp)
                }
            }
            !uiState.freeModelsLoaded -> {
                OutlinedButton(
                    onClick = { onIntent(UiIntent.FetchOpenCodeModels) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Bolt, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("获取 OpenCode 免费模型", fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "免登录免 Key 直连 opencode.ai 免费车道。获取后自动筛选带 free 的模型并逐个实测速度，不可用的（地区受限/额度/超时）自动隐藏，仅展示实测可用的，快的排前面；点「添加」才会加入你的模型列表。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "共 ${uiState.freeModels.size} 个可用免费模型 · 已实测（不可用的已自动隐藏）",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(onClick = { onIntent(UiIntent.FetchOpenCodeModels) }) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("重新获取", fontSize = 12.sp)
                    }
                }
                if (uiState.speedTestProgress != null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
                }
                // 排序：可用且快的在前，失败/不支持在后
                val sorted = remember(uiState.freeModels, uiState.freeSpeedResults) {
                    uiState.freeModels.sortedWith { a, b ->
                        scoreOf(uiState.freeSpeedResults[a.id]).compareTo(scoreOf(uiState.freeSpeedResults[b.id]))
                    }
                }
                sorted.forEach { entry ->
                    FreeModelRow(entry = entry, uiState = uiState, onIntent = onIntent)
                }
            }
        }

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
                    if (model.freeGatewayFingerprint) {
                        Icon(Icons.Default.Bolt, contentDescription = "免费", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
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

/** 排序键：可用且快的在前 */
private fun scoreOf(speed: OpenCodeSpeedResult?): Long = when {
    speed == null -> 3_000_000L
    !speed.ok -> 2_000_000L
    speed.latencyMs <= 0 -> 1_000_000L
    else -> speed.latencyMs
}

@Composable
private fun FreeModelRow(entry: OpenCodeFreeModel, uiState: ChatUiState, onIntent: (UiIntent) -> Unit) {
    val speed = uiState.freeSpeedResults[entry.id]
    val added = uiState.config.models.any { it.model == entry.id }
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entry.reasoning) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                            Text("思考", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                    }
                    if (!entry.chatWire) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text("不支持", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(entry.id, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${entry.contextWindow / 1024}K 上下文" +
                        (if (entry.reasoning && !entry.canDisableThinking) " · 思考不可关" else "") +
                        (if (entry.vision) " · 视觉" else ""),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            SpeedBadge(speed)
            Spacer(modifier = Modifier.width(8.dp))
            if (added) {
                Text("已添加", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            } else {
                TextButton(onClick = { onIntent(UiIntent.AddOpenCodeModel(entry.id)) }) {
                    Text("添加", fontSize = 13.sp)
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun SpeedBadge(speed: OpenCodeSpeedResult?) {
    val (color, text) = when {
        speed == null -> MaterialTheme.colorScheme.onSurfaceVariant to "测速中..."
        !speed.ok -> MaterialTheme.colorScheme.error to speed.note
        speed.latencyMs <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant to "无输出"
        speed.latencyMs < 2000 -> Color(0xFF2E7D32) to "${speed.latencyMs}ms · 快"
        speed.latencyMs < 6000 -> Color(0xFFEF6C00) to "${speed.latencyMs}ms · 中"
        else -> Color(0xFFD32F2F) to "${speed.latencyMs}ms · 慢"
    }
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.12f)) {
        Text(text, fontSize = 10.sp, color = color, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
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
                    "支持 OpenAI 兼容 API，流式输出（打字机效果），多模型切换，免费模型（免 Key）通道，备份/恢复，本地数据存储。\n内置 ISRG Root X1 证书信任，兼容 Android 5+ 旧设备的 HTTPS 连接。",
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

        // 免费网关提示
        if (model.freeGatewayFingerprint) {
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "⚡ 已启用免费模型网关（免 Key）：自动附带 opencode 指纹头与工具声明，免费通道仅支持流式输出；API Key 固定为 public，模型名请在网关返回的清单中选择。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        EditField("配置名称", model.name, { onIntent(UiIntent.UpdateEditingModel(model.copy(name = it))) }, placeholder = "例如：DeepSeek")
        EditField("API 地址", model.apiUrl, { onIntent(UiIntent.UpdateEditingModel(model.copy(apiUrl = it))) }, placeholder = "https://api.deepseek.com/v1/chat/completions")
        EditField("API Key", model.apiKey, { onIntent(UiIntent.UpdateEditingModel(model.copy(apiKey = it))) }, placeholder = "sk-xxxx...", isPassword = true)
        EditField("模型名称", model.model, { onIntent(UiIntent.UpdateEditingModel(model.copy(model = it))) }, placeholder = "deepseek-chat")

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

        Row(modifier = Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = model.stream,
                onCheckedChange = { onIntent(UiIntent.UpdateEditingModel(model.copy(stream = it))) },
                enabled = !model.freeGatewayFingerprint
            )
            Text(
                if (model.freeGatewayFingerprint) "流式输出（免费通道强制开启）" else "流式输出",
                fontSize = 14.sp
            )
        }

        // 思考等级：仅免费网关 + 支持思考的模型
        val freeCaps = com.uchat.app.data.OpenCodeCatalog.capabilitiesOf(model.model)
        if (model.freeGatewayFingerprint && freeCaps.reasoning) {
            Row(modifier = Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("思考等级", fontSize = 14.sp)
                    Text(
                        if (freeCaps.canDisableThinking)
                            "思考与正文共用输出预算；关闭则不设预算限制"
                        else
                            "该模型思考不可关闭，各档预算已自动翻倍",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val levels = buildList {
                if (freeCaps.canDisableThinking) add("off" to "关闭")
                add("light" to "精简")
                add("balanced" to "均衡")
                add("deep" to "深思")
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                levels.forEach { (level, label) ->
                    val budget = com.uchat.app.data.OpenCodeEffort.budgetLabel(level, model.model)
                    FilterChip(
                        selected = model.thinkingLevel == level,
                        onClick = { onIntent(UiIntent.UpdateEditingModel(model.copy(thinkingLevel = level))) },
                        label = { Text("$label·$budget", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        } else {
            Row(modifier = Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = model.thinking,
                    onCheckedChange = { onIntent(UiIntent.UpdateEditingModel(model.copy(thinking = it))) }
                )
                Column {
                    Text("深度思考", fontSize = 14.sp)
                    Text("启用 o1 系列模型的推理能力", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // API Test
        if (!uiState.isNewModel || model.apiKey.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onIntent(UiIntent.TestApi) },
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

            // 获取模型列表
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onIntent(UiIntent.FetchModels) },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                enabled = !uiState.fetchingModels && model.apiUrl.isNotBlank() && model.apiKey.isNotBlank()
            ) {
                if (uiState.fetchingModels) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("获取中...")
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("获取模型列表")
                }
            }

            if (uiState.availableModels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("可用模型（点击填入）：", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                uiState.availableModels.forEach { available ->
                    FilterChip(
                        selected = model.model == available,
                        onClick = { onIntent(UiIntent.UpdateEditingModel(model.copy(model = available))) },
                        label = { Text(available, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
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
                onClick = { onIntent(UiIntent.SaveEditingModel) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("保存")
            }
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
    data object FetchOpenCodeModels : UiIntent()
    data class AddOpenCodeModel(val modelId: String) : UiIntent()
    data class AddAttachment(val attachment: SendAttachment) : UiIntent()
    data class RemoveAttachment(val index: Int) : UiIntent()
    data class SetThemeMode(val mode: String) : UiIntent()
    data class SetAccentColor(val color: String) : UiIntent()
    data object ToggleSuperChat : UiIntent()
    data class SetApprovalThreshold(val level: Int) : UiIntent()
    data class ApproveTool(val allow: Boolean) : UiIntent()
}
