package com.uchat.app.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_chat_prefs_v2")

data class ModelConfig(
    val id: String,
    val name: String,
    val apiUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Float = 0.7f,
    val systemPrompt: String = "",
    val stream: Boolean = true,
    /** 深度思考（旧字段，仅迁移用）：true=开启；新版本用 thinkingPolicy */
    val thinking: Boolean = false,
    /** 思考策略：auto=按模型自动识别启用 / on=始终开启 / off=始终关闭 */
    val thinkingPolicy: String = "auto",
    /** 思考强度：auto（按模型默认 high）/ low / medium / high */
    val thinkingEffort: String = "auto",
    /** 思考参数格式：auto=按模型名自动识别 / deepseek / openai / qwen / bool */
    val thinkingMode: String = "auto",
    /** 是否为视觉模型：true=图片以 image_url 多模态发送；false=图片转沙箱文件交给模型处理 */
    val vision: Boolean = true,
    /** 附加到每次请求的自定义请求头（发消息 / 测试连接 / 获取模型列表） */
    val customHeaders: Map<String, String> = emptyMap(),
    /** 附加到请求体 JSON 顶层的自定义参数（键值，值自动识别数字/布尔/字符串），可覆盖默认参数 */
    val customBodyParams: Map<String, String> = emptyMap()
)

data class AppConfig(
    /** 模型列表：默认空，不自动添加任何模型（用户自行添加或从免费模型清单中添加） */
    val models: List<ModelConfig> = emptyList(),
    val activeModelId: String = "",
    val darkMode: Boolean = false,
    /** 消息正文显示字号（sp），12f..20f，默认 14f */
    val messageFontSize: Float = 14f,
    /** 主题模式：light / dark / system（默认跟随系统） */
    val themeMode: String = "system",
    /** 强调色：blue / green / purple / orange / teal / pink */
    val accentColor: String = "blue",
    /** Agent（Super Chat）模式为默认常开，模型可自主调用工具 */
    /** 工具授权阈值：dangerLevel >= 该值的工具调用需用户确认（2=敏感级起，3=高风险级起，4=全部自动） */
    val toolApprovalThreshold: Int = 3,
    /** AI 自主续写轮数上限：对话 JSON 协议 done=false 时自动续写；0 = 无限 */
    val maxAutoRounds: Int = 10,
    /** 上下文压缩阈值（k token，0 = 不压缩）：历史消息估算超过时，最旧部分用 AI 摘要压缩 */
    val contextLimitK: Int = 128,
    /** SSH 服务器配置（ssh_execute 工具使用） */
    val sshConfig: SshConfig = SshConfig(),
    /** Termux 环境选择：null=首次未选择（弹出提示），true=已开启（解压安装），false=暂不开启（busybox） */
    val termuxEnabled: Boolean? = null,
)

/** SSH 连接配置（模型通过 ssh_execute 工具连接远程服务器） */
data class SshConfig(
    /** 是否启用 SSH 工具 */
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    /** 密码认证（与 privateKey 二选一，私钥优先） */
    val password: String = "",
    /** 私钥内容（OpenSSH 格式），留空则用密码认证 */
    val privateKey: String = "",
)

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val durationMs: Long = 0L,
    /** 思考过程（reasoning，来自流式 delta.reasoning / reasoning_details），空表示无 */
    val reasoning: String = "",
    /** assistant 消息携带的工具调用请求（Agent 模式），空/ null 表示无 */
    val toolCalls: List<ToolCallData>? = null,
    /** tool 消息对应哪个工具调用（role == "tool" 时使用） */
    val toolCallId: String? = null,
    /** tool 消息的工具名（界面展示用） */
    val toolName: String? = null
)

/** 一次工具调用请求（OpenAI function calling） */
data class ToolCallData(
    val id: String,
    val name: String,
    val arguments: String
)

/** 工具定义（注册表条目）：内置或脚本工具 */
data class ToolDef(
    val id: String,
    val name: String,
    val description: String,
    /** 参数 JSON Schema 字符串 */
    val parameters: String,
    /** 危险等级：1 只读 / 2 有影响 / 3 高风险（触发授权阈值） */
    val dangerLevel: Int = 1,
    /** builtin / script */
    val type: String = "builtin",
    /** type==script 时的脚本内容 */
    val script: String? = null
)

data class Chat(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val lastUpdated: Long = System.currentTimeMillis()
)

/** 记忆条目：AI 通过 remember_memory 工具保存的用户长期信息（跨会话有效） */
data class MemoryEntry(
    val id: String = "",
    val content: String = "",
    /** low / normal / high */
    val importance: String = "normal",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/** 记忆系统回调：ToolRegistry 与存储解耦，由 AppRepository 实现 */
interface MemoryOps {
    /** 保存一条记忆，返回给模型看的简短结果文本 */
    suspend fun saveMemory(content: String, importance: String): String
    /** 按关键词检索记忆，返回给模型看的格式化文本 */
    suspend fun recallMemories(query: String): String
}

/** 联网能力回调：web_search / fetch_page 由 AppRepository 实现（复用内置证书信任） */
interface NetOps {
    /** 联网搜索（Firecrawl keyless，失败自动降级 Keenable），返回给模型看的格式化结果 */
    suspend fun webSearch(query: String, maxResults: Int): String
    /** 抓取公开网页正文（Firecrawl keyless scrape，Markdown 截断） */
    suspend fun fetchPage(url: String): String
}

/** SSH 能力回调：ssh_execute 由 AppRepository 实现（复用设置页配置的连接） */
interface SSHOps {
    /** 在已配置的 SSH 服务器上执行命令，返回 stdout/stderr 合并输出（截断 8000 字符） */
    suspend fun sshExecute(command: String): String
}

data class CodeBlock(
    val language: String,
    val code: String,
    val startIndex: Int,
    val endIndex: Int,
)

/** 内联链接位置：偏移相对所属 plain 段文本，供 UI 层点击命中判断 */
data class LinkPos(
    val start: Int,
    val end: Int,
    val label: String,
    val url: String
)

/**
 * Markdown segments — used by AIChatApp to render mixed code + prose content.
 * plainSegments: ordered list of (annotatedString, rawText) for non-code-block regions
 * codeBlocks: ordered list of extracted fenced code blocks
 * links: 与 plainSegments 索引对齐的段内链接位置
 */
data class MarkdownSegments(
    val plainSegments: List<Pair<AnnotatedString, String>>,
    val codeBlocks: List<CodeBlock>,
    val links: List<List<LinkPos>> = emptyList(),
)

fun parseMarkdown(text: String, isDark: Boolean): Pair<AnnotatedString, List<CodeBlock>> {
    val segments = parseMarkdownSegments(text, isDark)
    // Legacy path: flatten plain text AnnotatedString + code block list
    val flatText = buildAnnotatedString {
        segments.plainSegments.forEach { (annotated, _) -> append(annotated) }
    }
    return Pair(flatText, segments.codeBlocks)
}

fun parseMarkdownSegments(text: String, isDark: Boolean): MarkdownSegments {
    val codeBg = if (isDark) Color(0xFF2D2D2D) else Color(0xFFF0F0F0)
    val codeText = if (isDark) Color(0xFF9CDCFE) else Color(0xFF0066CC)
    val linkColor = if (isDark) Color(0xFF82AAFF) else Color(0xFF1565C0)

    // 1. Extract fenced code blocks with positions
    val codeBlockRegex = Regex("""```(\w*)[\s\S]*?```""")
    val codeMatches = codeBlockRegex.findAll(text).toList()
    val codeBlocks = codeMatches.map { match ->
        val raw = match.value
        val firstNewline = raw.indexOf('\n')
        val lang = if (firstNewline > 3) raw.substring(3, firstNewline).trim() else ""
        // 内容区间：firstNewline 之后到结尾 ``` 之前（去掉围栏行）；空代码块（```\n```）安全返回空串
        val innerEnd = if (raw.endsWith("```")) raw.length - 3 else raw.length
        val inner = if (firstNewline > 0) {
            val begin = firstNewline + 1
            if (begin < innerEnd) raw.substring(begin, innerEnd) else ""
        } else {
            raw.substring(firstNewline + 1)
        }
        CodeBlock(language = lang, code = inner, startIndex = match.range.first, endIndex = match.range.last + 1)
    }

    // 2. Split text into plain regions between code blocks
    val plainSegments = mutableListOf<Pair<AnnotatedString, String>>()
    val allLinks = mutableListOf<List<LinkPos>>()
    var pos = 0
    for (block in codeBlocks) {
        if (block.startIndex > pos) {
            val plain = text.substring(pos, block.startIndex)
            val links = mutableListOf<LinkPos>()
            plainSegments.add(Pair(buildAnnotated(plain, codeBg, codeText, linkColor, links), plain))
            allLinks.add(links)
        }
        pos = block.endIndex
    }
    if (pos < text.length) {
        val plain = text.substring(pos)
        val links = mutableListOf<LinkPos>()
        plainSegments.add(Pair(buildAnnotated(plain, codeBg, codeText, linkColor, links), plain))
        allLinks.add(links)
    }
    if (plainSegments.isEmpty()) {
        plainSegments.add(Pair(buildAnnotated("", codeBg, codeText, linkColor, mutableListOf()), ""))
        allLinks.add(emptyList())
    }

    return MarkdownSegments(plainSegments, codeBlocks, allLinks)
}

private fun buildAnnotated(text: String, codeBg: Color, codeText: Color, linkColor: Color, links: MutableList<LinkPos>): AnnotatedString {
    return buildAnnotatedString {
        var col = 0
        val lines = text.split("\n")
        for (line in lines) {
            when {
                line.startsWith("```") -> { append("\n"); col += 1 }
                line.startsWith("# ") -> { val v = line.removePrefix("# "); pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)); append(v); pop(); append("\n"); col += v.length + 1 }
                line.startsWith("## ") -> { val v = line.removePrefix("## "); pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)); append(v); pop(); append("\n"); col += v.length + 1 }
                line.startsWith("### ") -> { val v = line.removePrefix("### "); pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)); append(v); pop(); append("\n"); col += v.length + 1 }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    append("  \u2022  "); col += 5
                    col += appendInlineRich(line.drop(2), linkColor, links)
                    append("\n"); col += 1
                }
                line.startsWith("> ") -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color.Gray))
                    append("  \u2507 "); col += 4
                    col += appendInlineRich(line.drop(2), linkColor, links)
                    pop(); append("\n"); col += 1
                }
                line == "---" || line == "***" -> { append("─────────────────────────────────\n"); col += 34 }
                line.contains("`") -> { col += parseInlineCode(line, codeBg, codeText, linkColor, links) }
                else -> { col += appendInlineRich(line, linkColor, links); append("\n"); col += 1 }
            }
        }
    }
}

