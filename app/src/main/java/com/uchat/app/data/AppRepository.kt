package com.uchat.app.data

import android.content.Context
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
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
    val thinking: Boolean = false,
    /** 附加到每次请求的自定义请求头（发消息 / 测试连接 / 获取模型列表） */
    val customHeaders: Map<String, String> = emptyMap(),
    /** Our Free Model 免费网关（opencode.ai 免密车道）：自动注入 opencode 指纹头、
     *  声明工具指纹、强制流式输出，无需额外配置 */
    val freeGatewayFingerprint: Boolean = false,
    /** 思考等级：off / light / balanced / deep（仅免费网关中支持思考的模型生效，
     *  对应输出预算 关闭 / 2K / 8K / 模型上限） */
    val thinkingLevel: String = "balanced"
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
    /** Super Chat（Agent 模式）开关：开启后模型可自主调用工具 */
    val superChat: Boolean = false,
    /** 工具授权阈值：dangerLevel >= 该值的工具调用需用户确认（2=敏感级起，3=高风险级起，4=全部自动） */
    val toolApprovalThreshold: Int = 3,
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

data class CodeBlock(
    val language: String,
    val code: String,
    val startIndex: Int,
    val endIndex: Int,
)

/**
 * Markdown segments — used by AIChatApp to render mixed code + prose content.
 * plainSegments: ordered list of (annotatedString, rawText) for non-code-block regions
 * codeBlocks: ordered list of extracted fenced code blocks
 */
data class MarkdownSegments(
    val plainSegments: List<Pair<AnnotatedString, String>>,
    val codeBlocks: List<CodeBlock>,
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

    // 1. Extract fenced code blocks with positions
    val codeBlockRegex = Regex("""```(\w*)[\s\S]*?```""")
    val codeMatches = codeBlockRegex.findAll(text).toList()
    val codeBlocks = codeMatches.map { match ->
        val raw = match.value
        val firstNewline = raw.indexOf('\n')
        val lang = if (firstNewline > 3) raw.substring(3, firstNewline).trim() else ""
        val inner = if (firstNewline > 0 && raw.endsWith("```")) {
            raw.substring(firstNewline + 1, raw.length - 4)
        } else {
            raw.substring(firstNewline + 1)
        }
        CodeBlock(language = lang, code = inner, startIndex = match.range.first, endIndex = match.range.last + 1)
    }

    // 2. Split text into plain regions between code blocks
    val plainSegments = mutableListOf<Pair<AnnotatedString, String>>()
    var pos = 0
    for (block in codeBlocks) {
        if (block.startIndex > pos) {
            val plain = text.substring(pos, block.startIndex)
            plainSegments.add(Pair(buildAnnotated(plain, codeBg, codeText), plain))
        }
        pos = block.endIndex
    }
    if (pos < text.length) {
        val plain = text.substring(pos)
        plainSegments.add(Pair(buildAnnotated(plain, codeBg, codeText), plain))
    }
    if (plainSegments.isEmpty()) {
        plainSegments.add(Pair(buildAnnotated("", codeBg, codeText), ""))
    }

    return MarkdownSegments(plainSegments, codeBlocks)
}

private fun buildAnnotated(text: String, codeBg: Color, codeText: Color): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.split("\n")
        for (line in lines) {
            when {
                line.startsWith("```") -> append("\n")
                line.startsWith("# ") -> { pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)); append(line.removePrefix("# ")); pop(); append("\n") }
                line.startsWith("## ") -> { pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)); append(line.removePrefix("## ")); pop(); append("\n") }
                line.startsWith("### ") -> { pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)); append(line.removePrefix("### ")); pop(); append("\n") }
                line.startsWith("- ") || line.startsWith("* ") -> { append("  \u2022  "); append(line.drop(2)); append("\n") }
                line.startsWith("> ") -> { pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color.Gray)); append("  \u2507 "); append(line.drop(2)); pop(); append("\n") }
                line == "---" || line == "***" -> { append("─────────────────────────────────\n") }
                line.contains("`") -> parseInlineCode(line, codeBg, codeText)
                else -> { append(line); append("\n") }
            }
        }
    }
}

