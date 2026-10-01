package com.aichat.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.os.Build
import com.aichat.app.data.ApiTestResult
import com.aichat.app.data.AppConfig
import com.aichat.app.data.AppRepository
import com.aichat.app.data.Chat
import com.aichat.app.data.ChatMessage
import com.aichat.app.data.ModelConfig
import com.aichat.app.data.OpenCodeFreeModel
import com.aichat.app.data.OpenCodeSpeedResult
import com.aichat.app.data.SendAttachment
import com.aichat.app.data.ToolCallData
import com.aichat.app.data.ToolDef
import com.aichat.app.tools.ToolRegistry
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
    val editingTitle: String? = null,
    /** 待写入文件的备份 JSON（由 UI 端 SAF 写入后清除） */
    val exportPayload: String? = null,
    /** OpenCode 免费模型清单（已从网关拉取并附能力信息） */
    val freeModels: List<OpenCodeFreeModel> = emptyList(),
    /** 是否已成功拉取过免费模型清单 */
    val freeModelsLoaded: Boolean = false,
    /** 正在拉取免费模型清单 */
    val fetchingFreeModels: Boolean = false,
    /** 免费模型测速结果（id -> 结果） */
    val freeSpeedResults: Map<String, OpenCodeSpeedResult> = emptyMap(),
    /** 测速进度，如 "3/11" */
    val speedTestProgress: String? = null,
    /** 拉取/测速错误提示 */
    val freeModelError: String? = null,
    /** 待发送附件（图片/文本文件），随下一条消息一起发出后清空 */
    val attachments: List<SendAttachment> = emptyList(),
    /** 进行中的工具调用（Agent 模式） */
    val toolActivity: ToolActivity? = null,
    /** 待用户授权（L3 工具调用） */
    val pendingApproval: PendingToolApproval? = null,
)

