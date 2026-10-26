package com.uchat.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.os.Build
import com.uchat.app.data.ApiTestResult
import com.uchat.app.data.AppConfig
import com.uchat.app.data.AppRepository
import com.uchat.app.data.Chat
import com.uchat.app.data.ChatMessage
import com.uchat.app.data.MemoryEntry
import com.uchat.app.data.ModelConfig
import com.uchat.app.data.SendAttachment
import com.uchat.app.data.SendResult
import com.uchat.app.data.ToolCallData
import com.uchat.app.data.ToolDef
import com.uchat.app.data.estimateTokens
import com.uchat.app.data.flattenHistoryForSummary
import com.uchat.app.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 进行中的工具调用（Agent 模式展示用） */
data class ToolActivity(
    val name: String,
    val status: String,   // running / done / error
    val detail: String = ""
)

/** 待用户授权的工具调用（dangerLevel >= 阈值时弹窗） */
data class PendingToolApproval(
    val callName: String,
    val description: String,
    val arguments: String,
    val dangerLevel: Int
)

data class ChatUiState(
    val config: AppConfig = AppConfig(),
    val currentChatId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    /** 流式输出当前累计内容（打字机效果），非空表示正在流式返回 */
    val streamingContent: String? = null,
    /** 流式输出当前累计的思考过程（reasoning），空表示无思考 */
    val streamingReasoning: String? = null,
    /** AI 自主续写中（JSON 协议 done=false，客户端正请求 AI 继续输出） */
    val autoContinuing: Boolean = false,
    /** 当前是否有可停止的生成任务 */
    val cancellable: Boolean = false,
    val showSettings: Boolean = false,
    val availableModels: List<String> = emptyList(),
    val fetchingModels: Boolean = false,
    val editingModel: ModelConfig? = null,
    val isNewModel: Boolean = false,
    val chatList: List<Pair<String, Chat>> = emptyList(),
    val toastMessage: String? = null,
    /** 最近一次生成失败的错误（保留现场，UI 显示报错与重试） */
    val generationError: String? = null,
    val apiTestResult: ApiTestResult? = null,
    val isTestingApi: Boolean = false,
    /** 请求体预览（查看请求体功能），null = 不显示 */
    val requestBodyPreview: String? = null,
    val editingTitle: String? = null,
    /** 待写入文件的备份 JSON（由 UI 端 SAF 写入后清除） */
    val exportPayload: String? = null,
    /** 待发送附件（图片/文本文件），随下一条消息一起发出后清空 */
    val attachments: List<SendAttachment> = emptyList(),
    /** 进行中的工具调用（Agent 模式） */
    val toolActivity: ToolActivity? = null,
    /** Termux 环境安装状态（设置页 Termux 卡片展示） */
    val termuxState: com.uchat.app.env.TermuxRuntime.State = com.uchat.app.env.TermuxRuntime.State.NotInstalled,
    /** 待用户授权（L3 工具调用） */
    val pendingApproval: PendingToolApproval? = null,
    /** 长期记忆列表（设置页查看/管理） */
    val memories: List<MemoryEntry> = emptyList(),
    /** 自动重试状态：请求失败后间隔重试中（UI 显示错误消息与第几次） */
    val retryInfo: RetryInfo? = null,
)

/** 自动重试信息：attempt 为第几次尝试（1 起），maxAttempts 为总次数，message 为错误详情 */
data class RetryInfo(
    val attempt: Int,
    val maxAttempts: Int,
    val message: String
)