private fun AnnotatedString.Builder.parseInlineCode(line: String, bg: Color, textColor: Color) {
    var i = 0
    while (i < line.length) {
        val start = line.indexOf('`', i)
        if (start == -1) { append(line.substring(i)); append("\n"); break }
        if (start > i) append(line.substring(i, start))
        val end = line.indexOf('`', start + 1)
        if (end == -1) { append(line.substring(start)); append("\n"); break }
        pushStyle(SpanStyle(background = bg, fontFamily = FontFamily.Monospace, color = textColor))
        append(line.substring(start, end + 1))
        pop()
        i = end + 1
    }
}

class AppRepository(private val context: Context) {
    private val gson = Gson()

    /** 内置 ISRG Root X1 信任 + TLS1.2 的 SocketFactory（失败时回退系统默认） */
    private val sslSocketFactory: SSLSocketFactory? by lazy { TlsConfig.createSocketFactory(context) }

    private object Keys {
        val models = stringPreferencesKey("models_v2")
        val activeModelId = stringPreferencesKey("active_model_id_v2")
        val darkMode = booleanPreferencesKey("dark_mode_v2")
        val messageFontSize = floatPreferencesKey("message_font_size_v2")
        val themeMode = stringPreferencesKey("theme_mode_v2")
        val accentColor = stringPreferencesKey("accent_color_v2")
        val superChat = booleanPreferencesKey("super_chat_v2")
        val toolApprovalThreshold = intPreferencesKey("tool_approval_threshold_v2")
        val chats = stringPreferencesKey("chats_v2")
    }

    val configFlow: Flow<AppConfig> = context.dataStore.data.map { prefs ->
        val modelsJson = prefs[Keys.models]
        val models: List<ModelConfig> = if (modelsJson != null) {
            try {
                val type = object : TypeToken<List<ModelConfig>>() {}.type
                gson.fromJson<List<ModelConfig>>(modelsJson, type).map { m ->
                    // 旧版本配置无 thinkingLevel 字段（Gson 解码为 null）：免费模型默认均衡，其余关闭
                    if (m.thinkingLevel == null) {
                        m.copy(thinkingLevel = if (m.freeGatewayFingerprint) "balanced" else "off")
                    } else m
                }
            } catch (_: Exception) { AppConfig().models }
        } else { AppConfig().models }
        val activeId = prefs[Keys.activeModelId] ?: models.firstOrNull()?.id ?: ""
        val dark = prefs[Keys.darkMode] ?: false
        val font = prefs[Keys.messageFontSize] ?: 14f
        val themeMode = prefs[Keys.themeMode] ?: if (dark) "dark" else "system"
        val accent = prefs[Keys.accentColor] ?: "blue"
        val superChat = prefs[Keys.superChat] ?: false
        val threshold = prefs[Keys.toolApprovalThreshold] ?: 3
        AppConfig(models = models, activeModelId = activeId, darkMode = dark,
                  messageFontSize = font, themeMode = themeMode, accentColor = accent,
                  superChat = superChat, toolApprovalThreshold = threshold)
    }

    val chatsFlow: Flow<Map<String, Chat>> = context.dataStore.data.map { prefs ->
        val chatsJson = prefs[Keys.chats] ?: "{}"
        try {
            val type = object : TypeToken<Map<String, Chat>>() {}.type
            gson.fromJson(chatsJson, type) ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    suspend fun saveConfig(config: AppConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.models] = gson.toJson(config.models)
            prefs[Keys.activeModelId] = config.activeModelId
            prefs[Keys.darkMode] = config.darkMode
            prefs[Keys.messageFontSize] = config.messageFontSize
            prefs[Keys.themeMode] = config.themeMode
            prefs[Keys.accentColor] = config.accentColor
            prefs[Keys.superChat] = config.superChat
            prefs[Keys.toolApprovalThreshold] = config.toolApprovalThreshold
        }
    }