private fun AnnotatedString.Builder.parseInlineCode(line: String, bg: Color, textColor: Color, linkColor: Color, links: MutableList<LinkPos>): Int {
    var col = 0
    var i = 0
    while (i < line.length) {
        val start = line.indexOf('`', i)
        if (start == -1) { col += appendInlineRich(line.substring(i), linkColor, links); append("\n"); return col + 1 }
        if (start > i) col += appendInlineRich(line.substring(i, start), linkColor, links)
        val end = line.indexOf('`', start + 1)
        if (end == -1) { col += appendInlineRich(line.substring(start), linkColor, links); append("\n"); return col + 1 }
        pushStyle(SpanStyle(background = bg, fontFamily = FontFamily.Monospace, color = textColor))
        append(line.substring(start, end + 1))
        pop()
        col += end - start + 1
        i = end + 1
    }
    append("\n")
    return col + 1
}

/** 行内富文本：[text](url) 链接 与 裸 URL 渲染为下划线链接样式，位置记入 links（偏移相对当前 plain 段） */
private fun AnnotatedString.Builder.appendInlineRich(text: String, linkColor: Color, links: MutableList<LinkPos>): Int {
    if (text.isEmpty()) return 0
    val regex = Regex("""\[([^\]]+)\]\((https?://[^)\s]+)\)|(https?://[^\s\)\]<>]+)""")
    val matches = regex.findAll(text).toList()
    if (matches.isEmpty()) { append(text); return text.length }
    var col = 0
    for (m in matches) {
        if (m.range.first > col) { append(text.substring(col, m.range.first)); col = m.range.first }
        val label = m.groups[1]?.value ?: m.groups[3]!!.value
        val url = m.groups[2]?.value ?: m.groups[3]!!.value
        pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        append(label)
        pop()
        links.add(LinkPos(col, col + label.length, label, url))
        col += label.length
    }
    if (col < text.length) append(text.substring(col))
    return text.length
}

class AppRepository(private val context: Context) : MemoryOps, NetOps, SSHOps {
    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()

    /** 内置 ISRG Root X1 信任 + TLS1.2 的 SocketFactory（失败时回退系统默认） */
    private val sslSocketFactory: SSLSocketFactory? by lazy { TlsConfig.createSocketFactory(context) }

    private object Keys {
        val models = stringPreferencesKey("models_v2")
        val activeModelId = stringPreferencesKey("active_model_id_v2")
        val darkMode = booleanPreferencesKey("dark_mode_v2")
        val messageFontSize = floatPreferencesKey("message_font_size_v2")
        val themeMode = stringPreferencesKey("theme_mode_v2")
        val accentColor = stringPreferencesKey("accent_color_v2")
        val toolApprovalThreshold = intPreferencesKey("tool_approval_threshold_v2")
        val maxAutoRounds = intPreferencesKey("max_auto_rounds_v2")
        val contextLimitK = intPreferencesKey("context_limit_k_v2")
        val sshConfig = stringPreferencesKey("ssh_config_v1")
        val memories = stringPreferencesKey("memories_v2")
        val chats = stringPreferencesKey("chats_v2")
    }

    val configFlow: Flow<AppConfig> = context.dataStore.data.map { prefs ->
        val modelsJson = prefs[Keys.models]
        val models: List<ModelConfig> = if (modelsJson != null) {
            try {
                val type = object : TypeToken<List<ModelConfig>>() {}.type
                normalizeModels(gson.fromJson<List<ModelConfig>>(modelsJson, type), modelsJson)
            } catch (_: Exception) { AppConfig().models }
        } else { AppConfig().models }
        val activeId = prefs[Keys.activeModelId] ?: models.firstOrNull()?.id ?: ""
        val dark = prefs[Keys.darkMode] ?: false
        val font = prefs[Keys.messageFontSize] ?: 14f
        val themeMode = prefs[Keys.themeMode] ?: if (dark) "dark" else "system"
        val accent = prefs[Keys.accentColor] ?: "blue"
        val threshold = prefs[Keys.toolApprovalThreshold] ?: 3
        val maxAutoRounds = prefs[Keys.maxAutoRounds] ?: 10
        val contextLimitK = prefs[Keys.contextLimitK] ?: 128
        val sshJson = prefs[Keys.sshConfig]
        val ssh = try {
            gson.fromJson(sshJson, SshConfig::class.java) ?: SshConfig()
        } catch (_: Exception) { SshConfig() }
        AppConfig(models = models, activeModelId = activeId, darkMode = dark,
                  messageFontSize = font, themeMode = themeMode, accentColor = accent,
                  toolApprovalThreshold = threshold, maxAutoRounds = maxAutoRounds,
                  contextLimitK = contextLimitK, sshConfig = ssh)
    }

