package net.thunderbird.feature.ai.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.k9mail.core.ui.compose.designsystem.atom.DividerHorizontal
import app.k9mail.core.ui.compose.designsystem.atom.Surface
import app.k9mail.core.ui.compose.designsystem.atom.button.ButtonFilled
import app.k9mail.core.ui.compose.designsystem.atom.button.ButtonText
import app.k9mail.core.ui.compose.designsystem.atom.text.TextBodyMedium
import app.k9mail.core.ui.compose.designsystem.atom.textfield.TextFieldOutlined
import app.k9mail.core.ui.compose.designsystem.molecule.input.SwitchInput
import com.fsck.k9.ui.base.BaseActivity
import net.thunderbird.core.ui.compose.designsystem.atom.icon.Icon
import net.thunderbird.core.ui.compose.designsystem.atom.icon.Icons
import net.thunderbird.core.ui.compose.theme2.MainTheme
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider
import org.koin.android.ext.android.inject
import java.io.File

class AiChatSettingsActivity : BaseActivity() {
    private val themeProvider: FeatureThemeProvider by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setLayout(R.layout.ai_chat_activity)
        setTitle(R.string.ai_chat_settings_title)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        findViewById<ComposeView>(R.id.ai_chat_compose_view).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                themeProvider.WithTheme {
                    AiChatSettingsScreen()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            finish()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    companion object {
        @JvmStatic
        fun launch(context: Context) {
            context.startActivity(Intent(context, AiChatSettingsActivity::class.java))
        }
    }
}

@Composable
private fun AiChatSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AiChatPreferences.PREFS, Context.MODE_PRIVATE) }
    var systemPrompt by remember {
        mutableStateOf(
            prefs.getString(
                AiChatPreferences.SYSTEM_PROMPT_KEY,
                null,
            ) ?: "",
        )
    }
    var keywordPrompt by remember {
        mutableStateOf(
            prefs.getString(
                AiChatPreferences.KEYWORD_PROMPT_KEY,
                null,
            ) ?: "",
        )
    }
    var mailPrompt by remember {
        mutableStateOf(
            prefs.getString(
                AiChatPreferences.MAIL_PROMPT_KEY,
                null,
            ) ?: "",
        )
    }
    var finalPrompt by remember {
        mutableStateOf(
            prefs.getString(
                AiChatPreferences.FINAL_PROMPT_KEY,
                null,
            ) ?: "",
        )
    }
    var selectedModelName by remember {
        mutableStateOf(
            prefs.getString(AiChatPreferences.MODEL_PATH_KEY, null)?.let { File(it).name } ?: "",
        )
    }
    var debugChatModeEnabled by remember {
        mutableStateOf(
            prefs.getBoolean(AiChatPreferences.DEBUG_CHAT_MODE_KEY, false),
        )
    }
    var resetChatAtEachStepEnabled by remember {
        mutableStateOf(
            prefs.getBoolean(AiChatPreferences.RESET_CHAT_AT_EACH_STEP_KEY, false),
        )
    }
    var isAdvancedExpanded by remember { mutableStateOf(false) }
    var activePromptEditor by remember { mutableStateOf<PromptEditor?>(null) }
    val canSavePrompts = systemPrompt.isNotBlank() &&
        keywordPrompt.isNotBlank() &&
        mailPrompt.isNotBlank() &&
        finalPrompt.isNotBlank()
    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val copied = copyModelFromUri(context, uri) ?: return@rememberLauncherForActivityResult
        selectedModelName = copied
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MainTheme.colors.surfaceContainerLowest,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(MainTheme.spacings.double),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = MainTheme.sizes.large)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MainTheme.spacings.double),
            ) {
                PromptPreviewField(
                    value = systemPrompt,
                    label = stringResource(R.string.ai_chat_system_prompt_label),
                    onClick = { activePromptEditor = PromptEditor.System },
                )
                PromptPreviewField(
                    value = keywordPrompt,
                    label = stringResource(R.string.ai_chat_keyword_prompt_label),
                    onClick = { activePromptEditor = PromptEditor.Keyword },
                )
                PromptPreviewField(
                    value = mailPrompt,
                    label = stringResource(R.string.ai_chat_mail_prompt_label),
                    onClick = { activePromptEditor = PromptEditor.Mail },
                )
                PromptPreviewField(
                    value = finalPrompt,
                    label = stringResource(R.string.ai_chat_final_prompt_label),
                    onClick = { activePromptEditor = PromptEditor.Final },
                )
                TextBodyMedium(
                    text = stringResource(R.string.ai_chat_prompt_placeholders_note),
                )
                DividerHorizontal(
                    modifier = Modifier.padding(vertical = MainTheme.spacings.default),
                )
                TextBodyMedium(
                    text = if (selectedModelName.isBlank()) {
                        stringResource(R.string.ai_chat_model_not_selected)
                    } else {
                        stringResource(R.string.ai_chat_model_selected, selectedModelName)
                    },
                )
                ButtonText(
                    text = stringResource(R.string.ai_chat_select_model_button),
                    onClick = { modelPickerLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                DividerHorizontal(
                    modifier = Modifier.padding(vertical = MainTheme.spacings.default),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                ) {
                    ButtonText(
                        text = stringResource(R.string.ai_chat_advanced_section_title),
                        onClick = { isAdvancedExpanded = !isAdvancedExpanded },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(
                                imageVector = if (isAdvancedExpanded) {
                                    Icons.Outlined.ExpandLess
                                } else {
                                    Icons.Outlined.ExpandMore
                                },
                                contentDescription = null,
                            )
                        },
                    )
                    if (isAdvancedExpanded) {
                        SwitchInput(
                            text = stringResource(R.string.ai_chat_debug_chat_mode_label),
                            checked = debugChatModeEnabled,
                            onCheckedChange = { debugChatModeEnabled = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SwitchInput(
                            text = stringResource(R.string.ai_chat_reset_chat_at_each_step_label),
                            checked = resetChatAtEachStepEnabled,
                            onCheckedChange = { resetChatAtEachStepEnabled = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            ButtonFilled(
                text = stringResource(R.string.ai_chat_save_settings),
                onClick = {
                    prefs.edit()
                        .putString(
                                AiChatPreferences.SYSTEM_PROMPT_KEY,
                                systemPrompt.ifBlank { null },
                            )
                            .putString(
                                AiChatPreferences.KEYWORD_PROMPT_KEY,
                                keywordPrompt.ifBlank { null },
                            )
                            .putString(
                                AiChatPreferences.MAIL_PROMPT_KEY,
                                mailPrompt.ifBlank { null },
                            )
                            .putString(
                                AiChatPreferences.FINAL_PROMPT_KEY,
                                finalPrompt.ifBlank { null },
                            )
                            .putString(AiChatPreferences.MODEL_PATH_KEY, selectedModelName.ifBlank { null })
                            .putBoolean(AiChatPreferences.DEBUG_CHAT_MODE_KEY, debugChatModeEnabled)
                            .putBoolean(
                                AiChatPreferences.RESET_CHAT_AT_EACH_STEP_KEY,
                                resetChatAtEachStepEnabled,
                            )
                            .apply()
                    if (context is AiChatSettingsActivity) {
                        context.finish()
                    }
                },
                enabled = canSavePrompts,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }
    }

    activePromptEditor?.let { editor ->
        FullscreenPromptEditor(
            title = when (editor) {
                PromptEditor.System -> stringResource(R.string.ai_chat_system_prompt_label)
                PromptEditor.Keyword -> stringResource(R.string.ai_chat_keyword_prompt_label)
                PromptEditor.Mail -> stringResource(R.string.ai_chat_mail_prompt_label)
                PromptEditor.Final -> stringResource(R.string.ai_chat_final_prompt_label)
            },
            initialValue = when (editor) {
                PromptEditor.System -> systemPrompt
                PromptEditor.Keyword -> keywordPrompt
                PromptEditor.Mail -> mailPrompt
                PromptEditor.Final -> finalPrompt
            },
            onDismiss = { activePromptEditor = null },
            onSave = { updatedValue ->
                when (editor) {
                    PromptEditor.System -> systemPrompt = updatedValue
                    PromptEditor.Keyword -> keywordPrompt = updatedValue
                    PromptEditor.Mail -> mailPrompt = updatedValue
                    PromptEditor.Final -> finalPrompt = updatedValue
                }
                activePromptEditor = null
            },
        )
    }
}

private enum class PromptEditor {
    System,
    Keyword,
    Mail,
    Final,
}

@Composable
private fun PromptPreviewField(
    value: String,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp),
    ) {
        TextFieldOutlined(
            value = value,
            onValueChange = {},
            modifier = Modifier.fillMaxSize(),
            label = label,
            isReadOnly = true,
            isSingleLine = false,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
        )
    }
}

@Composable
private fun FullscreenPromptEditor(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MainTheme.colors.surfaceContainerLowest,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(MainTheme.spacings.double),
                verticalArrangement = Arrangement.spacedBy(MainTheme.spacings.double),
            ) {
                TextBodyMedium(text = title)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        MainTheme.spacings.default,
                        Alignment.End,
                    ),
                ) {
                    ButtonText(
                        text = stringResource(android.R.string.cancel),
                        onClick = onDismiss,
                    )
                    ButtonFilled(
                        text = stringResource(android.R.string.ok),
                        onClick = { onSave(value) },
                    )
                }
                TextFieldOutlined(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxSize(),
                    isSingleLine = false,
                )
            }
        }
    }
}