    suspend fun saveChats(chats: Map<String, Chat>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.chats] = gson.toJson(chats)
        }
    }

    /** 统一的连接入口：HTTPS 连接注入内置证书信任的 SocketFactory */
    private fun openConnection(url: URL): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        if (url.protocol == "https" && conn is HttpsURLConnection) {
            sslSocketFactory?.let { conn.sslSocketFactory = it }
        }
        return conn
    }

    /**
     * 发送消息（OpenAI 兼容 /chat/completions）。
     * @param onChunk 流式增量回调（累计全文正文 + 思考过程）；非流式时仅回调一次
     * @param isCancelled 外部取消标志；content 为 null 表示请求被取消
     * @param sessionSeed 会话种子：Our Free Model 免费车道按 session 计配额，
     *        同一对话固定使用同一 session id 以避免 429
     */
    suspend fun sendMessage(
        config: AppConfig,
        messages: List<ChatMessage>,
        newMessage: String,
        onChunk: (content: String, reasoning: String) -> Unit,
        isCancelled: () -> Boolean = { false },
        sessionSeed: String? = null,
        attachments: List<SendAttachment> = emptyList(),
        tools: List<ToolDef> = emptyList()
    ): SendResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val model = config.models.find { it.id == config.activeModelId }
            ?: throw Exception("未选择模型，请在设置中配置")
        if (model.apiKey.isBlank()) throw Exception("API Key 未填写")
        if (model.model.isBlank()) throw Exception("模型名称未填写")

        val msgList = mutableListOf<Map<String, Any?>>()
        val agentPrompt = if (tools.isNotEmpty()) agentSystemPrompt(tools) else null
        val systemPrompt = when {
            agentPrompt != null && model.systemPrompt.isNotBlank() -> model.systemPrompt + "\n\n" + agentPrompt
            agentPrompt != null -> agentPrompt
            else -> model.systemPrompt
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
        } else {
            val parts = mutableListOf<Map<String, Any?>>()
            parts.add(mapOf("type" to "text", "text" to textPart))
            images.forEach { img ->
                parts.add(mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf("url" to "data:${img.mime};base64,${img.content}")
                ))
            }
            msgList.add(mapOf("role" to "user", "content" to parts))
        }

        // Our Free Model 免费车道只允许流式
        val streaming = if (model.freeGatewayFingerprint) true else model.stream

        val body = mutableMapOf<String, Any?>(
            "model" to model.model,
            "messages" to msgList,
            "temperature" to model.temperature
        )
        if (streaming) body["stream"] = true
        if (tools.isNotEmpty() && !model.freeGatewayFingerprint) {
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
        if (model.freeGatewayFingerprint) {
            // 免费车道：工具指纹声明（网关要求四件套，否则 403 FreeTierError）+ 思考等级预算
            body["tools"] = OpenCodeGateway.fingerprintTools()
            body["tool_choice"] = "none"
            OpenCodeEffort.budgetFor(model.thinkingLevel, model.model)?.let { body["max_tokens"] = it }
        } else if (model.thinking) {
            body["thinking"] = true
        }

        val requestBody = gson.toJson(body)
        val conn = openConnection(URL(model.apiUrl)).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${model.apiKey}")
            if (streaming) setRequestProperty("Accept", "text/event-stream")
            // 自定义请求头
            model.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            // 免费网关指纹头
            if (model.freeGatewayFingerprint) {
                OpenCodeGateway.fingerprintHeaders(
                    sessionId = OpenCodeGateway.sessionId(sessionSeed ?: "chat"),
                    requestId = null
                ).forEach { (k, v) -> setRequestProperty(k, v) }
            }
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
                            val deltaReasoning = delta["reasoning"] as? String
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
        val reasoning = message?.get("reasoning") as? String ?: ""
        val calls = (message?.get("tool_calls") as? List<*>)?.mapNotNull { tc ->
            val m = tc as? Map<*, *> ?: return@mapNotNull null
            val fn = m["function"] as? Map<*, *> ?: return@mapNotNull null
            ToolCallData(m["id"] as? String ?: "call_${System.currentTimeMillis()}", fn["name"] as? String ?: "", fn["arguments"] as? String ?: "")
        }?.filter { it.name.isNotBlank() } ?: emptyList()
        val (clean, tagged) = extractThinkingTags(content)
        return Triple(clean, if (reasoning.isNotBlank()) reasoning else tagged, calls)
    }

    /** Agent 模式系统提示词：说明工具与自主决策规则，确保模型使用工具 */
    private fun agentSystemPrompt(tools: List<ToolDef>): String = buildString {
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

    /** 由任意形式的 API 地址推导 /v1/models 端点 */
    private fun modelsEndpoint(apiUrl: String): String {
        val base = apiUrl
            .substringBefore("/v1/chat/completions")
            .substringBefore("/chat/completions")
            .substringBefore("/v1/models")
            .trimEnd('/')
        return "$base/v1/models"
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
                    code == 403 -> ApiTestResult(
                        false,
                        if (model.freeGatewayFingerprint)
                            "访问被拒 (403)：免费通道可能地区受限或暂不可用，可重试或换模型"
                        else
                            "权限不足 (403)"
                    )
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

    /** 附加自定义请求头与免费网关指纹头（GET 类请求） */
    private fun applyExtraHeaders(conn: HttpURLConnection, model: ModelConfig) {
        model.customHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (model.freeGatewayFingerprint) {
            OpenCodeGateway.fingerprintHeaders(sessionId = null, requestId = null)
                .forEach { (k, v) -> conn.setRequestProperty(k, v) }
        }
    }

    // ── Our Free Model：免费模型清单 + 实测速度 ─────────────────

    /** 拉取 OpenCode 网关免费模型清单（带指纹头），附本地能力表信息 */
    suspend fun fetchOpenCodeFreeModels(): List<OpenCodeFreeModel> = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection(URL(OpenCodeGateway.MODELS_URL)).apply {
                setRequestProperty("Authorization", "Bearer public")
                OpenCodeGateway.fingerprintHeaders(sessionId = null, requestId = null)
                    .forEach { (k, v) -> setRequestProperty(k, v) }
                connectTimeout = 15_000; readTimeout = 15_000
            }
            return@withContext try {
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val resp = gson.fromJson(body, Map::class.java)
                    val ids = (resp["data"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("id") as? String } ?: emptyList()
                    OpenCodeCatalog.buildFromIds(ids)
                } else emptyList()
            } finally { conn.disconnect() }
        } catch (_: Exception) { emptyList() }
    }

    /** 实测单个免费模型的首字延迟与可用性（最小流式请求，使用独立测速 session） */
    suspend fun testOpenCodeModelSpeed(model: OpenCodeFreeModel): OpenCodeSpeedResult = withContext(Dispatchers.IO) {
        if (!model.chatWire) return@withContext OpenCodeSpeedResult(false, -1, "走 /responses 接口，暂不支持")
        val startTime = System.currentTimeMillis()
        try {
            val body = mapOf(
                "model" to model.id,
                "messages" to listOf(mapOf("role" to "user", "content" to "hi")),
                "stream" to true,
                "max_tokens" to 8,
                "tools" to OpenCodeGateway.fingerprintTools(),
                "tool_choice" to "none"
            )
            val requestBody = gson.toJson(body)
            val conn = openConnection(URL(OpenCodeGateway.CHAT_URL)).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer public")
                setRequestProperty("Accept", "text/event-stream")
                OpenCodeGateway.fingerprintHeaders(
                    sessionId = OpenCodeGateway.sessionId("probe-${model.id}"),
                    requestId = null
                ).forEach { (k, v) -> setRequestProperty(k, v) }
                doOutput = true; doInput = true
                connectTimeout = 10_000; readTimeout = 60_000
            }
            return@withContext try {
                conn.outputStream.use { it.write(requestBody.toByteArray(StandardCharsets.UTF_8)); it.flush() }
                val code = conn.responseCode
                when {
                    code !in 200..299 -> {
                        val errText = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                        when {
                            code == 403 || errText.contains("RegionError") -> OpenCodeSpeedResult(false, -1, "地区受限")
                            code == 429 || errText.contains("FreeUsageLimitError") -> OpenCodeSpeedResult(false, -1, "免费额度限制")
                            else -> OpenCodeSpeedResult(false, -1, "HTTP $code")
                        }
                    }
                    else -> {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
                        var ttft = -1L
                        try {
                            var line = reader.readLine()
                            while (line != null) {
                                val trimmed = line.trim()
                                if (trimmed.startsWith("data:")) {
                                    val data = trimmed.removePrefix("data:").trim()
                                    if (data == "[DONE]") break
                                    try {
                                        val parsed = gson.fromJson(data, Map::class.java)
                                        val delta = (((parsed["choices"] as? List<*>)?.getOrNull(0) as? Map<*, *>)?.get("delta") as? Map<*, *>) ?: emptyMap<String, Any?>()
                                        if (!(delta["content"] as? String).isNullOrBlank() || !(delta["reasoning"] as? String).isNullOrBlank()) {
                                            ttft = System.currentTimeMillis() - startTime
                                            break
                                        }
                                    } catch (_: Exception) {}
                                }
                                line = reader.readLine()
                            }
                        } finally {
                            try { reader.close() } catch (_: Exception) {}
                        }
                        if (ttft > 0) OpenCodeSpeedResult(true, ttft, "首字 ${ttft}ms")
                        else OpenCodeSpeedResult(true, -1, "无输出")
                    }
                }
            } finally { conn.disconnect() }
        } catch (e: java.net.SocketTimeoutException) {
            OpenCodeSpeedResult(false, -1, "连接超时")
        } catch (e: Exception) {
            OpenCodeSpeedResult(false, -1, "连接失败")
        }
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
            saveConfig(config)
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