    val chatsFlow: Flow<Map<String, Chat>> = context.dataStore.data.map { prefs ->
        val chatsJson = prefs[Keys.chats] ?: "{}"
        try {
            val type = object : TypeToken<Map<String, Chat>>() {}.type
            gson.fromJson(chatsJson, type) ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    /**
     * 旧版本保存的模型配置 JSON 缺少后新增的非空字段时，Gson 反射反序列化会留下 null，
     * 后续 model.copy(...) 触发非空校验 NPE。这里用反射检查并补齐默认值（绕过 Kotlin 非空类型系统）。
     */
    private fun normalizeModels(models: List<ModelConfig>, rawJson: String = ""): List<ModelConfig> {
        // 旧数据没有 vision 字段时基本类型会反序列化为 false，需按原始 JSON 判断补 true（默认视觉）
        val legacyNoVision = rawJson.isNotBlank() && !rawJson.contains("\"vision\"")
        return models.map { m ->
            var out = m
            try {
                // 所有字段先反射读出（as? 容错 null），最后一次性 copy，避免中间 copy 因其它字段为 null 抛 NPE
                fun field(name: String): Any? {
                    val f = ModelConfig::class.java.getDeclaredField(name).apply { isAccessible = true }
                    return f.get(out)
                }
                val newMode = field("thinkingMode") as? String ?: "auto"
                val oldThinking = field("thinking") as? Boolean
                val newPolicy = field("thinkingPolicy") as? String ?: if (oldThinking == true) "on" else "auto"
                val newEffort = field("thinkingEffort") as? String ?: "auto"
                @Suppress("UNCHECKED_CAST")
                val newHeaders = (field("customHeaders") as? Map<String, String>) ?: emptyMap()
                @Suppress("UNCHECKED_CAST")
                val newBody = (field("customBodyParams") as? Map<String, String>) ?: emptyMap()
                out = out.copy(
                    thinkingMode = newMode,
                    thinkingPolicy = newPolicy,
                    thinkingEffort = newEffort,
                    customHeaders = newHeaders,
                    customBodyParams = newBody,
                    vision = if (legacyNoVision) true else out.vision
                )
            } catch (_: Throwable) {}
            out
        }
    }

    suspend fun saveConfig(config: AppConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.models] = gson.toJson(config.models)
            prefs[Keys.activeModelId] = config.activeModelId
            prefs[Keys.darkMode] = config.darkMode
            prefs[Keys.messageFontSize] = config.messageFontSize
            prefs[Keys.themeMode] = config.themeMode
            prefs[Keys.accentColor] = config.accentColor
            prefs[Keys.toolApprovalThreshold] = config.toolApprovalThreshold
            prefs[Keys.maxAutoRounds] = config.maxAutoRounds
            prefs[Keys.contextLimitK] = config.contextLimitK
            prefs[Keys.sshConfig] = gson.toJson(config.sshConfig)
        }
    }

