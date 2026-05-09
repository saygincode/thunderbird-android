package net.thunderbird.feature.ai.chat

internal object AiChatHelper {
    internal fun cleanEmailText(rawText: String): String {
        if (rawText.isBlank()) return rawText
        val normalized = rawText
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        val lines = normalized.lines()
        val cleanedLines = mutableListOf<String>()
        var stop = false
        for (line in lines) {
            val trimmed = line.trimEnd()
            val lower = trimmed.lowercase()
            if (lower.startsWith(">")) {
                continue
            }
            if (lower.startsWith("--") ||
                lower.startsWith("sent from my") ||
                (lower.startsWith("on ") && lower.contains(" wrote:"))
            ) {
                stop = true
            }
            if (stop) {
                break
            }
            cleanedLines.add(trimmed)
        }
        val withoutFooter = removeConfidentialityFooter(cleanedLines)
        return collapseBlankLines(withoutFooter.joinToString("\n")).trim()
    }

    private fun removeConfidentialityFooter(lines: List<String>): List<String> {
        if (lines.isEmpty()) return lines
        val markers = listOf("confidential", "privileged", "intended recipient", "disclaimer")
        val threshold = (lines.size * 0.6).toInt()
        for (i in lines.indices) {
            if (i < threshold) continue
            val lower = lines[i].lowercase()
            if (markers.any { lower.contains(it) }) {
                return lines.subList(0, i)
            }
        }
        return lines
    }

    private fun collapseBlankLines(text: String): String {
        val result = StringBuilder()
        var lastBlank = false
        for (line in text.lines()) {
            val isBlank = line.isBlank()
            if (isBlank && lastBlank) continue
            result.append(line).append('\n')
            lastBlank = isBlank
        }
        return result.toString().trimEnd()
    }

    internal fun buildPrompt(
        template: String,
        query: String,
        mail: String,
        answer: String,
        answers: String = "",
    ): String {
        return template
                .replace("{query}", query)
                .replace("{mail}", mail)
                .replace("{answer}", answer)
                .replace("{answers}", answers)
    }
}