class ChatViewModel(
    private val repository: AppRepository,
    private val toolRegistry: ToolRegistry,
    private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** 续写提示词：模型上一轮 done=false 时追加的 system 消息 */
    private val CONTINUE_PROMPT = "你上一轮回复中 done 为 false，表示你还没说完。请继续输出剩余内容，仍按回复格式协议输出 JSON。"

    /** 生成期间启动前台服务，切换后台/锁屏不中断（顺带为后续代理服务器常驻做准备） */
    /** 当前生成任务 */
    private var generateJob: Job? = null

    /** 取消标志：点"停止生成"后置 true，网络层据此中断 */
    @Volatile
    private var cancelled = false

    /** 当前回合是否已因模型不支持工具声明而降级为纯聊天（每次 sendToChat 重置） */
    @Volatile
    private var toolDegraded = false

    init {
        // Termux 环境状态订阅（下载/解压/就绪进度实时进 uiState）
        viewModelScope.launch {
            try {
                val rt = (appContext.applicationContext as? com.uchat.app.AIChatApplication)?.termuxRuntime
                if (rt != null) {
                    rt.state.collect { st -> _uiState.update { it.copy(termuxState = st) } }
                }
            } catch (_: Exception) {}
        }
        viewModelScope.launch {
            // ── 持续订阅：配置 / 聊天记录 ──
            combine(repository.configFlow, repository.chatsFlow) { config, chats ->
                Pair(config, chats.toList().sortedByDescending { it.second.lastUpdated })
            }.collect { (config, chatList) ->
                val currentChatId = _uiState.value.currentChatId
                val messages = if (currentChatId != null) {
                    chatList.find { it.first == currentChatId }?.second?.messages ?: emptyList()
                } else emptyList()
                _uiState.update { it.copy(config = config, chatList = chatList, messages = messages) }
                // 主页模式：不自动新建空对话，用户发送消息时才创建会话
            }
        }
    }

    // ── 基础 UI 状态 ─────────────────────────────────────────────

    fun showSettings() = _uiState.update { it.copy(showSettings = true) }

    fun hideSettings() {
        stopGenerating()
        _uiState.update {
            it.copy(
                showSettings = false, editingModel = null, isNewModel = false,
                apiTestResult = null, availableModels = emptyList()
            )
        }
    }

    fun clearToast() { _uiState.update { it.copy(toastMessage = null) } }

    /** 切换会话/退出时中断进行中的生成 */
    private fun interruptCurrentGenerate() {
        cancelled = true
        generateJob?.cancel()
    }

    fun goToList() {
        interruptCurrentGenerate()
        _uiState.update {
            it.copy(
                currentChatId = null, messages = emptyList(),
                isLoading = false, streamingContent = null, streamingReasoning = null, cancellable = false
            )
        }
    }

    fun newChat() {
        interruptCurrentGenerate()
        val id = "chat_${UUID.randomUUID()}"
        viewModelScope.launch {
            val newChat = Chat(id = id, title = "新对话", messages = emptyList())
            val allChats = repository.chatsFlow.first().toMutableMap()
            allChats[id] = newChat
            repository.saveChats(allChats)
            _uiState.update {
                it.copy(
                    currentChatId = id, messages = emptyList(), showSettings = false,
                    isLoading = false, streamingContent = null, streamingReasoning = null, cancellable = false
                )
            }
        }
    }

    fun selectChat(chatId: String) {
        interruptCurrentGenerate()
        viewModelScope.launch {
            val msgs = repository.chatsFlow.first()[chatId]?.messages ?: emptyList()
            _uiState.update {
                it.copy(
                    currentChatId = chatId, messages = msgs, showSettings = false,
                    isLoading = false, streamingContent = null, streamingReasoning = null, cancellable = false
                )
            }
        }
    }

    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            if (chatId == _uiState.value.currentChatId) interruptCurrentGenerate()
            val allChats = repository.chatsFlow.first().toMutableMap()
            allChats.remove(chatId)
            repository.saveChats(allChats)
            if (_uiState.value.currentChatId == chatId) {
                _uiState.update {
                    it.copy(
                        currentChatId = null, messages = emptyList(),
                        isLoading = false, streamingContent = null, streamingReasoning = null, cancellable = false
                    )
                }
            }
        }
    }

    fun clearCurrentChat() {
        val chatId = _uiState.value.currentChatId ?: return
        interruptCurrentGenerate()
        viewModelScope.launch {
            val allChats = repository.chatsFlow.first().toMutableMap()
            val chat = allChats[chatId] ?: return@launch
            allChats[chatId] = chat.copy(messages = emptyList(), lastUpdated = System.currentTimeMillis())
            repository.saveChats(allChats)
            _uiState.update {
                it.copy(
                    messages = emptyList(),
                    isLoading = false, streamingContent = null, streamingReasoning = null, cancellable = false,
                    toastMessage = "对话已清空"
                )
            }
        }
    }

    // ── 标题编辑（由 editingTitle 状态驱动弹窗） ────────────────

    fun startEditTitle() {
        viewModelScope.launch {
            val chatId = _uiState.value.currentChatId ?: return@launch
            val title = repository.chatsFlow.first()[chatId]?.title ?: ""
            _uiState.update { it.copy(editingTitle = title) }
        }
    }

    fun cancelEditTitle() { _uiState.update { it.copy(editingTitle = null) } }

    fun updateChatTitle(chatId: String, newTitle: String) {
        viewModelScope.launch {
            val allChats = repository.chatsFlow.first().toMutableMap()
            val chat = allChats[chatId] ?: return@launch
            allChats[chatId] = chat.copy(title = newTitle.take(50), lastUpdated = System.currentTimeMillis())
            repository.saveChats(allChats)
            if (_uiState.value.currentChatId == chatId) {
                _uiState.update { it.copy(editingTitle = null) }
            }
        }
    }

    // ── 发送 / 停止 ──────────────────────────────────────────────

    /** 停止当前生成 */
    /** 待用户授权的挂起门（L3 工具调用时 await） */
    private var toolApprovalGate: CompletableDeferred<Boolean>? = null

    fun stopGenerating() {
        cancelled = true
        generateJob?.cancel()
        toolApprovalGate?.complete(false)
        toolApprovalGate = null
        _uiState.update { it.copy(cancellable = false, pendingApproval = null, toolActivity = null, autoContinuing = false) }
    }

    // ── Agent（Super Chat）模式设置 ─────────────────────────────

    fun setApprovalThreshold(level: Int) {
        viewModelScope.launch {
            val config = _uiState.value.config.copy(toolApprovalThreshold = level.coerceIn(2, 4))
            repository.saveConfig(config)
        }
    }

    fun setMaxAutoRounds(rounds: Int) {
        viewModelScope.launch {
            val config = _uiState.value.config.copy(maxAutoRounds = rounds.coerceIn(0, 50))
            repository.saveConfig(config)
        }
    }

    /** 上下文压缩阈值（k token，0 = 关闭），设置项：32/64/128/256 */
    fun setContextLimitK(k: Int) {
        viewModelScope.launch {
            val config = _uiState.value.config.copy(contextLimitK = if (k <= 0) 0 else k.coerceIn(32, 256))
            repository.saveConfig(config)
        }
    }

    fun saveSshConfig(ssh: com.uchat.app.data.SshConfig) {
        viewModelScope.launch {
            val config = _uiState.value.config.copy(sshConfig = ssh)
            repository.saveConfig(config)
        }
    }

    fun installTermux() {
        viewModelScope.launch {
            try {
                val rt = (appContext.applicationContext as? com.uchat.app.AIChatApplication)?.termuxRuntime ?: return@launch
                rt.install()
            } catch (_: Exception) {}
        }
    }

    fun uninstallTermux() {
        try {
            val rt = (appContext.applicationContext as? com.uchat.app.AIChatApplication)?.termuxRuntime ?: return
            rt.uninstall()
            _uiState.update { it.copy(termuxState = com.uchat.app.env.TermuxRuntime.State.NotInstalled) }
        } catch (_: Exception) {}
    }

    // ── 自动重试：网络/5xx 错误最多重试 10 次，每次间隔 5 秒，UI 显示错误消息 ──

    private fun isRetryable(e: Exception): Boolean {
        if (e is java.io.IOException || e is java.net.SocketTimeoutException) return true
        val m = e.message ?: ""
        return m.contains("API 错误 (5") ||
            m.contains("timeout", ignoreCase = true) ||
            m.contains("连接", ignoreCase = true) ||
            m.contains("网络", ignoreCase = true)
    }

    /** 带自动重试的请求：4xx 先走工具降级（不计次数）；网络/5xx 重试最多 10 次、间隔 5 秒，期间 UI 显示错误 */
    private suspend fun sendWithRetry(
        config: AppConfig,
        messages: List<ChatMessage>,
        text: String,
        onChunk: (String, String) -> Unit,
        attachments: List<SendAttachment>,
        tools: List<ToolDef>,
        memoryNotes: String,
        extraSystem: String?
    ): SendResult {
        val MAX_RETRIES = 10
        val RETRY_DELAY_MS = 5_000L
        var attempt = 0
        while (true) {
            attempt++
            try {
                val r = repository.sendMessage(config, messages, text, onChunk, { cancelled }, attachments, tools, memoryNotes, extraSystem)
                _uiState.update { it.copy(retryInfo = null) }
                return r
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 工具声明不被模型支持（4xx）→ 去掉工具降级重试一次（普通模型也能正常聊天），不计入网络重试次数
                if (!toolDegraded && (e.message?.contains("API 错误 (4") == true)) {
                    toolDegraded = true
                    _uiState.update { it.copy(retryInfo = null) }
                    val r = repository.sendMessage(config, messages, text, onChunk, { cancelled }, attachments, emptyList(), memoryNotes, extraSystem)
                    _uiState.update { it.copy(retryInfo = null) }
                    return r
                }
                if (isRetryable(e) && attempt < MAX_RETRIES) {
                    _uiState.update { s -> s.copy(retryInfo = RetryInfo(attempt, MAX_RETRIES, e.message ?: "网络错误")) }
                    delay(RETRY_DELAY_MS)
                    continue
                }
                throw e
            }
        }
    }

    // ── 上下文压缩：超阈值时把最旧部分用 AI 摘要压缩（失败回退窗口截断） ──

    private suspend fun compactIfNeeded(hist: List<ChatMessage>, config: AppConfig): List<ChatMessage> {
        val limitK = config.contextLimitK
        if (limitK <= 0 || hist.isEmpty()) return hist
        // 固定开销：回复协议提示词 + 工具定义 + 记忆注入（粗估）+ 预留输出空间
        val fixedTokens = 1800
        val histTokens = estimateTokens(*hist.map { it.content }.toTypedArray())
        val budget = limitK * 1000L
        if ((histTokens + fixedTokens).toLong() < budget * 3 / 4) return hist
        // 需要压缩：保留最近约 45% 预算的完整回合，更早的部分做 AI 摘要
        val keepTarget = budget * 3 / 4 * 45 / 100
        var keepTokens = 0L
        var cutIndex = hist.size
        for (i in hist.indices.reversed()) {
            val t = estimateTokens(hist[i].content).toLong()
            if (keepTokens + t > keepTarget) break
            keepTokens += t
            cutIndex = i
        }
        // 调整到回合边界：向前跳过 tool 消息与其调用方 assistant(含工具调用)，避免拆散工作回合
        while (cutIndex > 0) {
            val prev = hist[cutIndex - 1]
            if (prev.role == "tool" || !prev.toolCalls.isNullOrEmpty()) cutIndex-- else break
        }
        if (cutIndex <= 0) return hist   // 保留部分为空：放弃本次压缩
        val toSummarize = hist.take(cutIndex)
        val kept = hist.drop(cutIndex)
        val summary = try {
            repository.summarize(flattenHistoryForSummary(toSummarize), config).trim()
        } catch (_: Exception) {
            ""   // 摘要失败 → 回退：直接丢弃旧部分（窗口截断兜底）
        }
        val head = if (summary.isNotBlank()) {
            listOf(ChatMessage(role = "system", content = "【早期对话摘要】$summary", reasoning = ""))
        } else emptyList()
        return head + kept
    }

    // ── 记忆系统管理（设置页） ──────────────────────────────────

    fun loadMemories() {
        viewModelScope.launch {
            _uiState.update { it.copy(memories = repository.memoriesFlow.first()) }
        }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch {
            repository.deleteMemory(id)
            loadMemories()
        }
    }

    fun clearMemories() {
        viewModelScope.launch {
            repository.clearMemories()
            loadMemories()
        }
    }

    fun approveTool(allow: Boolean) {
        toolApprovalGate?.complete(allow)
        toolApprovalGate = null
        _uiState.update { it.copy(pendingApproval = null) }
    }

    /** 执行工具：dangerLevel >= 阈值先请求用户授权；返回结果字符串（模型可见） */
    private suspend fun runToolWithApproval(call: ToolCallData): String {
        val def = toolRegistry.definitions.find { it.name == call.name }
        if (def == null) return "错误：未知工具 ${call.name}"
        val threshold = _uiState.value.config.toolApprovalThreshold
        if (def.dangerLevel >= threshold) {
            val gate = CompletableDeferred<Boolean>()
            toolApprovalGate = gate
            _uiState.update {
                it.copy(pendingApproval = PendingToolApproval(def.name, def.description, call.arguments, def.dangerLevel))
            }
            val allow = gate.await()
            if (!allow) return "用户拒绝了工具调用 ${def.name}，请向用户解释原因并停止使用该工具"
        }
        return try {
            toolRegistry.execute(def.name, call.arguments)
        } catch (e: Exception) {
            "错误：工具执行异常 —— ${e.message}"
        }
    }

    fun sendMessage(text: String) {
        val state = _uiState.value
        if (state.isLoading) return
        if (state.currentChatId == null) {
            newChatAndSend(text)
            return
        }
        sendToChat(state.currentChatId!!, text)
    }

    private fun sendToChat(chatId: String, text: String) {
        val state = _uiState.value
        val userMsg = ChatMessage(role = "user", content = text)
        cancelled = false
        toolDegraded = false
        generateJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val allChats = repository.chatsFlow.first().toMutableMap()
                val chat = allChats[chatId] ?: return@launch
                allChats[chatId] = chat.copy(
                    messages = chat.messages + userMsg,
                    title = if (chat.messages.isEmpty()) text.take(30) else chat.title,
                    lastUpdated = System.currentTimeMillis()
                )
                repository.saveChats(allChats)
                _uiState.update {
                    it.copy(
                        messages = allChats[chatId]?.messages ?: emptyList(),
                        isLoading = true, streamingContent = null, streamingReasoning = null, cancellable = true,
                        generationError = null
                    )
                }
                val startMsgs = allChats[chatId]?.messages?.filter { it.content.isNotBlank() } ?: emptyList()

                // Agent（Super Chat）模式为默认常开：模型自主决策 —— 说话 → 调工具 → 看结果 → 决定继续或结束
                // 回复走 JSON 协议（content + done）：done=false 时客户端自动续写，直到说完或达到轮数上限
                var hist = startMsgs                       // 演进中的消息链（含本条 userMsg）
                var roundText = text
                var roundAttachments = state.attachments
                var roundExtraSystem: String? = null       // 请求级临时指令（如续写指令），不入对话历史
                var lastToolRequest = false
                var rounds = 0
                var toolRounds = 0
                var autoRounds = 0
                val MAX_TOOL_ROUNDS = 6
                val maxAutoRounds = state.config.maxAutoRounds   // 0 = 无限续写

                // 阶段式持久化：每轮完成立即落盘 + 刷新 UI，断线时保留已生成部分
                suspend fun persistStage() {
                    allChats[chatId] = chat.copy(messages = hist, lastUpdated = System.currentTimeMillis())
                    repository.saveChats(allChats)
                    // 清打字机残留，避免与已落库消息重复显示
                    _uiState.update { it.copy(messages = hist, streamingContent = null, streamingReasoning = null) }
                }

                // 客户端过滤：流式中实时剥离 JSON 壳，只展示正文（解析失败时展示原文）
                val onChunk: (String, String) -> Unit = { content, reasoning ->
                    val reply = repository.parseJsonReply(content)
                    _uiState.update { s ->
                        s.copy(streamingContent = reply.content, streamingReasoning = reasoning)
                    }
                }

                while (true) {
                    rounds++
                    // 上下文压缩：估算超阈值时把最旧部分用 AI 摘要压缩（摘要失败回退窗口截断）
                    val compacted = compactIfNeeded(hist, state.config)
                    if (compacted !== hist) {
                        hist = compacted
                        persistStage()
                    }
                    // 记忆每轮重新读取：AI 可能在本轮通过 remember_memory 写入新记忆，下一轮立即生效
                    val memoryNotes = repository.formatMemoryNotes()
                    val result = sendWithRetry(
                        config = state.config,
                        messages = if (rounds == 1) hist.dropLast(1) else hist,
                        text = roundText,
                        onChunk = onChunk,
                        attachments = if (rounds == 1) roundAttachments else emptyList(),
                        tools = if (toolDegraded) emptyList() else toolRegistry.definitions,
                        memoryNotes = memoryNotes,
                        extraSystem = roundExtraSystem
                    )
                    lastToolRequest = result.toolCalls.isNotEmpty()
                    if (result.content == null && !lastToolRequest) {
                        // 用户取消：保留已发送的用户消息与已生成的阶段内容
                        _uiState.update {
                            it.copy(isLoading = false, streamingContent = null, streamingReasoning = null,
                                    cancellable = false, attachments = emptyList(), autoContinuing = false)
                        }
                        return@launch
                    }
                    val rawContent = result.content ?: ""
                    val reply = repository.parseJsonReply(rawContent)
                    hist = hist + ChatMessage(
                        role = "assistant",
                        content = reply.content,           // 过滤后的正文（剥掉 JSON 壳）
                        reasoning = result.reasoning ?: "",
                        durationMs = result.durationMs,
                        toolCalls = result.toolCalls.ifEmpty { null }
                    )
                    persistStage()   // 阶段式展示：本轮回复立即上屏

                    if (!lastToolRequest) {
                        // 无工具请求：按 JSON 协议决定是否续写
                        toolRounds = 0
                        if (!reply.done && (maxAutoRounds == 0 || autoRounds < maxAutoRounds)) {
                            autoRounds++
                            // 方案 A：续写指令只注入本次请求（extraSystem），不写入对话历史/UI
                            roundExtraSystem = CONTINUE_PROMPT
                            _uiState.update { s -> s.copy(autoContinuing = true) }
                            roundText = ""
                            roundAttachments = emptyList()
                            continue
                        }
                        _uiState.update { s -> s.copy(autoContinuing = false) }
                        if (!reply.done && maxAutoRounds > 0) {
                            _uiState.update { it.copy(toastMessage = "已达到 AI 自主续写上限（$maxAutoRounds 轮），已结束回合") }
                        }
                        break
                    }

                    // 工具请求：执行工具 → 结果作为 tool 消息回传，让模型决定下一步
                    autoRounds = 0
                    toolRounds++
                    roundExtraSystem = null                // 工具轮不携带续写指令
                    for (call in result.toolCalls) {
                        _uiState.update { s -> s.copy(toolActivity = ToolActivity(call.name, "running", call.arguments)) }
                        val output = runToolWithApproval(call)
                        _uiState.update { s ->
                            s.copy(toolActivity = ToolActivity(call.name, if (output.startsWith("错误")) "error" else "done", output.take(120)))
                        }
                        hist = hist + ChatMessage(
                            role = "tool",
                            content = output.ifBlank { "(无输出)" },
                            toolCallId = call.id,
                            toolName = call.name
                        )
                        persistStage()   // 每个工具结果立即上屏
                    }
                    _uiState.update { s -> s.copy(toolActivity = null) }
                    if (toolRounds >= MAX_TOOL_ROUNDS) {
                        _uiState.update { it.copy(toastMessage = "已达到最大工具轮次（$MAX_TOOL_ROUNDS），已提前结束回合") }
                        break
                    }
                    roundText = ""           // 后续轮次不再插入用户文本
                    roundAttachments = emptyList()
                }

                _uiState.update {
                    it.copy(
                        isLoading = false, streamingContent = null, streamingReasoning = null, cancellable = false,
                        messages = hist,
                        attachments = emptyList(), toolActivity = null, autoContinuing = false, generationError = null,
                        retryInfo = null
                    )
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isLoading = false, streamingContent = null, streamingReasoning = null, cancellable = false, retryInfo = null) }
            } catch (e: Exception) {
                // 断线/错误：保留用户消息与已生成的阶段内容，标记可重试（不再删除任何消息）
                _uiState.update {
                    it.copy(
                        isLoading = false, streamingContent = null, streamingReasoning = null, cancellable = false,
                        generationError = e.message ?: "连接中断，请重试", toolActivity = null, retryInfo = null
                    )
                }
            }
        }
    }

    private fun newChatAndSend(text: String) {
        val id = "chat_${UUID.randomUUID()}"
        viewModelScope.launch(Dispatchers.IO) {
            val allChats = repository.chatsFlow.first().toMutableMap()
            allChats[id] = Chat(
                id = id,
                title = text.take(30),
                messages = emptyList(),
                lastUpdated = System.currentTimeMillis()
            )
            repository.saveChats(allChats)
            _uiState.update {
                it.copy(currentChatId = id, messages = emptyList(), showSettings = false)
            }
            // 统一走 sendToChat（含 Super Chat 循环），新对话第一句也能自主调用工具
            sendToChat(id, text)
        }
    }

    /** 重新生成最后一条 AI 回复：截断到最后一条用户消息之前，重发该消息走完整回合（Super Chat 下重跑工具循环） */
    fun regenerateLastResponse() {
        val state = _uiState.value
        if (state.isLoading) return
        val chatId = state.currentChatId ?: return
        val msgs = state.messages
        if (msgs.isEmpty() || msgs.last().role == "user") return
        val lastUserIndex = msgs.indexOfLast { it.role == "user" }
        if (lastUserIndex < 0) return
        val lastUserText = msgs[lastUserIndex].content
        val trimmed = msgs.take(lastUserIndex)   // 不含最后用户消息（sendToChat 会重新追加）

        generateJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val allChats = repository.chatsFlow.first().toMutableMap()
                val chat = allChats[chatId] ?: return@launch
                allChats[chatId] = chat.copy(messages = trimmed, lastUpdated = System.currentTimeMillis())
                repository.saveChats(allChats)
                _uiState.update { it.copy(messages = trimmed) }
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = e.message ?: "重新生成失败") }
            }
        }
        sendToChat(chatId, lastUserText)
    }

    /** 编辑重发：截断到指定消息之前（删除该消息及之后的所有消息） */
    fun truncateFrom(messageId: Long) {
        val chatId = _uiState.value.currentChatId ?: return
        viewModelScope.launch {
            val allChats = repository.chatsFlow.first().toMutableMap()
            val chat = allChats[chatId] ?: return@launch
            val idx = chat.messages.indexOfFirst { it.id == messageId }
            if (idx < 0) return@launch
            allChats[chatId] = chat.copy(
                messages = chat.messages.take(idx),
                lastUpdated = System.currentTimeMillis()
            )
            repository.saveChats(allChats)
            _uiState.update { it.copy(messages = allChats[chatId]?.messages ?: emptyList()) }
        }
    }

    // ── 模型配置 ────────────────────────────────────────────────

    fun setActiveModel(modelId: String) {
        viewModelScope.launch {
            val config = _uiState.value.config.copy(activeModelId = modelId)
            repository.saveConfig(config)
        }
    }

    fun toggleDarkMode() {
        viewModelScope.launch {
            val config = _uiState.value.config.copy(darkMode = !_uiState.value.config.darkMode)
            repository.saveConfig(config)
        }
    }

    fun setMessageFontSize(size: Float) {
        viewModelScope.launch {
            val config = _uiState.value.config.copy(messageFontSize = size.coerceIn(12f, 20f))
            repository.saveConfig(config)
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            val config = _uiState.value.config.copy(themeMode = mode)
            repository.saveConfig(config)
        }
    }

    fun setAccentColor(color: String) {
        viewModelScope.launch {
            val config = _uiState.value.config.copy(accentColor = color)
            repository.saveConfig(config)
        }
    }

    fun addAttachment(attachment: SendAttachment) {
        _uiState.update { it.copy(attachments = it.attachments + attachment) }
    }

    fun removeAttachment(index: Int) {
        _uiState.update { it.copy(attachments = it.attachments.filterIndexed { i, _ -> i != index }) }
    }

    fun startAddModel() {
        _uiState.update {
            it.copy(
                editingModel = ModelConfig(
                    id = "model_${UUID.randomUUID()}",
                    name = "",
                    apiUrl = "",
                    apiKey = "",
                    model = ""
                ),
                isNewModel = true,
                showSettings = true,
                apiTestResult = null,
                availableModels = emptyList()
            )
        }
    }

    fun startEditModel(model: ModelConfig) {
        _uiState.update {
            it.copy(
                editingModel = model, isNewModel = false,
                showSettings = true, apiTestResult = null, availableModels = emptyList()
            )
        }
    }

    fun updateEditingModel(model: ModelConfig) {
        _uiState.update { it.copy(editingModel = model) }
    }

    fun cancelEditModel() {
        _uiState.update { it.copy(editingModel = null, isNewModel = false) }
    }

    fun saveEditingModel() {
        val editing = _uiState.value.editingModel ?: return
        viewModelScope.launch {
            val newId = if (_uiState.value.isNewModel) "model_${UUID.randomUUID()}" else editing.id
            val models = if (_uiState.value.isNewModel) {
                _uiState.value.config.models + editing.copy(id = newId)
            } else {
                _uiState.value.config.models.map { if (it.id == editing.id) editing.copy(id = newId) else it }
            }
            val activeId = if (_uiState.value.config.activeModelId == editing.id || _uiState.value.isNewModel) newId else _uiState.value.config.activeModelId
            val config = _uiState.value.config.copy(models = models, activeModelId = activeId)
            repository.saveConfig(config)
            _uiState.update {
                it.copy(
                    editingModel = null, isNewModel = false,
                    availableModels = emptyList(), toastMessage = "已保存"
                )
            }
        }
    }

    fun deleteEditingModel() {
        val editing = _uiState.value.editingModel ?: return
        if (_uiState.value.isNewModel) {
            _uiState.update { it.copy(editingModel = null, isNewModel = false) }
            return
        }
        viewModelScope.launch {
            val models = _uiState.value.config.models.filter { it.id != editing.id }
            val activeModelId = if (_uiState.value.config.activeModelId == editing.id) models.firstOrNull()?.id ?: "" else _uiState.value.config.activeModelId
            val config = _uiState.value.config.copy(models = models, activeModelId = activeModelId)
            repository.saveConfig(config)
            _uiState.update {
                it.copy(
                    editingModel = null, isNewModel = false,
                    availableModels = emptyList(), toastMessage = "已删除"
                )
            }
        }
    }

    /** 测试连接：使用正在编辑的模型配置（修复原 bug：之前测的是已保存的旧配置） */
    fun testApi() {
        val editing = _uiState.value.editingModel ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingApi = true, apiTestResult = null) }
            val result = repository.testApiConnection(editing)
            _uiState.update { it.copy(isTestingApi = false, apiTestResult = result) }
        }
    }

    /** 获取模型列表：使用正在编辑的模型配置 */
    fun fetchModels() {
        val editing = _uiState.value.editingModel ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(fetchingModels = true, availableModels = emptyList()) }
            val models = repository.fetchAvailableModels(editing)
            _uiState.update { it.copy(fetchingModels = false, availableModels = models) }
        }
    }

    /** 关闭模型选择弹窗（清空列表） */
    fun clearAvailableModels() {
        _uiState.update { it.copy(availableModels = emptyList()) }
    }

    /** 生成请求体预览（含自定义请求头），用于验证配置 */
    fun previewRequestBody() {
        val editing = _uiState.value.editingModel ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val preview = repository.previewRequestBody(editing)
            _uiState.update { it.copy(requestBodyPreview = preview) }
        }
    }

    /** 关闭请求体预览 */
    fun clearRequestPreview() {
        _uiState.update { it.copy(requestBodyPreview = null) }
    }

    // ── 导出聊天（文本格式） ─────────────────────────────────────

    fun exportChats() {
        viewModelScope.launch {
            val chats = repository.chatsFlow.first()
            val markdown = buildString {
                appendLine("# 聊天记录导出")
                appendLine()
                chats.values.sortedByDescending { it.lastUpdated }.forEach { chat ->
                    appendLine("## ${chat.title}")
                    appendLine()
                    chat.messages.forEach { msg ->
                        val roleLabel = if (msg.role == "user") "👤 用户" else "🤖 AI"
                        val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(msg.timestamp))
                        appendLine("**$roleLabel** ($timeStr)")
                        appendLine(msg.content)
                        appendLine()
                    }
                    appendLine("---")
                    appendLine()
                }
            }
            _uiState.update { it.copy(toastMessage = "export:$markdown") }
        }
    }

    // ── 备份 / 恢复 / 清空 ──────────────────────────────────────

    fun prepareExport() {
        viewModelScope.launch {
            val json = repository.exportAll()
            _uiState.update { it.copy(exportPayload = json) }
        }
    }

    fun clearExportPayload() { _uiState.update { it.copy(exportPayload = null) } }

    fun importData(json: String) {
        viewModelScope.launch {
            val ok = repository.importAll(json)
            _uiState.update {
                it.copy(toastMessage = if (ok) "恢复成功" else "恢复失败：数据格式无效或文件为空")
            }
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            repository.clearAll()
            _uiState.update {
                it.copy(
                    currentChatId = null, messages = emptyList(),
                    editingModel = null, isNewModel = false, showSettings = false,
                    isLoading = false, streamingContent = null, streamingReasoning = null, cancellable = false,
                    availableModels = emptyList(), apiTestResult = null,
                    toastMessage = "已清空所有数据"
                )
            }
        }
    }
}

class ChatViewModelFactory(
    private val repository: AppRepository,
    private val toolRegistry: com.uchat.app.tools.ToolRegistry,
    private val appContext: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ChatViewModel(repository, toolRegistry, appContext) as T
    }
}