    suspend fun saveChats(chats: Map<String, Chat>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.chats] = gson.toJson(chats)
        }
    }

    // ── 记忆系统（跨会话长期记忆，AI 通过 remember_memory / recall_memories 工具存取） ──

    /** 全部记忆（最新在前） */
    val memoriesFlow: Flow<List<MemoryEntry>> = context.dataStore.data.map { prefs ->
        val json = prefs[Keys.memories] ?: "[]"
        try {
            val type = object : TypeToken<List<MemoryEntry>>() {}.type
            gson.fromJson<List<MemoryEntry>>(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun saveMemories(list: List<MemoryEntry>) {
        context.dataStore.edit { prefs -> prefs[Keys.memories] = gson.toJson(list) }
    }

    suspend fun addMemory(content: String, importance: String = "normal"): MemoryEntry {
        val now = System.currentTimeMillis()
        val entry = MemoryEntry(
            id = "mem_${System.currentTimeMillis()}_${(Math.random() * 100000).toInt()}",
            content = content.trim(),
            importance = if (importance in setOf("low", "normal", "high")) importance else "normal",
            createdAt = now, updatedAt = now
        )
        val list = memoriesFlow.first().toMutableList()
        list.add(0, entry)
        if (list.size > 200) list.removeAt(list.size - 1)   // 上限防膨胀
        saveMemories(list)
        return entry
    }

    suspend fun deleteMemory(id: String) {
        saveMemories(memoriesFlow.first().filterNot { it.id == id })
    }

    suspend fun clearMemories() = saveMemories(emptyList())

    override suspend fun saveMemory(content: String, importance: String): String {
        if (content.isBlank()) return "错误：内容为空"
        val entry = addMemory(content, importance)
        return "已保存记忆（${entry.importance}）：${entry.content.take(80)}"
    }

    override suspend fun recallMemories(query: String): String {
        val all = memoriesFlow.first()
        val q = query.trim()
        val hits = if (q.isBlank()) all.take(10)
        else {
            val words = q.split(Regex("\\s+|，|,|。|；")).filter { it.length >= 2 }
            all.filter { m ->
                val c = m.content.lowercase()
                c.contains(q.lowercase()) || words.any { c.contains(it.lowercase()) }
            }.take(10)
        }
        if (hits.isEmpty()) return "（没有找到相关记忆，可调用 remember_memory 保存）"
        return hits.joinToString("\n") { "- [${it.importance}] ${it.content}" }
    }

    /** 注入 system 的记忆文本；无记忆时返回空串 */
    suspend fun formatMemoryNotes(): String {
        val all = memoriesFlow.first()
        if (all.isEmpty()) return ""
        return buildString {
            append("以下是关于用户的长久记忆（跨会话有效，请参考这些信息来回答，但不要复述本条说明）：\n")
            all.take(20).forEach { append("- ").append(it.content).append('\n') }
        }
    }

    // ── 联网搜索（参考 modsearch：Firecrawl keyless 为主，Keenable keyless 降级） ──

    private data class SearchItem(val title: String, val url: String, val snippet: String)

    override suspend fun webSearch(query: String, maxResults: Int): String = withContext(Dispatchers.IO) {
        val limit = maxResults.coerceIn(1, 8)
        val items = try {
            firecrawlSearch(query, limit)
        } catch (e1: Exception) {
            try { keenableSearch(query, limit) }
            catch (_: Exception) { throw Exception("联网搜索失败：${e1.message}") }
        }
        if (items.isEmpty()) return@withContext "（未找到相关结果，可尝试换个关键词）"
        buildString {
            append("搜索结果（").append(items.size).append(" 条，按相关度排序）：\n")
            items.forEachIndexed { i, it ->
                append(i + 1).append(". ").append(it.title).append('\n')
                append("   链接：").append(it.url).append('\n')
                if (it.snippet.isNotBlank()) append("   摘要：").append(it.snippet).append('\n')
            }
        }
    }

    override suspend fun fetchPage(url: String): String = withContext(Dispatchers.IO) {
        val body = mapOf(
            "url" to url,
            "formats" to listOf("markdown"),
            "onlyMainContent" to true,
            "maxAge" to 0,
            "storeInCache" to false,
            "skipTlsVerification" to false,
            "timeout" to 20000
        )
        val text = httpPost("https://api.firecrawl.dev/v2/scrape", body, timeoutMs = 30_000)
        val data = gson.fromJson(text, Map::class.java)
        val d = (data["data"] as? Map<*, *>) ?: throw Exception("抓取失败：响应格式异常")
        val markdown = (d["markdown"] as? String) ?: ""
        val meta = (d["metadata"] as? Map<*, *>) ?: emptyMap<String, Any?>()
        val title = (meta["title"] as? String)?.takeIf { it.isNotBlank() } ?: url
        val content = markdown.trim().take(8000)
        if (content.isBlank()) return@withContext "（页面无可读正文：$url）"
        buildString {
            append("标题：").append(title).append('\n')
            append("页面正文：\n").append(content)
        }
    }

    /** Firecrawl keyless /v2/search（无 Authorization，匿名配额按 IP 计） */
    private suspend fun firecrawlSearch(query: String, limit: Int): List<SearchItem> {
        val body = mapOf("query" to query, "limit" to limit, "sources" to listOf("web"), "timeout" to 20000)
        val text = httpPost("https://api.firecrawl.dev/v2/search", body, timeoutMs = 20_000)
        val data = gson.fromJson(text, Map::class.java)
        val web = ((data["data"] as? Map<*, *>)?.get("web") as? List<*>) ?: emptyList<Any?>()
        return web.mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            SearchItem(
                (m["title"] as? String) ?: "",
                (m["url"] as? String) ?: "",
                (m["description"] as? String) ?: ""
            )
        }.filter { it.url.isNotBlank() }
    }

    /** Keenable keyless /v1/search/public（x-keenable-title 标识来源） */
    private suspend fun keenableSearch(query: String, limit: Int): List<SearchItem> {
        val body = mapOf("query" to query, "max_results" to limit)
        val text = httpPost(
            "https://api.keenable.ai/v1/search/public", body, timeoutMs = 20_000,
            headers = mapOf("x-keenable-title" to "uchat")
        )
        val data = gson.fromJson(text, Map::class.java)
        val results = (data["results"] as? List<*>) ?: emptyList<Any?>()
        return results.mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            SearchItem(
                (m["title"] as? String) ?: "",
                (m["url"] as? String) ?: "",
                ((m["snippet"] as? String) ?: (m["description"] as? String)) ?: ""
            )
        }.filter { it.url.isNotBlank() }
    }

    /** 通用 JSON POST（复用 openConnection 的内置证书信任） */
    private suspend fun httpPost(
        url: String,
        body: Map<String, Any?>,
        timeoutMs: Int,
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        val conn = openConnection(URL(url)).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            doOutput = true; doInput = true
            connectTimeout = timeoutMs; readTimeout = timeoutMs
        }
        try {
            conn.outputStream.use { it.write(gson.toJson(body).toByteArray(StandardCharsets.UTF_8)); it.flush() }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                throw Exception("HTTP $code: ${err.take(160)}")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 统一的连接入口：HTTPS 连接注入内置证书信任的 SocketFactory */
    private fun openConnection(url: URL): HttpURLConnection {        val conn = url.openConnection() as HttpURLConnection
        if (url.protocol == "https" && conn is HttpsURLConnection) {
            sslSocketFactory?.let { conn.sslSocketFactory = it }
        }
        return conn
    }

    /**
     * 发送消息（OpenAI 兼容 /chat/completions）。
     * API 地址只需填到 v1 根地址（如 https://api.deepseek.com/v1），自动拼接 /chat/completions。
     * @param onChunk 流式增量回调（累计全文正文 + 思考过程）；非流式时仅回调一次
     * @param isCancelled 外部取消标志；content 为 null 表示请求被取消
     */
    /** 把用户输入的自定义参数值转换为 JSON 类型：数字/布尔/JSON 字面量；无法解析则保持字符串 */
    private fun parseJsonValue(v: String): Any {
        val t = v.trim()
        if (t.isEmpty()) return ""
        return try {
            gson.fromJson<Any>(t, Any::class.java) ?: ""
        } catch (_: Exception) { t }
    }

    /**
     * 计算本次请求要合并的思考参数（按思考策略）：
     * on = 添加开启思考参数；off = 显式"关闭思考"参数（否则服务器默认可能自动开启思考）；
     * auto = 不带任何思考参数，完全由 API 决定（与 off 一样不干预）。
     */
    private fun buildThinkingRequestParams(model: ModelConfig): Map<String, Any?> = when (model.thinkingPolicy) {
        "on" -> buildThinkingParams(model)
        "off" -> buildThinkingDisabledParams(model)
        else -> emptyMap()
    }

    /**
     * 显式关闭思考的参数（与 buildThinkingParams 同格式，取关闭值）：
     * deepseek → thinking.type=disabled；qwen → enable_thinking=false；bool → thinking=false；
     * openai 格式（o1/o3/gpt-5/claude 等）不支持显式关闭思考（API 限制），返回空参数交给服务器默认。
     */
    private fun buildThinkingDisabledParams(model: ModelConfig): Map<String, Any?> {
        fun deepseek() = mapOf("thinking" to mapOf("type" to "disabled"))
        fun qwen() = mapOf("enable_thinking" to false)
        fun bool() = mapOf("thinking" to false)
        return when (model.thinkingMode) {
            "deepseek" -> deepseek()
            "qwen" -> qwen()
            "bool" -> bool()
            "openai" -> emptyMap()
            else -> {
                val m = model.model.lowercase()
                when {
                    m.contains("deepseek") -> deepseek()
                    m.contains("qwen") || m.contains("qwq") || m.contains("qwen3") -> qwen()
                    m.contains("o1") || m.contains("o3") || m.contains("gpt-") || m.contains("claude") -> emptyMap()
                    else -> bool()
                }
            }
        }
    }

    /**
     * 按模型生成开启思考的参数：兼容 DeepSeek / OpenAI / Qwen / 其他（布尔 thinking）。
     * thinkingMode=auto 时按模型名识别，识别不到回退布尔格式（多数中转仍接受）。
     * thinkingEffort：auto=high，低=low，中=medium，强=high。
     */
    private fun buildThinkingParams(model: ModelConfig): Map<String, Any?> {
        val effort = when (model.thinkingEffort) {
            "low" -> "low"
            "medium" -> "medium"
            "high" -> "high"
            else -> "high"
        }
        fun deepseek() = mapOf("thinking" to mapOf("type" to "enabled"), "reasoning_effort" to effort)
        fun openai() = mapOf("reasoning_effort" to effort)
        fun qwen() = mapOf("enable_thinking" to true)
        fun bool() = mapOf("thinking" to true)
        return when (model.thinkingMode) {
            "deepseek" -> deepseek()
            "openai" -> openai()
            "qwen" -> qwen()
            "bool" -> bool()
            else -> {
                val m = model.model.lowercase()
                when {
                    m.contains("deepseek") -> deepseek()
                    m.contains("qwen") || m.contains("qwq") || m.contains("qwen3") -> qwen()
                    m.contains("o1") || m.contains("o3") || m.contains("gpt-") || m.contains("claude") -> openai()
                    else -> bool()
                }
            }
        }
    }

    /**
     * 预览即将发送的请求（不发请求）：分段展示 请求地址 / 请求体 JSON（格式化）/ 请求头 / 自定义参数，
     * 便于一眼核对自定义请求体、请求头与思考参数是否正确。
     */
    fun previewRequestBody(model: ModelConfig): String {
        val body = linkedMapOf<String, Any?>(
            "model" to model.model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to "（系统提示词，发送时填入实际内容）"),
                mapOf("role" to "user", "content" to "（你的消息）")
            ),
            "temperature" to model.temperature
        )
        if (model.stream) body["stream"] = true
        buildThinkingRequestParams(model).forEach { (k, v) -> body[k] = v }
        model.customBodyParams.forEach { (k, v) -> body[k] = parseJsonValue(v) }
        val sb = StringBuilder()
        // ① 请求地址
        sb.append("【请求地址】\n")
        sb.append("POST ").append(chatCompletionsEndpoint(model.apiUrl)).append("\n\n")
        // ② 请求体 JSON（格式化）
        sb.append("【请求体 JSON】\n")
        sb.append(prettyGson.toJson(body)).append("\n\n")
        // ③ 请求头
        sb.append("【请求头】\n")
        sb.append("Content-Type: application/json\n")
        sb.append("Authorization: Bearer ").append(model.apiKey.take(12)).append(if (model.apiKey.length > 12) "…（隐藏）" else "").append("\n")
        if (model.stream) sb.append("Accept: text/event-stream\n")
        if (model.customHeaders.isEmpty()) {
            sb.append("（无自定义请求头）\n")
        } else {
            model.customHeaders.forEach { (k, v) -> sb.append("$k: $v\n") }
        }
        sb.append("\n")
        // ④ 自定义请求体参数
        sb.append("【自定义请求体参数】\n")
        if (model.customBodyParams.isEmpty()) {
            sb.append("（无，仅使用默认参数）\n")
        } else {
            model.customBodyParams.forEach { (k, v) ->
                sb.append("$k = $v").append("   →  已合并进上面的 JSON\n")
            }
        }
        return sb.toString().trim()
    }

    suspend fun sendMessage(
        config: AppConfig,
        messages: List<ChatMessage>,
        newMessage: String,
        onChunk: (content: String, reasoning: String) -> Unit,
        isCancelled: () -> Boolean = { false },
        attachments: List<SendAttachment> = emptyList(),
        tools: List<ToolDef> = emptyList(),
        /** 注入 system 的长期记忆文本（formatMemoryNotes() 产出），空则跳过 */
        memoryNotes: String = "",
        /** 追加到 system 末尾的临时指令（如 AI 续写指令），不写入对话历史 */
        extraSystem: String? = null
    ): SendResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val model = config.models.find { it.id == config.activeModelId }
            ?: throw Exception("未选择模型，请在设置中配置")
        if (model.apiKey.isBlank()) throw Exception("API Key 未填写")
        if (model.model.isBlank()) throw Exception("模型名称未填写")

        val msgList = mutableListOf<Map<String, Any?>>()
        val agentPrompt = if (tools.isNotEmpty()) agentSystemPrompt(tools) else null
        // 回复协议：JSON 输出（content + done）默认对全部模型启用，解析失败客户端自动降级
        val systemPrompt = buildString {
            append(JSON_PROTOCOL_PROMPT)
            if (memoryNotes.isNotBlank()) append("\n\n").append(memoryNotes)
            if (model.systemPrompt.isNotBlank()) append("\n\n").append(model.systemPrompt)
            if (agentPrompt != null) append("\n\n").append(agentPrompt)
            if (extraSystem != null) append("\n\n").append(extraSystem)
        }
        if (systemPrompt.isNotBlank()) {
            msgList.add(mapOf("role" to "system", "content" to systemPrompt))
        }
        messages.forEach { msg ->
            when {
                msg.role == "tool" -> msgList.add(mapOf(
                    "role" to "tool",
                    "tool_call_id" to (msg.toolCallId ?: ""),
                    "content" to msg.content
                ))
                !msg.toolCalls.isNullOrEmpty() -> msgList.add(mapOf(
                    "role" to "assistant",
                    "content" to null,
                    "tool_calls" to msg.toolCalls.map {
                        mapOf(
                            "id" to it.id,
                            "type" to "function",
                            "function" to mapOf("name" to it.name, "arguments" to it.arguments)
                        )
                    }
                ))
                msg.content.isNotBlank() -> msgList.add(mapOf("role" to msg.role, "content" to msg.content))
            }
        }
        // 附件：图片走多模态 content 数组（data URL），文本文件内容拼入正文
        val images = attachments.filter { it.kind == "image" }
        val texts = attachments.filter { it.kind == "text" }
        val textPart = buildString {
            texts.forEach { append("【附件：").append(it.name).append("】\n").append(it.content).append("\n\n") }
            append(newMessage)
        }
        val hasUserText = textPart.isNotBlank() || images.isNotEmpty()
        if (images.isEmpty()) {
            if (textPart.isNotBlank()) msgList.add(mapOf("role" to "user", "content" to textPart))
        } else if (model.vision) {
            // 视觉模型：图片以 image_url 多模态嵌入
            val parts = mutableListOf<Map<String, Any?>>()
            parts.add(mapOf("type" to "text", "text" to textPart))
            images.forEach { img ->
                parts.add(mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf("url" to "data:${img.mime};base64,${img.content}")
                ))
            }
            msgList.add(mapOf("role" to "user", "content" to parts))
        } else {
            // 非视觉模型：图片无法直接发送，转存沙箱文件并描述给模型，模型可用 run_shell 等工具读取
            val imgFiles = images.mapIndexed { i, img ->
                val dir = File(context.filesDir, "toolbox/images").apply { mkdirs() }
                val ext = when (img.mime) {
                    "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"
                    else -> "img"
                }
                val f = File(dir, "img_${System.currentTimeMillis()}_$i.$ext")
                try {
                    f.writeBytes(Base64.decode(img.content, Base64.DEFAULT))
                } catch (_: Exception) { f.writeBytes(img.content.toByteArray()) }
                "【图片文件 ${i + 1}】${f.name}（沙箱路径 toolbox/images/${f.name}，大小 ${f.length() / 1024}KB，类型 ${img.mime}）"
            }.joinToString("\n")
            val desc = "$textPart\n\n用户发送了图片（当前模型为非视觉模型，未作为图片直接发送）：\n$imgFiles\n如需分析这些文件，可用 run_shell 等工具读取沙箱内文件。"
            msgList.add(mapOf("role" to "user", "content" to desc))
        }

        // 流式 / 非流式由配置决定
        val streaming = model.stream

        val body = mutableMapOf<String, Any?>(
            "model" to model.model,
            "messages" to msgList,
            "temperature" to model.temperature
        )
        if (streaming) body["stream"] = true
        if (tools.isNotEmpty()) {
            body["tools"] = tools.map { def ->
                mapOf(
                    "type" to "function",
                    "function" to mapOf(
                        "name" to def.name,
                        "description" to def.description,
                        "parameters" to (try { gson.fromJson(def.parameters, Map::class.java) } catch (_: Exception) { emptyMap<String, Any?>() })
                    )
                )
            }
            body["tool_choice"] = "auto"
        }
        // 思考参数：on=开启 / off=显式关闭 / auto=按模型识别（识别不到不加）
        buildThinkingRequestParams(model).forEach { (k, v) -> body[k] = v }
        // 自定义请求体参数：合并到 body 顶层，覆盖同名默认参数
        model.customBodyParams.forEach { (k, v) -> body[k] = parseJsonValue(v) }

        val requestBody = gson.toJson(body)
        val conn = openConnection(URL(chatCompletionsEndpoint(model.apiUrl))).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${model.apiKey}")
            if (streaming) setRequestProperty("Accept", "text/event-stream")
            // 自定义请求头
            model.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            doOutput = true; doInput = true
            connectTimeout = 20_000; readTimeout = 60_000
        }

        try {
            conn.outputStream.use { it.write(requestBody.toByteArray(StandardCharsets.UTF_8)); it.flush() }
            if (isCancelled()) return@withContext SendResult(null, null, System.currentTimeMillis() - startTime)
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                throw Exception("API 错误 ($code): ${err.take(200)}")
            }
            val (content, reasoning, toolCalls) = if (streaming) {
                parseStreamingResponse(conn, onChunk, isCancelled)
            } else {
                parseNonStreamingResponse(conn, isCancelled)
            }
            if (content == null && toolCalls.isEmpty()) {
                SendResult(null, null, System.currentTimeMillis() - startTime)
            } else {
                SendResult(content, reasoning, System.currentTimeMillis() - startTime, toolCalls)
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 上下文摘要：把历史对话文本用当前模型压缩成中文要点摘要（非流式、无工具、无 JSON 协议）。
     * 失败抛异常，由调用方回退窗口截断。
     */
    suspend fun summarize(historyText: String, config: AppConfig): String = withContext(Dispatchers.IO) {
        val model = config.models.find { it.id == config.activeModelId }
            ?: throw Exception("未选择模型，无法压缩上下文")
        if (model.apiKey.isBlank() || model.model.isBlank()) throw Exception("模型配置不完整，无法压缩上下文")
        val body = mapOf(
            "model" to model.model,
            "stream" to false,
            "messages" to listOf(
                mapOf("role" to "system", "content" to SUMMARY_PROMPT),
                mapOf("role" to "user", "content" to historyText)
            ),
            "max_tokens" to 1024,
            "temperature" to 0.3
        )
        val requestBody = gson.toJson(body)
        val conn = openConnection(URL(chatCompletionsEndpoint(model.apiUrl))).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${model.apiKey}")
            model.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            doOutput = true; doInput = true
            connectTimeout = 20_000; readTimeout = 60_000
        }
        try {
            conn.outputStream.use { it.write(requestBody.toByteArray(StandardCharsets.UTF_8)); it.flush() }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                throw Exception("API 错误 ($code): ${err.take(200)}")
            }
            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            val j = gson.fromJson(text, Map::class.java) as? Map<*, *>
            val content = (((j?.get("choices") as? List<*>)?.getOrNull(0) as? Map<*, *>)?.get("message") as? Map<*, *>)?.get("content") as? String
            content?.trim()?.takeIf { it.isNotBlank() } ?: throw Exception("摘要返回为空")
        } finally {
            conn.disconnect()
        }
    }

    /** 流式 SSE 解析：同步收集正文（delta.content）与思考过程（delta.reasoning / reasoning_details） */
    private fun parseStreamingResponse(
        conn: HttpURLConnection,
        onChunk: (content: String, reasoning: String) -> Unit,
        isCancelled: () -> Boolean
    ): Triple<String?, String?, List<ToolCallData>> {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolAcc = mutableMapOf<Int, Triple<String, String, String>>()  // index -> (id, name, args)
        val reader = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
        try {
            var line: String? = reader.readLine()
            while (line != null) {
                if (isCancelled()) return Triple(null, null, emptyList())
                val trimmed = line.trim()
                val isDone = trimmed == "data: [DONE]" || trimmed == "data:[DONE]"
                if (trimmed.startsWith("data:") && !isDone) {
                    try {
                        val data = trimmed.removePrefix("data:").trim()
                        if (data.isNotEmpty()) {
                            val parsed = gson.fromJson(data, Map::class.java)
                            val delta = (((parsed["choices"] as? List<*>)?.getOrNull(0) as? Map<*, *>)?.get("delta") as? Map<*, *>) ?: emptyMap<String, Any?>()
                            val deltaContent = delta["content"] as? String
                            if (!deltaContent.isNullOrBlank()) content.append(deltaContent)
                            val deltaReasoning = (delta["reasoning"] as? String) ?: (delta["reasoning_content"] as? String)
                            if (!deltaReasoning.isNullOrBlank()) reasoning.append(deltaReasoning)
                            (delta["reasoning_details"] as? List<*>)?.forEach { part ->
                                val text = (part as? Map<*, *>)?.get("text") as? String
                                if (!text.isNullOrBlank()) reasoning.append(text)
                            }
                            // 工具调用增量累积（按 index 分段拼接）
                            (delta["tool_calls"] as? List<*>)?.forEach { tc ->
                                val tcMap = tc as? Map<*, *> ?: return@forEach
                                val idx = (tcMap["index"] as? Number)?.toInt() ?: return@forEach
                                val cur = toolAcc[idx] ?: Triple("", "", "")
                                toolAcc[idx] = Triple(
                                    cur.first.ifBlank { tcMap["id"] as? String ?: "" },
                                    cur.second.ifBlank { (tcMap["function"] as? Map<*, *>)?.get("name") as? String ?: "" },
                                    cur.third + ((tcMap["function"] as? Map<*, *>)?.get("arguments") as? String ?: "")
                                )
                            }
                            if (deltaContent != null || deltaReasoning != null) {
                                onChunk(content.toString(), reasoning.toString())
                            }
                        }
                    } catch (_: Exception) {}
                }
                line = reader.readLine()
            }
        } finally {
            try { reader.close() } catch (_: Exception) {}
        }
        // 部分模型把思考过程以 <|thinking|>…<|/thinking|> 等标签直接放入正文，剥离进思考通道
        val (clean, tagged) = extractThinkingTags(content.toString())
        if (tagged.isNotEmpty() && reasoning.isEmpty()) reasoning.append(tagged)
        val calls = toolAcc.values
            .filter { it.second.isNotBlank() }
            .map { ToolCallData(it.first.ifBlank { "call_${System.currentTimeMillis()}" }, it.second, it.third) }
        return Triple(clean, reasoning.toString(), calls)
    }

    private fun parseNonStreamingResponse(conn: HttpURLConnection, isCancelled: () -> Boolean): Triple<String?, String?, List<ToolCallData>> {
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        if (isCancelled()) return Triple(null, null, emptyList())
        val resp = gson.fromJson(body, Map::class.java)
        val message = ((resp["choices"] as? List<*>)?.getOrNull(0) as? Map<*, *>)?.get("message") as? Map<*, *>
        val content = message?.get("content") as? String ?: ""
        val reasoning = (message?.get("reasoning") as? String) ?: (message?.get("reasoning_content") as? String) ?: ""
        val calls = (message?.get("tool_calls") as? List<*>)?.mapNotNull { tc ->
            val m = tc as? Map<*, *> ?: return@mapNotNull null
            val fn = m["function"] as? Map<*, *> ?: return@mapNotNull null
            ToolCallData(m["id"] as? String ?: "call_${System.currentTimeMillis()}", fn["name"] as? String ?: "", fn["arguments"] as? String ?: "")
        }?.filter { it.name.isNotBlank() } ?: emptyList()
        val (clean, tagged) = extractThinkingTags(content)
        return Triple(clean, if (reasoning.isNotBlank()) reasoning else tagged, calls)
    }

    /** 对话 JSON 协议提示词：要求模型以 JSON 回复，done 字段控制是否自主续写（加固版：面向大多数模型） */
    private val JSON_PROTOCOL_PROMPT = buildString {
        append("回复格式协议（务必严格遵守）：\n")
        append("1. 你的每一条回复正文都必须是一个 JSON 对象，形如 {\"content\":\"正文内容\",\"done\":true}。\n")
        append("2. 只允许输出这一个 JSON 对象，前后不要有任何其它文字、解释、注释，也不要用 ``` 代码块或任何标记包裹它。\n")
        append("3. JSON 只有两个字段：\n")
        append("   - content：你要对用户说的话，可以是多行文本，支持 Markdown；\n")
        append("   - done：只能是布尔值 true 或 false（不要写成字符串 \"true\"/\"false\"，也不要省略）。\n")
        append("4. done 的含义：true = 已经完整回答完毕，本回合结束；false = 还没说完（例如还要补充细节、继续推进多步任务、想再追问澄清、把回答分多段给出），系统会自动让你接着输出下一段，直到输出 done=true 为止。\n")
        append("5. 示例：\n")
        append("   回答完毕：{\"content\":\"你好！我是你的 AI 助手。\",\"done\":true}\n")
        append("   还没说完：{\"content\":\"这个问题分三步。第一步……\",\"done\":false}\n")
        append("6. 简单问题一次答完即可，直接 done=true，不要为了多说话故意拆成多段。\n")
        append("7. 特别注意：content 里写你要说的话本身，不要写\"这是一个JSON\"之类的描述；不要在 content 里重复协议规则。")
    }

    /**
     * 解析模型回复的 JSON 协议（content + done）。
     * 兼容：整体 JSON、```json 围栏包裹、前后夹带说明文字（取首尾大括号间子串）。
     * 解析失败时降级为普通文本（content=原文，done=true），保证不兼容协议的模型也能正常聊天。
     */
    fun parseJsonReply(text: String): JsonReply {
        val t = text.trim()
        parseJsonObject(t)?.let { return it }
        // 去掉 ```json ... ``` 围栏
        val fence = Regex("(?s)```[a-zA-Z]*\\s*(\\{[\\s\\S]*?\\})\\s*```").find(t)
        if (fence != null) {
            parseJsonObject(fence.groupValues[1].trim())?.let { return it }
        }
        // 取首个 { 到末尾 } 的子串
        val first = t.indexOf('{')
        val last = t.lastIndexOf('}')
        if (first >= 0 && last > first) {
            parseJsonObject(t.substring(first, last + 1))?.let { return it }
        }
        return JsonReply(t, true)
    }

    /** 上下文摘要提示词：要求模型把历史对话压缩成要点 */
    private val SUMMARY_PROMPT = buildString {
        append("你是一个对话摘要器。把下面这段对话压缩成简洁的中文要点摘要，按要点分行输出，不超过 300 字。\n")
        append("必须保留：用户身份/偏好/重要事实/已做决定/关键结论/未完成事项/约定。\n")
        append("不要虚构、不要复述对话原文、不要输出寒暄。只输出摘要正文。")
    }

    private fun parseJsonObject(s: String): JsonReply? = try {
        val obj = gson.fromJson(s, Map::class.java) as? Map<*, *> ?: return null
        val content = (obj["content"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        // done 兼容布尔与字符串两种写法（弱模型可能输出 "done": "false"）
        val done = when (val d = obj["done"]) {
            is Boolean -> d
            is String -> d.equals("true", ignoreCase = true)
            else -> true
        }
        JsonReply(content, done)
    } catch (_: Exception) { null }

    /** Agent 模式系统提示词：说明工具与自主决策规则，确保模型使用工具 */    private fun agentSystemPrompt(tools: List<ToolDef>): String = buildString {
        append("你当前处于 Agent 模式，可使用以下工具：\n")
        tools.forEach { t ->
            append("- 工具名 ").append(t.name).append("：").append(t.description)
            append("（参数 JSON Schema：").append(t.parameters).append("）\n")
        }
        append("使用规则：\n")
        append("1. 需要精确计算、实时时间、读取文件、运行命令等场景，先调用合适工具，不要凭记忆猜测\n")
        append("2. 调用工具后等待结果，基于实际结果回答；结果不充分可继续调用相关工具\n")
        append("3. 每完成一步你都要自主决策下一步：继续调用工具深化任务，或输出最终回答结束回合\n")
        append("4. 输出最终回答后立即结束回合，不要无意义地反复调用工具\n")
        append("5. 若工具返回错误，可修正参数重试一次；仍失败则如实告知用户\n")
        append("6. 全程使用简体中文回答\n")
        append("7. 回复支持 Markdown：展示图片用 ![描述](图片URL)，提供网页/文件下载链接用 [文字](https://…)，可放正文任意位置；网络图片可直接引用 URL\n")
        append("8. 记忆纪律：系统已在上下文中自动注入最近记忆摘要，通常无需查询。仅当需要用户过去的信息（如\"我之前说过什么\"\"我的偏好\"\"上次聊的什么\"）时才调用 recall_memories；日常闲聊、简单提问直接回答，禁止为回答问题而先查记忆。确需回忆时一次查清，同一轮不要多次重复查询记忆\n")
        append("9. 高效执行：能用已有信息回答就直接回答；不调用工具也能完成的简单任务不要调用工具；不要为了展示能力或流程完整而做多余动作，一切以最快满足用户当前需求为准\n")
        append("10. 信息不足先提问：用户需求不明确、缺少关键信息（如要生成什么内容、目标细节、涉及哪个项目）时，先向用户提问澄清，再动手执行；不要臆测用户意图\n")
    }

    /**
     * 从正文中剥离思考标签块：
     * <|thinking|>…<|/thinking|>、...、<reasoning>…</reasoning>、```thinking\n…```
     * 返回 (干净正文, 拼接的思考内容)；无标签时原样返回。
     */
    private fun extractThinkingTags(text: String): Pair<String, String> {
        if (text.isEmpty()) return Pair(text, "")
        val pattern = Regex(
            "<\\|thinking\\|>[\\s\\S]*?<\\|/thinking\\|>" +
                "|(?s)<reasoning>[\\s\\S]*?</reasoning>" +
                "|(?s)```thinking\\n[\\s\\S]*?```" +
                "|(?s)```reasoning\\n[\\s\\S]*?```" +
                "|<thinking>[\\s\\S]*?</thinking>"
        )
        if (!pattern.containsMatchIn(text)) return Pair(text, "")
        val tagged = StringBuilder()
        var last = 0
        val cleaned = StringBuilder()
        for (match in pattern.findAll(text)) {
            cleaned.append(text, last, match.range.first)
            val inner = patternContent(match.value)
            if (inner.isNotEmpty()) tagged.append(inner).append("\n\n")
            last = match.range.last + 1
        }
        cleaned.append(text, last, text.length)
        return Pair(cleaned.toString().trim(), tagged.toString().trim())
    }

    private fun patternContent(block: String): String {
        // 去掉包裹标签，保留内部内容
        var s = block
        for (tag in listOf("<|thinking|>", "<|/thinking|>", "<thinking>", "</thinking>", "<reasoning>", "</reasoning>", "```thinking", "```reasoning", "```")) {
            s = s.replace(tag, "")
        }
        return s.trim()
    }

    /** 由任意形式的 API 地址推导 /v1/models 端点（兼容完整端点与 v1 根地址） */
    private fun modelsEndpoint(apiUrl: String): String {
        val t = apiUrl.trim()
        val base = when {
            t.endsWith("/chat/completions") -> t.removeSuffix("/chat/completions")
            t.endsWith("/models") -> t.removeSuffix("/models").trimEnd('/')
            else -> t.trimEnd('/')
        }.trimEnd('/')
        return if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
    }

    /** 由 API 地址推导 chat completions 端点：完整端点原样使用，v1 根地址自动拼接 */
    private fun chatCompletionsEndpoint(apiUrl: String): String {
        val t = apiUrl.trim()
        return when {
            t.endsWith("/chat/completions") -> t
            t.endsWith("/models") -> t.removeSuffix("/models") + "chat/completions"
            else -> t.trimEnd('/') + "/chat/completions"
        }
    }

    /** 拉取可用模型列表（使用传入的模型配置，而非已保存的配置） */
    suspend fun fetchAvailableModels(model: ModelConfig): List<String> = withContext(Dispatchers.IO) {
        if (model.apiKey.isBlank() || model.apiUrl.isBlank()) return@withContext emptyList()
        try {
            val conn = openConnection(URL(modelsEndpoint(model.apiUrl))).apply {
                setRequestProperty("Authorization", "Bearer ${model.apiKey}")
                applyExtraHeaders(this, model)
                connectTimeout = 10_000; readTimeout = 10_000
            }
            return@withContext try {
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val resp = gson.fromJson(body, Map::class.java)
                    (resp["data"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }?.filter { it.isNotBlank() } ?: emptyList()
                } else emptyList()
            } finally { conn.disconnect() }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * SSH 工具实现：读取设置页配置的连接，执行远程命令并返回输出。
     * 密码/私钥二选一（私钥优先）；连接/执行均带超时；输出截断 8000 字符。
     */
    override suspend fun sshExecute(command: String): String = withContext(Dispatchers.IO) {
        val ssh = configFlow.first().sshConfig
        if (!ssh.enabled) return@withContext "错误：SSH 工具未启用（请在设置 → SSH 中配置并启用）"
        if (command.isBlank()) return@withContext "错误：缺少参数 command"
        if (ssh.host.isBlank() || ssh.username.isBlank()) return@withContext "错误：SSH 配置不完整（缺少主机或用户名）"
        if (ssh.privateKey.isBlank() && ssh.password.isBlank()) return@withContext "错误：SSH 未配置密码或私钥"
        var session: Session? = null
        try {
            val jsch = JSch()
            // Android 6（API 23）及部分老设备缺少 EC（椭圆曲线）算法的 JCE 实现
            // （AlgorithmParameters EC not found），而 JSch 默认优先使用 ecdh-sha2-nistp* 密钥交换。
            // 禁用 EC 密钥交换，改用 curve25519 / diffie-hellman 系列（同样安全、纯 Java 实现，老设备可用）。
            JSch.setConfig(
                "kex",
                "curve25519-sha256,curve25519-sha256@libssh.org," +
                    "diffie-hellman-group16-sha512,diffie-hellman-group15-sha512," +
                    "diffie-hellman-group14-sha256,diffie-hellman-group14-sha1," +
                    "diffie-hellman-group-exchange-sha256,diffie-hellman-group-exchange-sha1," +
                    "diffie-hellman-group1-sha1"
            )
            if (ssh.privateKey.isNotBlank()) {
                jsch.addIdentity("uchat_ssh_key", ssh.privateKey.toByteArray(), null, null)
            }
            session = jsch.getSession(ssh.username, ssh.host, ssh.port).apply {
                if (ssh.privateKey.isBlank()) setPassword(ssh.password)
                setConfig("StrictHostKeyChecking", "no")
                setConfig("PreferredAuthentications", if (ssh.privateKey.isNotBlank()) "publickey,password" else "password,keyboard-interactive")
                timeout = 15_000
                connect(15_000)
            }
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val out = StringBuilder()
            val err = StringBuilder()
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                executor.submit {
                    try { out.append(channel.inputStream.readBytes().toString(Charsets.UTF_8)) } catch (_: Exception) {}
                }
                executor.submit {
                    try { err.append(channel.errStream.readBytes().toString(Charsets.UTF_8)) } catch (_: Exception) {}
                }
                channel.connect()
                var waited = 0
                while (!channel.isClosed && waited < 30_000) {
                    Thread.sleep(200); waited += 200
                }
                if (waited >= 30_000) {
                    channel.disconnect(); session.disconnect(); session = null
                    return@withContext "错误：SSH 命令执行超时（30 秒）"
                }
                executor.shutdown()
                executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
                val stdout = out.toString().trim()
                val stderr = err.toString().trim()
                val result = if (stderr.isNotBlank()) "$stdout\n[stderr] $stderr" else stdout
                (if (result.isBlank()) "（命令执行完成，无输出）" else result).take(8000)
            } finally {
                try { channel.disconnect() } catch (_: Exception) {}
                executor.shutdownNow()
            }
        } catch (e: Exception) {
            "错误：SSH 执行失败 —— ${e.message ?: e.javaClass.simpleName}"
        } finally {
            try { session?.disconnect() } catch (_: Exception) {}
        }
    }

    /** 测试 API 连接（使用传入的模型配置，而非已保存的配置） */
    suspend fun testApiConnection(model: ModelConfig): ApiTestResult = withContext(Dispatchers.IO) {
        if (model.apiUrl.isBlank()) return@withContext ApiTestResult(false, "API 地址为空")
        if (model.apiKey.isBlank()) return@withContext ApiTestResult(false, "API Key 为空")
        try {
            val conn = openConnection(URL(modelsEndpoint(model.apiUrl))).apply {
                setRequestProperty("Authorization", "Bearer ${model.apiKey}")
                applyExtraHeaders(this, model)
                connectTimeout = 10_000; readTimeout = 10_000
            }
            return@withContext try {
                val code = conn.responseCode
                when {
                    code in 200..299 -> ApiTestResult(true, "连接成功 (HTTP $code)")
                    code == 401 -> ApiTestResult(false, "认证失败：API Key 无效 (401)")
                    code == 403 -> ApiTestResult(false, "权限不足 (403)")
                    else -> ApiTestResult(false, "服务器返回错误 (HTTP $code)")
                }
            } finally { conn.disconnect() }
        } catch (e: java.net.UnknownHostException) {
            ApiTestResult(false, "无法解析域名，请检查 API 地址")
        } catch (e: java.net.SocketTimeoutException) {
            ApiTestResult(false, "连接超时，请检查网络或 API 地址")
        } catch (e: Exception) {
            ApiTestResult(false, "连接失败: ${e.message}")
        }
    }

    /** 附加自定义请求头（GET 类请求） */
    private fun applyExtraHeaders(conn: HttpURLConnection, model: ModelConfig) {
        model.customHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
    }

    // ─────────────────────────────────────────────────────────────
    // 配置 + 聊天记录 备份 / 恢复 / 清空
    // ─────────────────────────────────────────────────────────────

    data class BackupData(
        val schema: Int = 1,
        val config: AppConfig? = null,
        val chats: Map<String, Chat>? = null
    )

    suspend fun exportAll(): String = withContext(Dispatchers.IO) {
        val config = configFlow.first()
        val chats = chatsFlow.first()
        gson.toJson(BackupData(schema = 1, config = config, chats = chats))
    }

    suspend fun importAll(json: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val backup = gson.fromJson(json, BackupData::class.java)
            val config = backup.config ?: return@withContext false
            val chats = backup.chats ?: return@withContext false
            saveConfig(config.copy(models = normalizeModels(config.models)))
            saveChats(chats)
            true
        } catch (_: Exception) { false }
    }

    suspend fun clearAll() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.models)
            prefs.remove(Keys.activeModelId)
            prefs.remove(Keys.darkMode)
            prefs.remove(Keys.chats)
        }
    }
}