class ChatViewModel(
    private val repository: AppRepository,
    private val toolRegistry: ToolRegistry,
    private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** 生成期间启动前台服务，切换后台/锁屏不中断（顺带为后续代理服务器常驻做准备） */
    /** 当前生成任务 */
    private var generateJob: Job? = null

    /** 取消标志：点"停止生成"后置 true，网络层据此中断 */
    @Volatile
    private var cancelled = false

    init {
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
        _uiState.update { it.copy(cancellable = false, pendingApproval = null, toolActivity = null) }
    }

    // ── Super Chat（Agent 模式） ────────────────────────────────

    fun toggleSuperChat() {
        viewModelScope.launch {
            val config = _uiState.value.config.copy(superChat = !_uiState.value.config.superChat)
            repository.saveConfig(config)
        }
    }

    fun setApprovalThreshold(level: Int) {
        viewModelScope.launch {
            val config = _uiState.value.config.copy(toolApprovalThreshold = level.coerceIn(2, 4))
            repository.saveConfig(config)
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

                // Super Chat（Agent 模式）：模型自主决策 —— 说话 → 调工具 → 看结果 → 决定继续或结束
                // 免费车道强制 tool_choice=none，不支持工具，自动退化为普通聊天
                val activeModel = state.config.models.find { it.id == state.config.activeModelId }
                val superChatEnabled = state.config.superChat && activeModel?.freeGatewayFingerprint != true
                val tools = if (superChatEnabled) toolRegistry.definitions else emptyList()

                var hist = startMsgs                       // 演进中的消息链（含本条 userMsg）
                var roundText = text
                var roundAttachments = state.attachments
                var lastToolRequest = false
                var rounds = 0
                val MAX_ROUNDS = 6

                // 阶段式持久化：每轮完成立即落盘 + 刷新 UI，断线时保留已生成部分
                suspend fun persistStage() {
                    allChats[chatId] = chat.copy(messages = hist, lastUpdated = System.currentTimeMillis())
                    repository.saveChats(allChats)
                    // 清打字机残留，避免与已落库消息重复显示
                    _uiState.update { it.copy(messages = hist, streamingContent = null, streamingReasoning = null) }
                }

                while (rounds < MAX_ROUNDS) {
                    rounds++
                    val result = repository.sendMessage(
                        state.config,
                        if (rounds == 1) hist.dropLast(1) else hist,
                        roundText,
                        onChunk = { content, reasoning ->
                            _uiState.update { s ->
                                s.copy(streamingContent = content, streamingReasoning = reasoning)
                            }
                        },
                        isCancelled = { cancelled },
                        sessionSeed = chatId,
                        attachments = if (rounds == 1) roundAttachments else emptyList(),
                        tools = tools
                    )
                    lastToolRequest = result.toolCalls.isNotEmpty()
                    if (result.content == null && !lastToolRequest) {
                        // 用户取消：保留已发送的用户消息与已生成的阶段内容
                        _uiState.update {
                            it.copy(isLoading = false, streamingContent = null, streamingReasoning = null,
                                    cancellable = false, attachments = emptyList())
                        }
                                return@launch
                    }
                    hist = hist + ChatMessage(
                        role = "assistant",
                        content = result.content ?: "",
                        reasoning = result.reasoning ?: "",
                        durationMs = result.durationMs,
                        toolCalls = result.toolCalls.ifEmpty { null }
                    )
                    persistStage()   // 阶段式展示：本轮回复立即上屏
                    if (!lastToolRequest) break   // 无工具请求 → 回合完成

                    // 执行工具 → 结果作为 tool 消息回传，让模型决定下一步
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
                    if (rounds < MAX_ROUNDS) _uiState.update { s -> s.copy(toolActivity = null) }
                    roundText = ""           // 后续轮次不再插入用户文本
                    roundAttachments = emptyList()
                }

                _uiState.update {
                    it.copy(
                        isLoading = false, streamingContent = null, streamingReasoning = null, cancellable = false,
                        messages = hist,
                        attachments = emptyList(), toolActivity = null, generationError = null
                    )
                }
                if (lastToolRequest) {
                    _uiState.update { it.copy(toastMessage = "已达到最大工具轮次（$MAX_ROUNDS），已提前结束回合") }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isLoading = false, streamingContent = null, streamingReasoning = null, cancellable = false) }
            } catch (e: Exception) {
                // 断线/错误：保留用户消息与已生成的阶段内容，标记可重试（不再删除任何消息）
                _uiState.update {
                    it.copy(
                        isLoading = false, streamingContent = null, streamingReasoning = null, cancellable = false,
                        generationError = e.message ?: "连接中断，请重试", toolActivity = null
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

    /**
     * 获取 OpenCode 免费模型：拉取网关清单（仅保留 id 带 free 的模型）→ 逐个实测速度与可用性 →
     * 仅把「实测可用」的模型展示给用户（不可用的：地区受限 / 额度 / 超时 / 不支持接口，不展示）。
     * 添加与否由用户点列表中的「添加」按钮决定，不自动添加。
     */
    fun fetchOpenCodeFreeModels() {
        if (_uiState.value.fetchingFreeModels) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    fetchingFreeModels = true, freeModelError = null,
                    freeModels = emptyList(), freeModelsLoaded = false,
                    freeSpeedResults = emptyMap(), speedTestProgress = null
                )
            }
            val models = repository.fetchOpenCodeFreeModels()
            if (models.isEmpty()) {
                _uiState.update {
                    it.copy(
                        fetchingFreeModels = false,
                        freeModelError = "获取失败：无法访问 opencode.ai，或当前没有可用免费模型"
                    )
                }
                return@launch
            }
            // 逐个实测（列表暂不展示，全部测完才一次性展示可用模型）
            val usable = mutableListOf<OpenCodeFreeModel>()
            val results = mutableMapOf<String, OpenCodeSpeedResult>()
            models.forEachIndexed { index, model ->
                _uiState.update { s -> s.copy(speedTestProgress = "${index + 1}/${models.size}") }
                val res = repository.testOpenCodeModelSpeed(model)
                results[model.id] = res
                if (res.ok && res.latencyMs > 0) usable.add(model)
                _uiState.update { s -> s.copy(freeSpeedResults = results.toMap()) }
            }
            if (usable.isEmpty()) {
                _uiState.update {
                    it.copy(
                        fetchingFreeModels = false, speedTestProgress = null,
                        freeModelError = "全部免费模型均不可用（地区受限 / 额度 / 超时），请稍后再试"
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    fetchingFreeModels = false, freeModels = usable, freeModelsLoaded = true,
                    speedTestProgress = null
                )
            }
        }
    }

    /** 用户主动点击「添加」后才把模型加入配置（免费网关参数自动就绪） */
    fun addOpenCodeModel(modelId: String) {
        val entry = _uiState.value.freeModels.find { it.id == modelId } ?: return
        viewModelScope.launch {
            val existing = _uiState.value.config.models.map { it.model }.toSet()
            if (modelId in existing) {
                _uiState.update { it.copy(toastMessage = "该模型已在列表中") }
                return@launch
            }
            val config = ModelConfig(
                id = "free_$modelId",
                name = entry.name,
                apiUrl = "https://opencode.ai/zen/v1/chat/completions",
                apiKey = "public",
                model = entry.id,
                temperature = 0.7f,
                stream = true,
                thinking = false,
                freeGatewayFingerprint = true,
                thinkingLevel = if (entry.reasoning) "balanced" else "off"
            )
            val newConfig = _uiState.value.config.copy(models = _uiState.value.config.models + config)
            repository.saveConfig(newConfig)
            _uiState.update { it.copy(toastMessage = "已添加 ${entry.name}，可在顶部切换使用") }
        }
    }

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
    private val toolRegistry: com.aichat.app.tools.ToolRegistry,
    private val appContext: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ChatViewModel(repository, toolRegistry, appContext) as T
    }
}