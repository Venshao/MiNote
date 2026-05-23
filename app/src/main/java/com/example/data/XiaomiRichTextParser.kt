package com.example.data

data class RichTextLine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val isCheckbox: Boolean,
    val checked: Boolean,
    val text: String,
    val isRightAligned: Boolean = false
)

/** 列表卡片预览用的轻量行（不生成 UUID，只解析前几行） */
data class NotePreviewLine(
    val text: String,
    val isCheckbox: Boolean = false,
    val checked: Boolean = false
)

data class NoteCardPreview(
    val displayTitle: String,
    val bodyLines: List<NotePreviewLine>,
    /** 合并后的预览正文，列表里用单个 Text 渲染 */
    val bodyText: String
)

object XiaomiRichTextParser {
    /**
     * Parse rich text content containing <text indent="X">...</text> or <input type="checkbox"... /> tags into a list of lines.
     */
    fun parse(richText: String): List<RichTextLine> {
        if (richText.isBlank()) return emptyList()
        val lines = richText.split("\n")
        return lines.map { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("<input") && trimmed.contains("type=\"checkbox\"")) {
                val isChecked = trimmed.contains("checked=\"true\"")
                // Extract everything after '/>'
                val closingIdx = trimmed.indexOf("/>")
                val content = if (closingIdx != -1) {
                    trimmed.substring(closingIdx + 2)
                } else {
                    trimmed
                }
                
                // Let's check for <right> tag inside raw content
                val hasRight = content.contains("<right>") && content.contains("</right>")
                val textWithNoRight = if (hasRight) {
                    val rs = content.indexOf("<right>")
                    val re = content.lastIndexOf("</right>")
                    if (re > rs) {
                        content.substring(rs + 7, re)
                    } else {
                        content.replace("<right>", "").replace("</right>", "")
                    }
                } else {
                    content
                }
                
                RichTextLine(
                    isCheckbox = true,
                    checked = isChecked,
                    text = unescape(textWithNoRight),
                    isRightAligned = hasRight
                )
            } else if (trimmed.startsWith("<text")) {
                val startIdx = trimmed.indexOf('>')
                val endIdx = trimmed.lastIndexOf("</text>")
                val content = if (startIdx != -1 && endIdx > startIdx) {
                    trimmed.substring(startIdx + 1, endIdx)
                } else if (startIdx != -1) {
                    trimmed.substring(startIdx + 1)
                } else {
                    trimmed
                }
                
                // Let's check for <right> tag inside raw content
                val hasRight = content.contains("<right>") && content.contains("</right>")
                val textWithNoRight = if (hasRight) {
                    val rs = content.indexOf("<right>")
                    val re = content.lastIndexOf("</right>")
                    if (re > rs) {
                        content.substring(rs + 7, re)
                    } else {
                        content.replace("<right>", "").replace("</right>", "")
                    }
                } else {
                    content
                }
                
                RichTextLine(
                    isCheckbox = false,
                    checked = false,
                    text = unescape(textWithNoRight),
                    isRightAligned = hasRight
                )
            } else {
                val hasRight = line.contains("<right>") && line.contains("</right>")
                val textWithNoRight = if (hasRight) {
                    val rs = line.indexOf("<right>")
                    val re = line.lastIndexOf("</right>")
                    if (re > rs) {
                        line.substring(rs + 7, re)
                    } else {
                        line.replace("<right>", "").replace("</right>", "")
                    }
                } else {
                    line
                }
                RichTextLine(
                    isCheckbox = false,
                    checked = false,
                    text = textWithNoRight,
                    isRightAligned = hasRight
                )
            }
        }
    }

    /**
     * Map a standard representation of parsed lines back to the Xiaomi rich-text XML tagged format.
     */
    fun toRichTextFromLines(lines: List<RichTextLine>): String {
        return lines.joinToString("\n") { line ->
            val escapedText = escape(line.text)
            val wrappedText = if (line.isRightAligned) "<right>$escapedText</right>" else escapedText
            if (line.isCheckbox) {
                val checkedAttr = if (line.checked) " checked=\"true\"" else ""
                "<input type=\"checkbox\"$checkedAttr indent=\"1\" level=\"3\" />$wrappedText"
            } else {
                "<text indent=\"1\">$wrappedText</text>"
            }
        }
    }

    /**
     * Convert XML rich text to editable Markdown-like plain text in the editor.
     */
    fun toEditableText(richText: String): String {
        val parsed = parse(richText)
        return parsed.joinToString("\n") { line ->
            if (line.isCheckbox) {
                if (line.checked) "[x] ${line.text}" else "[ ] ${line.text}"
            } else {
                line.text
            }
        }
    }

    /**
     * Parse the edited Markdown-like plain text back to rich text XML.
     */
    fun toRichTextFromEditableText(editableText: String): String {
        val lines = editableText.split("\n")
        val richLines = lines.map { line ->
            if (line.startsWith("[ ] ")) {
                RichTextLine(isCheckbox = true, checked = false, text = line.substring(4))
            } else if (line.startsWith("[x] ") || line.startsWith("[X] ")) {
                RichTextLine(isCheckbox = true, checked = true, text = line.substring(4))
            } else {
                RichTextLine(isCheckbox = false, checked = false, text = line)
            }
        }
        return toRichTextFromLines(richLines)
    }

    /**
     * Simple plain text with no tags for indexing/display.
     */
    fun toPlainText(richText: String): String {
        val parsed = parse(richText)
        return parsed.joinToString("\n") { it.text }
    }

    /**
     * 仅解析前若干行用于列表卡片预览，避免长便签全文解析造成滑动卡顿。
     */
    fun buildNotePreview(title: String, snippet: String, maxBodyLines: Int = 3): NoteCardPreview {
        if (snippet.isBlank()) {
            val displayTitle = title.ifBlank { "无标题便签" }
            return NoteCardPreview(displayTitle, emptyList(), "")
        }

        val limitedSnippet = snippet.lineSequence()
            .take(maxBodyLines + 2)
            .joinToString("\n")

        val parsed = parse(limitedSnippet).map { line ->
            NotePreviewLine(
                text = line.text,
                isCheckbox = line.isCheckbox,
                checked = line.checked
            )
        }

        val displayTitle = when {
            title.isNotBlank() -> title
            else -> parsed.firstOrNull()?.text?.takeIf { it.isNotBlank() } ?: "无标题便签"
        }

        val bodyLines = if (title.isBlank()) {
            parsed.drop(1).take(maxBodyLines)
        } else {
            parsed.take(maxBodyLines)
        }

        val bodyText = bodyLines.joinToString("\n") { line ->
            when {
                line.isCheckbox && line.checked -> "✓ ${line.text}"
                line.isCheckbox -> "○ ${line.text}"
                else -> line.text
            }
        }

        return NoteCardPreview(displayTitle, bodyLines, bodyText)
    }

    private fun escape(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun unescape(text: String): String {
        return text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }
}