data class ApiTestResult(val success: Boolean, val message: String)

/** 发送结果：content 为 null 表示被取消 */
data class SendResult(
    val content: String?,
    val reasoning: String?,
    val durationMs: Long,
    /** Agent 模式：assistant 请求的工具调用（无则空） */
    val toolCalls: List<ToolCallData> = emptyList()
)

/** 对话 JSON 协议解析结果：content 为过滤后的正文，done=false 表示 AI 要求继续续写 */
data class JsonReply(
    val content: String,
    val done: Boolean
)

/** 发送附件：kind=image（content 为 base64，走多模态 content 数组）| kind=text（文本文件内容直接拼入正文） */
data class SendAttachment(
    val kind: String,
    val name: String,
    val mime: String,
    val content: String,
    val size: Long = 0L
)

/** 粗略 token 估算：中文/CJK 按 1 token/字，其余按 4 字符/token（用于上下文压缩阈值判定） */
fun estimateTokens(vararg parts: String): Int {
    var tokens = 0
    for (s in parts) {
        var cjk = 0
        var rest = 0
        for (ch in s) {
            if (ch.code >= 0x2E80) cjk++ else rest++
        }
        tokens += cjk + rest / 4
    }
    return tokens
}

/** 把历史消息扁平化为可摘要的纯文本（含摘要标记与工具结果简述） */
fun flattenHistoryForSummary(msgs: List<ChatMessage>): String {
    val sb = StringBuilder()
    for (m in msgs) {
        when (m.role) {
            "system" -> sb.append("已压缩摘要: ").append(m.content).append('\n')
            "tool" -> sb.append("工具 ").append(m.toolName ?: "").append(" 返回: ").append(m.content.take(200)).append('\n')
            "user" -> sb.append("用户: ").append(m.content).append('\n')
            else -> sb.append("AI: ").append(m.content).append('\n')
        }
    }
    return sb.toString()
}

