package net.thunderbird.feature.ai.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import net.thunderbird.core.common.exception.ExceptionHandler
import net.thunderbird.core.ui.theme.manager.ThemeManager
import android.view.Menu
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.k9mail.core.ui.compose.designsystem.atom.Surface
import app.k9mail.core.ui.compose.designsystem.atom.button.ButtonIcon
import app.k9mail.core.ui.compose.designsystem.atom.button.ButtonIconDefaults
import app.k9mail.core.ui.compose.designsystem.atom.text.TextBodyMedium
import app.k9mail.core.ui.compose.designsystem.atom.textfield.TextFieldOutlined
import app.k9mail.core.ui.compose.designsystem.organism.AlertDialog
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.UnsupportedArchitectureException
import com.fsck.k9.mailstore.LocalStoreProvider
import com.fsck.k9.mailstore.LocalMessage
import com.fsck.k9.message.extractors.MessageFulltextCreator
import com.fsck.k9.ui.base.BaseActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.ui.compose.designsystem.atom.icon.Icons
import net.thunderbird.core.ui.compose.theme2.MainTheme
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.api.SearchAttribute
import net.thunderbird.feature.search.legacy.api.SearchCondition
import net.thunderbird.feature.ai.chat.AiChatHelper.buildPrompt
import net.thunderbird.feature.ai.chat.AiChatHelper.cleanEmailText
import org.koin.android.ext.android.inject

class AiChatActivity : BaseActivity() {
    private val themeProvider: FeatureThemeProvider by inject()
    private val accountManager: LegacyAccountManager by inject()
    private val localStoreProvider: LocalStoreProvider by inject()
    private var onOpenSettingsRequested: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accountUuid = intent.getStringExtra(EXTRA_ACCOUNT)
        val account = accountUuid?.let { accountManager.getAccount(it) } ?: error("Unknown account")
        seedAiChatPromptDefaults(this)
        setLayout(R.layout.ai_chat_activity)
        setTitle(R.string.ai_chat_title)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        findViewById<ComposeView>(R.id.ai_chat_compose_view).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                themeProvider.WithTheme {
                    AiChatScreen(
                        account = account,
                        localStoreProvider = localStoreProvider,
                    )
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            finish()
            true
        } else if (item.itemId == R.id.action_ai_settings) {
            onOpenSettingsRequested?.invoke()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ai_chat_menu, menu)
        return true
    }

    companion object {
        private const val EXTRA_ACCOUNT = "account_uuid"

        @JvmStatic
        fun launch(activity: Activity, account: LegacyAccountDto) {
            val intent = Intent(activity, AiChatActivity::class.java).apply {
                putExtra(EXTRA_ACCOUNT, account.uuid)
            }
            activity.startActivity(intent)
        }
    }

    fun setOnOpenSettingsRequested(listener: (() -> Unit)?) {
        onOpenSettingsRequested = listener
    }
}

// Bypasses InferenceEngineImpl's "_readyForSystemPrompt must be true" guard
private val readyForSystemPromptField by lazy {
    Class.forName("com.arm.aichat.internal.InferenceEngineImpl")
        .getDeclaredField("_readyForSystemPrompt")
        .apply { isAccessible = true }
}

private suspend fun InferenceEngine.resetChatContext(systemPrompt: String) {
    readyForSystemPromptField.setBoolean(this, true)
    setSystemPrompt(systemPrompt)
}

private data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isThinking: Boolean = false,
)

private fun buildTextSearch(queries: List<String>): LocalMessageSearch {
    return LocalMessageSearch().apply {
        isManualSearch = true
        for (query in queries) {
            or(
                SearchCondition(
                    MessageSearchField.SENDER,
                    SearchAttribute.CONTAINS,
                    query,
                ),
            )
            or(
                SearchCondition(
                    MessageSearchField.TO,
                    SearchAttribute.CONTAINS,
                    query,
                ),
            )
            or(
                SearchCondition(
                    MessageSearchField.CC,
                    SearchAttribute.CONTAINS,
                    query,
                ),
            )
            or(
                SearchCondition(
                    MessageSearchField.BCC,
                    SearchAttribute.CONTAINS,
                    query,
                ),
            )
            or(
                SearchCondition(
                    MessageSearchField.SUBJECT,
                    SearchAttribute.CONTAINS,
                    query,
                ),
            )
            or(
                SearchCondition(
                    MessageSearchField.MESSAGE_CONTENTS,
                    SearchAttribute.CONTAINS,
                    query,
                ),
            )
        }
    }
}