/**
 * Our Free Model 免费车道（opencode.ai 免密网关）协议封装。
 *
 * 网关要点（均经 2026-09-25 实测验证）：
 * - 公共凭据 `Authorization: Bearer public`，无真实密钥；
 * - 请求必须带 `opencode/1.18.31` UA 与 `x-opencode-*` 指纹头，否则 Cloudflare 403；
 * - 免费档只允许流式（stream=true）；
 * - 请求体必须声明 bash/glob/grep/read 四个工具，否则 403 FreeTierError；
 * - 按 session id 计配额（429），同一对话应复用同一 session id。
 */
object OpenCodeGateway {

    /** chat 完成端点 */
    const val CHAT_URL = "https://opencode.ai/zen/v1/chat/completions"

    /** 模型清单端点 */
    const val MODELS_URL = "https://opencode.ai/zen/v1/models"

    /** 网关要求的 UA：需包含 opencode/ 且版本 >= 1.17 */
    private const val CLIENT_UA = "deepseek-harness/0.1.7 (+https://github.com/deepseek-ai/deepseek-harness) opencode/1.18.31"

    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private val FINGERPRINT_TOOLS = listOf("bash", "glob", "grep", "read")

    private fun sha256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

    private fun base62(bytes: ByteArray): String = buildString {
        for (b in bytes) append(BASE62[(b.toInt() and 0xFF) % 62])
    }

