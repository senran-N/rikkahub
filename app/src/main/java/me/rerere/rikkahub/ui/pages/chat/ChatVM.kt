package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.ai.ui.transformers.PlaceholderTransformer
import me.rerere.ai.ui.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.TemplateTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.mcp.McpManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.deleteChatFiles
import me.rerere.search.SearchService
import java.time.Instant
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

private val inputTransformers by lazy {
    listOf(
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
    )
}

class ChatVM(
    savedStateHandle: SavedStateHandle,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    val mcpManager: McpManager,
    val updateChecker: UpdateChecker,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(checkNotNull(savedStateHandle["id"]))
    private val _conversation = MutableStateFlow(Conversation.ofId(_conversationId))
    val conversation: StateFlow<Conversation>
        get() = _conversation
    var useWebSearch by mutableStateOf(false)

    // 异步任务
    val conversationJob = MutableStateFlow<Job?>(null)

    init {
        // Load the conversation from the repository (database)
        viewModelScope.launch {
            val conversation = conversationRepo.getConversationById(_conversationId)
            if (conversation != null) {
                this@ChatVM._conversation.value = conversation

                // 更新当前助手到 conversation 所属的 assistant
                // 这里不能用 updateSettings，因为 settings 可能还没加载
                settingsStore.updateAssistant(conversation.assistantId)
            }
        }
    }

    // 用户设置
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    // 聊天列表
    val conversations = settings
        .map { it.assistantId }
        .distinctUntilChanged()
        .flatMapLatest { assistantId ->
            conversationRepo.getConversationsOfAssistant(assistantId)
                .catch {
                    Log.e(TAG, "conversationRepo.getAllConversations: ", it)
                    errorFlow.emit(it)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // 当前模型
    val currentChatModel = settings
        .map { settings ->
            settings.getCurrentChatModel()
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误流
    val errorFlow = MutableSharedFlow<Throwable>()

    // 生成完成
    val generationDoneFlow = MutableSharedFlow<Uuid>()

    // 更新设置
    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    }
                )
            }
        }
    }

    // Update checker
    val updateState = updateChecker.checkUpdate()
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    // Search Tool
    private val searchTool = Tool(
        name = "search_web",
        description = "search web for information",
        parameters = InputSchema.Obj(
            buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        ),
        execute = {
            val query = it.jsonObject["query"]!!.jsonPrimitive.content
            val service = SearchService.getService(settings.value.searchServiceOptions)
            val result = service.search(
                query = query,
                commonOptions = settings.value.searchCommonOptions,
                serviceOptions = settings.value.searchServiceOptions,
            )
            val results = result.getOrThrow()
            JsonInstant.encodeToJsonElement(results)
        }
    )

    fun handleMessageSend(content: List<UIMessagePart>) {
        if (content.isEmptyInputMessage()) return

        this.conversationJob.value?.cancel()
        val job = viewModelScope.launch {
            // 添加消息到列表
            val newConversation = conversation.value.copy(
                messageNodes = conversation.value.messageNodes + UIMessage(
                    role = MessageRole.USER,
                    parts = content,
                ).toMessageNode(),
            )
            saveConversation(newConversation)

            // 开始补全
            handleMessageComplete()

            generationDoneFlow.emit(Uuid.random())
        }
        this.conversationJob.value = job
        job.invokeOnCompletion {
            this.conversationJob.value = null
        }
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        val newConversation = conversation.value.copy(
            messageNodes = conversation.value.messageNodes.map { node ->
                node.copy(
                    messages = node.messages.map { message ->
                        if (message.id == messageId) {
                            message.copy(
                                parts = parts,
                                createdAt = Clock.System.now().toLocalDateTime(
                                    TimeZone.currentSystemDefault()
                                )
                            )
                        } else {
                            message
                        }
                    }
                )
            },
        )
        this.updateConversation(newConversation)
        val node = newConversation.getMessageNodeByMessageId(messageId) ?: return
        this.regenerateAtMessage(
            message = node.currentMessage,
            regenerateAssistantMsg = false
        )
    }

    fun handleMessageTruncate() {
        viewModelScope.launch {
            val lastTruncateIndex = conversation.value.messageNodes.lastIndex + 1
            // 如果截断在最后一个索引，则取消截断，否则更新 truncateIndex 到最后一个截断位置
            val newConversation = conversation.value.copy(
                truncateIndex = if (conversation.value.truncateIndex == lastTruncateIndex) -1 else lastTruncateIndex,
            )
            saveConversation(newConversation)
        }
    }

    private suspend fun handleMessageComplete(messageRange: ClosedRange<Int>? = null) {
        val model = currentChatModel.value ?: return
        runCatching {
            generationHandler.generateText(
                settings = settings.value,
                model = model,
                messages = conversation.value.currentMessages.let {
                    if (messageRange != null) {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        it
                    }
                },
                assistant = settings.value.getCurrentAssistant(),
                memories = { memoryRepository.getMemoriesOfAssistant(settings.value.assistantId.toString()) },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (useWebSearch) {
                        add(searchTool)
                    }
                    mcpManager.getAllAvailableTools().forEach { tool ->
                        add(
                            Tool(
                                name = tool.name,
                                description = tool.description ?: "",
                                parameters = tool.inputSchema,
                                execute = {
                                    mcpManager.callTool(tool.name, it.jsonObject)
                                }
                            ))
                    }
                },
                truncateIndex = conversation.value.truncateIndex,
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                updateConversation(
                    conversation = conversation.value.copy(
                        messageNodes = conversation.value.messageNodes.map { node ->
                            node.copy(
                                messages = node.messages.map { it.finishReasoning() } // 结束思考
                            )
                        }
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        updateConversation(conversation.value.updateCurrentMessages(chunk.messages))
                    }

                    is GenerationChunk.TokenUsage -> {
                        var tokenUsage = conversation.value.tokenUsage ?: TokenUsage()
                        tokenUsage = tokenUsage.copy(
                            promptTokens = chunk.usage.promptTokens.takeIf { it > 0 }
                                ?: tokenUsage.promptTokens,
                            completionTokens = chunk.usage.completionTokens.takeIf { it > 0 }
                                ?: tokenUsage.completionTokens,
                        )
                        tokenUsage = tokenUsage.copy(
                            totalTokens = tokenUsage.promptTokens + tokenUsage.completionTokens,
                        )
                        updateConversation(conversation.value.copy(tokenUsage = tokenUsage))
                        Log.i(TAG, "handleMessageComplete: usage = ${chunk.usage}")
                    }
                }
            }
        }.onFailure {
            it.printStackTrace()
            errorFlow.emit(it)
        }.onSuccess {
            saveConversation(conversation.value)
            generateTitle(conversation.value)
        }
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        if (conversation.title.isNotBlank() && !force) return

        val model = settings.value.findModelById(settings.value.titleModelId) ?: let {
            // 如果没有标题模型，则使用聊天模型
            settings.value.getCurrentChatModel()
        } ?: return
        val provider = model.findProvider(settings.value.providers) ?: return

        viewModelScope.launch {
            runCatching {
                val providerHandler = ProviderManager.getProviderByType(provider)
                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.user(
                            """
                                你是一名擅长会话的助理，我会给你一些对话内容在content内，你需要将用户的会话总结为 10 个字以内的标题
                                1. 标题语言与用户的首要语言一致
                                2. 不要使用标点符号和其他特殊符号
                                3. 直接回复标题即可
                                4. 使用 ${Locale.getDefault().displayName} 语言总结
                                
                                <content>
                                ${conversation.currentMessages.joinToString("\n\n") { it.summaryAsText() }}
                                </content>
                                """.trimIndent()
                        )
                    ),
                    params = TextGenerationParams(
                        model = model,
                        temperature = 0.3f,
                    ),
                )
                Log.i(TAG, "generateTitle: ${result.choices[0]}")
                // 生成完，conversation可能不是最新了，因此需要重新获取
                conversationRepo.getConversationById(conversation.id)?.let {
                    saveConversation(
                        it.copy(
                            title = result.choices[0].message?.toText()?.trim() ?: "",
                        )
                    )
                }
            }.onFailure {
                it.printStackTrace()
            }
        }
    }

    suspend fun forkMessage(
        message: UIMessage
    ): Conversation {
        val node = conversation.value.getMessageNodeByMessage(message)
        val nodes =
            conversation.value.messageNodes.subList(
                0,
                conversation.value.messageNodes.indexOf(node) + 1
            )
        val newConversation = Conversation(
            id = Uuid.random(),
            assistantId = settings.value.assistantId,
            messageNodes = nodes
        )
        saveConversation(newConversation)
        return newConversation
    }

    fun deleteMessage(
        message: UIMessage
    ) {
        val conversation = conversation.value
        val node = conversation.getMessageNodeByMessage(message) ?: return // 找到这个消息所在的node
        val nodeIndex = conversation.messageNodes.indexOf(node)
        if (nodeIndex == -1) return
        val newConversation = if (node.messages.size == 1) {
            // 删除这个Node，因为这个node只有一个消息，那么这个node就是这个消息
            conversation.copy(
                messageNodes = conversation.messageNodes.filterIndexed { index, node -> index != nodeIndex }
            )
        } else {
            // 更新node，删除这个消息
            conversation.copy(
                messageNodes = conversation.messageNodes.map { node ->
                    val newNode = node.copy(
                        messages = node.messages.filter { it.id != message.id }
                    )
                    newNode.copy(
                        selectIndex = newNode.messages.lastIndex // 更新selectIndex
                    )
                }
            )
        }
        updateConversation(newConversation)
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        viewModelScope.launch {
            if (message.role == MessageRole.USER) {
                // 如果是用户消息，则截止到当前消息
                val node = conversation.value.getMessageNodeByMessage(message)
                val indexAt = conversation.value.messageNodes.indexOf(node)
                val newConversation = conversation.value.copy(
                    messageNodes = conversation.value.messageNodes.subList(0, indexAt + 1)
                )
                saveConversation(newConversation)
                conversationJob.value?.cancel()
                val job = viewModelScope.launch {
                    handleMessageComplete()
                    generationDoneFlow.emit(Uuid.random())
                }
                conversationJob.value = job
                job.invokeOnCompletion {
                    conversationJob.value = null
                }
            } else {
                if (!regenerateAssistantMsg) {
                    // 如果不需要重新生成助手消息，则直接返回
                    saveConversation(conversation.value)
                    return@launch
                }
                val node = conversation.value.getMessageNodeByMessage(message)
                val nodeIndex = conversation.value.messageNodes.indexOf(node)
                conversationJob.value?.cancel()
                val job = viewModelScope.launch {
                    handleMessageComplete(
                        messageRange = 0..<nodeIndex,
                    )
                    generationDoneFlow.emit(Uuid.random())
                }
                conversationJob.value = job
                job.invokeOnCompletion {
                    conversationJob.value = null
                }
            }
        }
    }

    fun updateConversation(conversation: Conversation) {
        if (conversation.id != this._conversationId) return
        checkFilesDelete(conversation, this._conversation.value)
        this._conversation.value = conversation
    }

    // 变更消息，检查文件删除
    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            context.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversation: Conversation) {
        val conversation = conversation.copy(
            assistantId = settings.value.assistantId,
            updateAt = Instant.now()
        )
        this.updateConversation(conversation)
        try {
            if (conversationRepo.getConversationById(conversation.id) == null) {
                conversationRepo.insertConversation(conversation)
            } else {
                conversationRepo.updateConversation(conversation)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            saveConversation(conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            saveConversation(conversation.value.copy(title = title))
        }
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }
    }
}