private fun extractSearchKeywords(keywordResponse: String): List<String> {
    return keywordResponse
        .split(',', '\n', ':', ';', ' ', '\t', '\r')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

private fun searchLocalMessages(
    keywordResponse: String,
    localStoreProvider: LocalStoreProvider,
    account: LegacyAccount,
): List<LocalMessage> {
    val keywords = extractSearchKeywords(keywordResponse)

    if (keywords.isEmpty())
        return emptyList()

    val search = buildTextSearch(keywords)
    val localStore = localStoreProvider.getInstanceByLegacyAccount(account)
    return localStore.searchForMessages(search)
}

@Composable
private fun AiChatScreen(
    account: LegacyAccount,
    localStoreProvider: LocalStoreProvider,
) {
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AiChatPreferences.PREFS, Context.MODE_PRIVATE) }
    val engine = remember { AiChat.getInferenceEngine(context) }
    val loadModelMutex = remember { Mutex() }
    var loadedModelName by remember { mutableStateOf<String?>(null) }
    var loadedSystemPrompt by remember { mutableStateOf<String?>(null) }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }
    var openSettingsOnDialogConfirm by remember { mutableStateOf(false) }

    suspend fun loadModel(showSettingsDialogOnFailure: Boolean = false): Boolean {
        return loadModelMutex.withLock {
            val systemPrompt = prefs.getString(
                AiChatPreferences.SYSTEM_PROMPT_KEY,
                null,
            )
            if (systemPrompt.isNullOrBlank()) {
                errorDialogMessage = context.getString(R.string.ai_chat_system_prompt_not_configured_load_model)
                openSettingsOnDialogConfirm = true
                return@withLock false
            }

            val configuredModelName = prefs.getString(
                AiChatPreferences.MODEL_PATH_KEY,
                null,
            )?.takeIf { it.isNotBlank() }
            val activeModel = getInternalModelFile(context).takeIf { it.exists() }
            if (configuredModelName == null || activeModel == null) {
                if (engine.state.value is InferenceEngine.State.ModelReady ||
                    engine.state.value is InferenceEngine.State.Error
                ) {
                    engine.cleanUp()
                }
                errorDialogMessage = context.getString(R.string.ai_chat_model_not_configured)
                openSettingsOnDialogConfirm = true
                return@withLock false
            }

            val desiredModelPath = activeModel.absolutePath
            val currentState = engine.state.value
            if (currentState is InferenceEngine.State.ModelReady &&
                loadedModelName == configuredModelName &&
                loadedSystemPrompt == systemPrompt
            ) {
                return@withLock true
            }

            if (currentState is InferenceEngine.State.ModelReady ||
                currentState is InferenceEngine.State.Error
            ) {
                engine.cleanUp()
            }
            if (engine.state.value !is InferenceEngine.State.Initialized) {
                engine.state.first { it is InferenceEngine.State.Initialized }
            }

            return try {
                engine.loadModel(desiredModelPath)
                engine.setSystemPrompt(systemPrompt)
                loadedModelName = configuredModelName
                loadedSystemPrompt = systemPrompt
                engine.state.value is InferenceEngine.State.ModelReady
            } catch (throwable: Throwable) {
                val details = throwable.message ?: "load failed"
                val error = context.getString(R.string.ai_chat_model_load_failed, details)
                errorDialogMessage = error
                false
            }
        }
    }

    val generationJob = remember { mutableStateOf<Job?>(null) }
    val thinkingText = stringResource(R.string.ai_chat_thinking)
    val activity = context as? AiChatActivity
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(activity) {
        activity?.setOnOpenSettingsRequested {
            context.startActivity(Intent(context, AiChatSettingsActivity::class.java))
        }
        onDispose {
            activity?.setOnOpenSettingsRequested(null)
        }
    }
    DisposableEffect(lifecycleOwner, prefs) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    loadModel(showSettingsDialogOnFailure = true)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val onUserMessageSend: suspend (String, Boolean, Boolean, suspend (String) -> Unit) -> String =
        onUserMessageSend@{ message, isDebugMode, resetChatAtEachStepEnabled, postDebugMessage ->
        val systemPrompt = prefs.getString(
            AiChatPreferences.SYSTEM_PROMPT_KEY,
            null,
        )
        val keywordTemplate = prefs.getString(
            AiChatPreferences.KEYWORD_PROMPT_KEY,
            null,
        )
        val mailTemplate = prefs.getString(
            AiChatPreferences.MAIL_PROMPT_KEY,
            null,
        )
        val finalTemplate = prefs.getString(
            AiChatPreferences.FINAL_PROMPT_KEY,
            null,
        )

        val missingPrompt = when {
            systemPrompt.isNullOrBlank() -> context.getString(R.string.ai_chat_system_prompt_label)
            keywordTemplate.isNullOrBlank() -> context.getString(R.string.ai_chat_keyword_prompt_label)
            mailTemplate.isNullOrBlank() -> context.getString(R.string.ai_chat_mail_prompt_label)
            finalTemplate.isNullOrBlank() -> context.getString(R.string.ai_chat_final_prompt_label)
            else -> null
        }

        if (missingPrompt != null) {
            val error = context.getString(R.string.ai_chat_prompt_not_configured, missingPrompt)
            errorDialogMessage = error
            openSettingsOnDialogConfirm = true
            return@onUserMessageSend error
        }

        val configuredKeywordTemplate = keywordTemplate ?: error("Keyword prompt not configured")
        val configuredMailTemplate = mailTemplate ?: error("Mail prompt not configured")
        val configuredFinalTemplate = finalTemplate ?: error("Final prompt not configured")

        val keywordPrompt = buildPrompt(configuredKeywordTemplate, message, "", "")

        if (isDebugMode) {
            postDebugMessage(
                context.getString(
                    R.string.ai_chat_debug_loaded_model_name,
                    prefs.getString(AiChatPreferences.MODEL_PATH_KEY, null)
                        ?: context.getString(R.string.ai_chat_debug_unknown_model),
                ),
            )
            postDebugMessage(
                context.getString(R.string.ai_chat_debug_system_prompt, loadedSystemPrompt.orEmpty()),
            )
            postDebugMessage(
                context.getString(
                    R.string.ai_chat_debug_built_keyword_prompt,
                    keywordPrompt.ifBlank { context.getString(R.string.ai_chat_debug_empty) },
                ),
            )
        }

        try {
            if (resetChatAtEachStepEnabled) {
                engine.resetChatContext(systemPrompt!!)
            }
            val keywordResponse = engine.sendUserPrompt(keywordPrompt).toList().joinToString("")
            if (isDebugMode) {
                postDebugMessage(
                    context.getString(
                        R.string.ai_chat_debug_keyword_response,
                        keywordResponse.ifBlank { context.getString(R.string.ai_chat_debug_empty) },
                    ),
                )
            }
            val keywordSearchResult = withContext(Dispatchers.IO) {
                searchLocalMessages(keywordResponse, localStoreProvider, account)
            }
            if (isDebugMode) {
                val subjects = keywordSearchResult
                    .map { it.subject?.trim().orEmpty() }
                    .map {
                        if (it.isBlank()) {
                            context.getString(R.string.ai_chat_debug_no_subject)
                        } else {
                            "- ${it}"
                        }
                    }
                    .joinToString("\n")
                postDebugMessage(
                    context.getString(
                        R.string.ai_chat_debug_search_results,
                        keywordSearchResult.size,
                        subjects.ifBlank { context.getString(R.string.ai_chat_debug_none) },
                    ),
                )
            }

            val sortedMatches = keywordSearchResult.sortedWith(
                compareBy<LocalMessage>(
                    { it.sentDate?.time ?: Long.MAX_VALUE },
                    { it.databaseId },
                ),
            )
            val fulltextCreator = MessageFulltextCreator.newInstance()
            val emailAnswers = mutableListOf<String>()
            sortedMatches.forEachIndexed { index, localMessage ->
                val noSubject = context.getString(R.string.ai_chat_debug_no_subject)

                val subject = localMessage.subject?.trim().orEmpty().ifBlank { noSubject }
                val rawText = fulltextCreator.createFulltext(localMessage)
                    ?: localMessage.preview
                    ?: ""
                val cleanedText = cleanEmailText(rawText)

                if (cleanedText.isBlank() && subject ==  noSubject) {
                    if (isDebugMode) {
                        postDebugMessage(
                            context.getString(
                                R.string.ai_chat_debug_skipping_email_empty,
                                index + 1,
                                sortedMatches.size,
                            ),
                        )
                    }
                    return@forEachIndexed
                }

                val mailSubjectText = buildString {
                    append(subject)
                    append("\n")
                    append(cleanedText)
                }

                val emailPrompt = buildPrompt(
                    template = configuredMailTemplate,
                    query = message,
                    mail = mailSubjectText,
                    answer = "",
                )

                if (isDebugMode) {
                    postDebugMessage(
                        context.getString(
                            R.string.ai_chat_debug_processing_email,
                            index + 1,
                            sortedMatches.size,
                            subject,
                        ),
                    )
                    postDebugMessage(
                        context.getString(
                            R.string.ai_chat_debug_built_email_prompt,
                            emailPrompt.ifBlank { context.getString(R.string.ai_chat_debug_empty) },
                        ),
                    )
                    postDebugMessage(
                        context.getString(
                            R.string.ai_chat_debug_answer_before,
                            context.getString(R.string.ai_chat_debug_empty),
                        ),
                    )
                }
                val startNanos = System.nanoTime()
                if (resetChatAtEachStepEnabled) {
                    engine.resetChatContext(systemPrompt!!)
                }
                val emailAnswer = engine.sendUserPrompt(emailPrompt)
                    .toList()
                    .joinToString("")
                    .trim()
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                if (emailAnswer.isNotBlank()) {
                    emailAnswers.add(emailAnswer)
                }

                if (isDebugMode) {
                    postDebugMessage(context.getString(R.string.ai_chat_debug_processed_in_ms, elapsedMs))
                    postDebugMessage(
                        context.getString(
                            R.string.ai_chat_debug_answer_after,
                            emailAnswer.ifBlank { context.getString(R.string.ai_chat_debug_empty) },
                        ),
                    )
                }
            }

            if (emailAnswers.isEmpty()) {
                return@onUserMessageSend context.getString(R.string.ai_chat_insufficient_evidence)
            }

            val answerListText = emailAnswers.mapIndexed { index, answer ->
                "${index + 1}. $answer"
            }.joinToString("\n")

            val finalPrompt = buildPrompt(
                template = configuredFinalTemplate,
                query = message,
                mail = "",
                answer = "",
                answers = answerListText,
            )

            if (isDebugMode) {
                postDebugMessage(
                    context.getString(
                        R.string.ai_chat_debug_built_final_prompt,
                        finalPrompt.ifBlank { context.getString(R.string.ai_chat_debug_empty) },
                    ),
                )
            }

            if (resetChatAtEachStepEnabled) {
                engine.resetChatContext(systemPrompt!!)
            }
            val finalAnswer = engine.sendUserPrompt(finalPrompt)
                .toList()
                .joinToString("")
                .trim()

            return@onUserMessageSend if (finalAnswer.isBlank()) {
                context.getString(R.string.ai_chat_insufficient_evidence)
            } else {
                finalAnswer
            }
        } catch (throwable: Throwable) {
            val details = throwable.message ?: "prompt failed"
            val error = context.getString(R.string.ai_chat_llama_generation_failed, details)
            errorDialogMessage = error
            ""
        }
    }

    DisposableEffect(engine) {
        onDispose {
            generationJob.value?.cancel()
            val state = engine.state.value
            if (state is InferenceEngine.State.ModelReady || state is InferenceEngine.State.Error) {
                engine.cleanUp()
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(Unit) {
        loadModel(showSettingsDialogOnFailure = true)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MainTheme.colors.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = MainTheme.spacings.double,
                    vertical = MainTheme.spacings.default,
                ),
                verticalArrangement = Arrangement.spacedBy(MainTheme.spacings.default),
            ) {
                items(messages) { message ->
                    ChatBubble(
                        message = message,
                        thinkingText = thinkingText,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MainTheme.spacings.double),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MainTheme.spacings.default),
            ) {
                TextFieldOutlined(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.ai_chat_input_hint),
                    isSingleLine = false,
                )
                ButtonIcon(
                    onClick = {
                        val trimmed = input.trim()
                        if (trimmed.isNotEmpty()) {
                            messages.add(ChatMessage(text = trimmed, isUser = true))
                            input = ""
                            val assistantIndex = messages.size
                            messages.add(
                                ChatMessage(
                                    text = thinkingText,
                                    isUser = false,
                                    isThinking = true,
                                ),
                            )
                            generationJob.value?.cancel()
                            generationJob.value = scope.launch {
                                suspend fun showFinalAssistantMessage(text: String) {
                                    fun ensureAssistantMessageIndex(): Int {
                                        if (assistantIndex in messages.indices && messages[assistantIndex].isThinking) {
                                            messages.removeAt(assistantIndex)
                                            messages.add(
                                                assistantIndex,
                                                ChatMessage(
                                                    text = "",
                                                    isUser = false,
                                                ),
                                            )
                                            return assistantIndex
                                        }

                                        messages.add(
                                            ChatMessage(
                                                text = "",
                                                isUser = false,
                                            ),
                                        )
                                        return messages.lastIndex
                                    }

                                    val targetIndex = ensureAssistantMessageIndex()
                                    val chunks = Regex("""\s+|\S+""").findAll(text).map { it.value }.toList()
                                    if (chunks.isEmpty()) {
                                        messages[targetIndex] = ChatMessage(
                                            text = text,
                                            isUser = false,
                                        )
                                        return
                                    }

                                    val streamedText = StringBuilder()
                                    for (chunk in chunks) {
                                        streamedText.append(chunk)
                                        messages[targetIndex] = ChatMessage(
                                            text = streamedText.toString(),
                                            isUser = false,
                                        )
                                        delay(24)
                                    }
                                }

                                suspend fun showFinalAssistantMessageAt(index: Int, text: String) {
                                    if (index !in messages.indices) {
                                        showFinalAssistantMessage(text)
                                        return
                                    }

                                    val chunks = Regex("""\s+|\S+""").findAll(text).map { it.value }.toList()
                                    if (chunks.isEmpty()) {
                                        messages[index] = ChatMessage(
                                            text = text,
                                            isUser = false,
                                        )
                                        return
                                    }

                                    val streamedText = StringBuilder()
                                    for (chunk in chunks) {
                                        streamedText.append(chunk)
                                        messages[index] = ChatMessage(
                                            text = streamedText.toString(),
                                            isUser = false,
                                        )
                                        delay(24)
                                    }
                                }

                                if (!loadModel(showSettingsDialogOnFailure = true)) {
                                    return@launch
                                }

                                try {
                                    val debugChatModeEnabled = prefs.getBoolean(
                                        AiChatPreferences.DEBUG_CHAT_MODE_KEY,
                                        false,
                                    )
                                    val resetChatAtEachStepEnabled = prefs.getBoolean(
                                        AiChatPreferences.RESET_CHAT_AT_EACH_STEP_KEY,
                                        false,
                                    )
                                    var hasReplacedPlaceholder = false
                                    val postDebugMessage: suspend (String) -> Unit = { text ->
                                        if (!hasReplacedPlaceholder) {
                                            messages[assistantIndex] = ChatMessage(
                                                text = text,
                                                isUser = false,
                                                isThinking = true,
                                            )
                                            hasReplacedPlaceholder = true
                                        } else {
                                            messages.add(
                                                ChatMessage(
                                                    text = text,
                                                    isUser = false,
                                                    isThinking = true,
                                                ),
                                            )
                                        }
                                    }
                                    val answer = onUserMessageSend(
                                        trimmed,
                                        debugChatModeEnabled,
                                        resetChatAtEachStepEnabled,
                                        postDebugMessage,
                                    )

                                    if (debugChatModeEnabled) {
                                        if (hasReplacedPlaceholder) {
                                            messages.add(
                                                ChatMessage(
                                                    text = "",
                                                    isUser = false,
                                                ),
                                            )
                                            showFinalAssistantMessageAt(messages.lastIndex, answer)
                                        } else {
                                            messages[assistantIndex] = ChatMessage(
                                                text = "",
                                                isUser = false,
                                            )
                                            showFinalAssistantMessageAt(assistantIndex, answer)
                                        }
                                    } else {
                                        showFinalAssistantMessage(answer)
                                    }
                                } catch (exception: UnsupportedArchitectureException) {
                                    showFinalAssistantMessage(
                                        context.getString(R.string.ai_chat_unsupported_arch),
                                    )
                                } catch (throwable: Throwable) {
                                    val details = throwable.message ?: "unknown error"
                                    showFinalAssistantMessage(
                                        context.getString(
                                            R.string.ai_chat_llama_generation_failed,
                                            details,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                    imageVector = Icons.Outlined.Send,
                    contentDescription = stringResource(R.string.ai_chat_send),
                    colors = ButtonIconDefaults.buttonIconFilledColors(),
                )
            }
        }
    }

    errorDialogMessage?.let { message ->
        AlertDialog(
            title = stringResource(R.string.ai_chat_error_dialog_title),
            text = message,
            confirmText = stringResource(android.R.string.ok),
            onConfirmClick = {
                errorDialogMessage = null
                if (openSettingsOnDialogConfirm) {
                    openSettingsOnDialogConfirm = false
                    context.startActivity(Intent(context, AiChatSettingsActivity::class.java))
                }
            },
            onDismissRequest = {
                errorDialogMessage = null
                openSettingsOnDialogConfirm = false
            },
        )
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    thinkingText: String,
) {
    val isAnimatedThinkingBubble = message.isThinking && message.text == thinkingText
    val animatedDotCount by produceState(initialValue = 0, key1 = isAnimatedThinkingBubble) {
        if (!isAnimatedThinkingBubble) {
            value = 0
            return@produceState
        }

        while (true) {
            kotlinx.coroutines.delay(420)
            value = (value + 1) % 4
        }
    }
    val displayText = if (isAnimatedThinkingBubble) {
        "$thinkingText${".".repeat(animatedDotCount)}"
    } else {
        message.text
    }

    val alignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleShape = if (message.isThinking) MainTheme.shapes.medium else MainTheme.shapes.large
    val backgroundColor = if (message.isUser) {
        MainTheme.colors.surfaceContainerHigh
    } else if (message.isThinking) {
        MainTheme.colors.surfaceContainerLow
    } else {
        MainTheme.colors.surfaceContainer
    }
    val textColor = if (message.isThinking) MainTheme.colors.onSurfaceVariant else MainTheme.colors.onSurface

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        val bubbleModifier = Modifier
            .background(
                color = backgroundColor,
                shape = bubbleShape,
            )
            .then(
                if (message.isThinking) {
                    Modifier.border(
                        width = 1.dp,
                        color = MainTheme.colors.outlineVariant,
                        shape = bubbleShape,
                    )
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = MainTheme.spacings.double,
                vertical = MainTheme.spacings.default,
            )

        if (isAnimatedThinkingBubble) {
            Box(modifier = bubbleModifier) {
                TextBodyMedium(
                    text = "$thinkingText...",
                    color = textColor.copy(alpha = 0f),
                    textAlign = if (message.isUser) TextAlign.End else TextAlign.Start,
                )
                TextBodyMedium(
                    text = displayText,
                    color = textColor,
                    textAlign = if (message.isUser) TextAlign.End else TextAlign.Start,
                )
            }
        } else {
            TextBodyMedium(
                text = displayText,
                color = textColor,
                textAlign = if (message.isUser) TextAlign.End else TextAlign.Start,
                modifier = bubbleModifier,
            )
        }
    }
}