/** markdown 资源块：kind=image（![alt](url)）| kind=file（[name](文件 url)）；cleanOffset 为该语法在干净文本中的起始位置（用于原位置渲染） */
data class MdBlock(
    val kind: String,
    val text: String,
    val url: String,
    val cleanOffset: Int
)

/**
 * 从 markdown 正文提取图片与文件链接块。
 * 返回 (干净文本, 块列表)：资源语法从正文中移除（文件链接不再保留名字文本），
 * 块按出现顺序排列，cleanOffset 指向该资源在干净文本中的插入位置，UI 据此原地渲染。
 */
fun extractMarkdownBlocks(content: String): Pair<String, List<MdBlock>> {
    val pattern = Regex(
        """!\[([^\]]*)\]\(([^)\s]+)\)|\[([^\]]+)\]\(((?:https?|file)://[^)\s]*\.(?:pdf|zip|apk|exe|docx?|xlsx?|pptx?|tar|gz|7z|bin|iso|mp3|mp4|wav|rar|dmg|msi|deb|rpm|log|csv|json|xml|sql)(?:\?[^)\s]*)?)\)"""
    )
    val blocks = mutableListOf<MdBlock>()
    val sb = StringBuilder(content.length)
    var deleted = 0
    var last = 0
    for (m in pattern.findAll(content)) {
        sb.append(content, last, m.range.first)
        val cleanOffset = m.range.first - deleted
        val isImage = m.value.startsWith("!")
        if (isImage) {
            val alt = m.groupValues[1].trim()
            val url = m.groupValues[2].trim()
            if (url.startsWith("http") || url.startsWith("data:image") || url.startsWith("file:")) {
                blocks.add(MdBlock("image", alt.ifBlank { "图片" }, url, cleanOffset))
                deleted += m.value.length
            } else {
                sb.append(m.value) // 不支持的图片语法保留原文
            }
        } else {
            blocks.add(MdBlock("file", m.groupValues[3], m.groupValues[4], cleanOffset))
            deleted += m.value.length
        }
        last = m.range.last + 1
    }
    sb.append(content, last, content.length)
    return Pair(sb.toString(), blocks)
}