    /** 由种子派生稳定的网关会话 id（同一对话固定，避免触发 429） */
    fun sessionId(seed: String): String {
        val digest = sha256("our-free-model\u0000$seed")
        val hex = digest.take(6).joinToString("") { "%02x".format(it) }
        val tail = if (digest.size > 20) digest.copyOfRange(6, 20) else digest.copyOfRange(6, digest.size)
        return "ses_$hex${base62(tail)}"
    }

    /** 每次请求的 request id */
    fun requestId(): String {
        val digest = sha256(System.nanoTime().toString())
        val hex = digest.take(6).joinToString("") { "%02x".format(it) }
        return "msg_$hex${base62(digest.copyOfRange(6, 20))}"
    }

    /** 网关指纹请求头 */
    fun fingerprintHeaders(sessionId: String?, requestId: String?): Map<String, String> = mapOf(
        "user-agent" to CLIENT_UA,
        "x-opencode-client" to "desktop",
        "x-opencode-session" to (sessionId ?: sessionId("global")),
        "x-opencode-request" to (requestId ?: requestId()),
        "x-opencode-project" to "global"
    )

    /** 免费档要求的工具指纹声明（占位工具，不可用） */
    fun fingerprintTools(): List<Map<String, Any>> =
        FINGERPRINT_TOOLS.map { name ->
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to name,
                    "description" to "This tool is currently unavailable and must not be used.",
                    "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>())
                )
            )
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

