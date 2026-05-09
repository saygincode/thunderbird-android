package net.thunderbird.feature.ai.chat

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

internal object AiChatPreferences {
    const val PREFS = "ai_chat_prefs"
    const val MODEL_PATH_KEY = "model_path"
    const val INTERNAL_MODEL_RELATIVE_PATH = "model.gguf"
    const val SYSTEM_PROMPT_KEY = "system_prompt"
    const val KEYWORD_PROMPT_KEY = "keyword_prompt"
    const val MAIL_PROMPT_KEY = "mail_prompt"
    const val FINAL_PROMPT_KEY = "final_prompt"
    const val DEBUG_CHAT_MODE_KEY = "debug_chat_mode"
    const val RESET_CHAT_AT_EACH_STEP_KEY = "reset_chat_at_each_step"
}

internal fun seedAiChatPromptDefaults(context: Context) {
    val prefs = context.getSharedPreferences(AiChatPreferences.PREFS, Context.MODE_PRIVATE)
    val defaults = mapOf(
        AiChatPreferences.SYSTEM_PROMPT_KEY to context.getString(
            R.string.ai_chat_default_system_prompt,
        ),
        AiChatPreferences.KEYWORD_PROMPT_KEY to context.getString(
            R.string.ai_chat_default_keyword_prompt,
        ),
        AiChatPreferences.MAIL_PROMPT_KEY to context.getString(
            R.string.ai_chat_default_mail_prompt,
        ),
        AiChatPreferences.FINAL_PROMPT_KEY to context.getString(
            R.string.ai_chat_default_final_prompt,
        ),
    )
    var changed = false
    val editor = prefs.edit()
    for ((key, value) in defaults) {
        if (prefs.getString(key, null).isNullOrBlank()) {
            editor.putString(key, value)
            changed = true
        }
    }
    if (changed) {
        editor.apply()
    }
}

internal fun resolveDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
    val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            return it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }
    return null
}

internal fun copyModelFromUri(
    context: Context,
    uri: Uri,
): String? {
    val name = resolveDisplayName(context.contentResolver, uri)
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() }
        ?: "selected-model.bin"
    val destination = getInternalModelFile(context)
    return try {
        destination.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null
        name
    } catch (exception: IOException) {
        null
    }
}

internal fun getInternalModelFile(context: Context): File {
    return File(context.filesDir, AiChatPreferences.INTERNAL_MODEL_RELATIVE_PATH)
}