/** OpenCode 免费车道模型条目（能力来自本地能力表，网关接口只返回 id） */
data class OpenCodeFreeModel(
    val id: String,
    val name: String,
    val reasoning: Boolean,
    val vision: Boolean,
    val contextWindow: Int,
    val maxOutput: Int,
    val canDisableThinking: Boolean,
    val chatWire: Boolean
)

/** 免费模型测速结果：latencyMs>0 表示首字延迟（毫秒） */
data class OpenCodeSpeedResult(
    val ok: Boolean,
    val latencyMs: Long,
    val note: String
)

/** 发送附件：kind=image（content 为 base64，走多模态 content 数组）| kind=text（文本文件内容直接拼入正文） */
data class SendAttachment(
    val kind: String,
    val name: String,
    val mime: String,
    val content: String,
    val size: Long = 0L
)

/** 模型能力快照（编辑表单/思考等级展示用） */
data class ModelCapabilities(
    val reasoning: Boolean,
    val maxOutput: Int,
    val canDisableThinking: Boolean
)

/**
 * OpenCode 免费车道模型能力表（移植自 dsh-our-free-model 插件 catalog.js）。
 * 网关 /zen/v1/models 只返回 id，没有能力字段，故按模型 id 匹配本地能力表。
 */
object OpenCodeCatalog {

    private data class Cap(
        val pattern: Regex,
        val vision: Boolean,
        val reasoning: Boolean,
        val contextWindow: Int,
        val maxOutput: Int,
        val canDisableThinking: Boolean? = null
    )

    private val CAPABILITIES = listOf(
        Cap(Regex("^mimo.*v2\\.6"), true, true, 1048576, 131072, false),
        Cap(Regex("^mimo.*v2\\.5"), true, true, 1048576, 131072, false),
        Cap(Regex("^mimo"), true, true, 262144, 131072, null),
        Cap(Regex("^muse.?spark"), true, true, 1048576, 131072, null),
        Cap(Regex("^nemotron"), false, true, 128000, 32768, null),
        Cap(Regex("^ling"), false, true, 128000, 32768, null),
        Cap(Regex("^space.?bunny"), true, true, 262144, 65536, null),
        Cap(Regex("^union"), true, false, 262144, 131072, null),
        Cap(Regex("^deepseek"), false, true, 128000, 64000, null),
        Cap(Regex("^jev"), false, false, 32768, 4096, null)
    )

    private val DISPLAY_NAMES = mapOf(
        "mimo-v2.6-flash-free" to "MiMo V2.6 Flash",
        "mimo-v2.5-free" to "MiMo V2.5",
        "muse-spark-1.3-contributor-free" to "Muse Spark 1.3",
        "muse-spark-1.2-contributor-free" to "Muse Spark 1.2",
        "nemotron-3-ultra-free" to "Nemotron 3 Ultra",
        "nemotron-3.5-lightning-free" to "Nemotron 3.5 Lightning",
        "ling-3.0-flash-fin-free" to "Ling 3.0 Flash Fin",
        "space-bunny-free" to "Space Bunny",
        "union-alpha" to "Union Alpha",
        "deepseek-v4-flash-free" to "DeepSeek V4 Flash",
        "jev-1.13-free" to "Jev 1.13"
    )

    private val ALWAYS_FREE = setOf("union-alpha", "space-bunny-free")

    /** 走 /responses 接口（app 暂不支持）的模型 */
    private val RESPONSES_MODELS = setOf("muse-spark-1.2-contributor-free", "muse-spark-1.3-contributor-free")

    /** 是否免费车道模型（id 后缀带 free，或白名单） */
    fun isFreeLane(id: String): Boolean {
        if (id in ALWAYS_FREE) return true
        return Regex("(?:^|[-_])free(?:$|[-_.])").containsMatchIn(id)
    }

    fun isResponsesWire(id: String): Boolean = id in RESPONSES_MODELS

    /** 能力表查询，未命中时回退默认（reasoning、32K） */
    fun capabilitiesOf(id: String): ModelCapabilities {
        for (cap in CAPABILITIES) if (cap.pattern.matches(id)) {
            return ModelCapabilities(
                reasoning = cap.reasoning,
                maxOutput = cap.maxOutput,
                canDisableThinking = cap.canDisableThinking ?: true
            )
        }
        return ModelCapabilities(reasoning = true, maxOutput = 32768, canDisableThinking = true)
    }

    /** 展示名：优先已知映射，否则把 id 转标题大小写 */
    fun displayName(id: String): String {
        DISPLAY_NAMES[id]?.let { return it }
        val words = id.replace(Regex("[-_.]+"), " ").trim().split(" ")
        return words.joinToString(" ") { word ->
            if (word.isEmpty()) word
            else if (word[0].isDigit()) word
            else word.replaceFirstChar { it.uppercase() }
        }
    }

    /** 由网关返回的 id 列表构建能力增强的免费模型清单（按清单顺序，去重） */
    fun buildFromIds(ids: List<String>): List<OpenCodeFreeModel> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<OpenCodeFreeModel>()
        for (raw in ids) {
            val id = raw.trim()
            if (id.isEmpty() || !isFreeLane(id) || id in seen) continue
            seen.add(id)
            val cap = CAPABILITIES.firstOrNull { it.pattern.matches(id) }
            val caps = capabilitiesOf(id)
            result.add(
                OpenCodeFreeModel(
                    id = id,
                    name = displayName(id),
                    reasoning = caps.reasoning,
                    vision = cap?.vision ?: false,
                    contextWindow = cap?.contextWindow ?: 131072,
                    maxOutput = caps.maxOutput,
                    canDisableThinking = caps.canDisableThinking,
                    chatWire = !isResponsesWire(id)
                )
            )
        }
        return result
    }
}

/**
 * 思考等级 → 输出预算（移植自插件 effort.js）：
 * 精简 2048 / 均衡 8192 / 深思 = 模型上限；思考不可关闭的模型（MiMo V2.5/2.6）各档翻倍，
 * 因为思考与正文共用同一份预算。
 */
object OpenCodeEffort {

    const val MIN_BUDGET = 512

    val LEVELS = listOf("light", "balanced", "deep")

    /** 返回该等级应下发网关的 max_tokens；off 或模型不支持思考返回 null（不限制） */
    fun budgetFor(level: String, modelId: String): Int? {
        if (level == "off") return null
        val ceiling = when (level) {
            "light" -> 2048
            "balanced" -> 8192
            "deep" -> null // 模型上限
            else -> return null
        }
        val caps = OpenCodeCatalog.capabilitiesOf(modelId)
        if (!caps.reasoning) return null
        val capacity = caps.maxOutput
        val c = when {
            ceiling == null -> capacity
            caps.canDisableThinking == false -> ceiling * 2
            else -> ceiling
        }
        return maxOf(MIN_BUDGET, minOf(c, capacity))
    }

    /** 展示用预算文本，如 "2K" / "128K" / "不限制" */
    fun budgetLabel(level: String, modelId: String): String {
        val budget = budgetFor(level, modelId) ?: return "不限制"
        return if (budget >= 1024) "${budget / 1024}K" else "$budget"
    }
}
