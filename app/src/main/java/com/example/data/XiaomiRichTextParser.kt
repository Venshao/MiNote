package com.example.data

data class RichTextLine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val isCheckbox: Boolean,
    val checked: Boolean,
    val text: String,
    val isRightAligned: Boolean = false,
    /** 非空表示该行为云端图片（content 中的 &lt;img fileid=... /&gt;） */
    val fileId: String? = null
) {
    val isImage: Boolean get() = !fileId.isNullOrBlank()
}

/** 列表卡片预览用的轻量行（不生成 UUID，只解析前几行） */
data class NotePreviewLine(
    val text: String,
    val isCheckbox: Boolean = false,
    val checked: Boolean = false,
    val isImage: Boolean = false
)

data class NoteCardPreview(
    val displayTitle: String,
    val bodyLines: List<NotePreviewLine>,
    /** 合并后的预览正文，列表里用单个 Text 渲染 */
    val bodyText: String
)

object XiaomiRichTextParser {
    private val imgTagRegex = Regex(
        """<img\b[^>]*\bfileid\s*=\s*"([^"]+)"[^>]*/?>""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Parse rich text content containing &lt;text&gt;, checkbox, or &lt;img fileid&gt; tags.
     */
    fun parse(richText: String): List<RichTextLine> {
        if (richText.isBlank()) return emptyList()
        val lines = richText.split("\n")
        return lines.map { line ->
            val trimmed = line.trim()
            val imgMatch = imgTagRegex.find(trimmed)
            if (imgMatch != null && trimmed.startsWith("<img", ignoreCase = true)) {
                RichTextLine(
                    isCheckbox = false,
                    checked = false,
                    text = "",
                    fileId = imgMatch.groupValues[1]
                )
            } else if (trimmed.startsWith("<input") && trimmed.contains("type=\"checkbox\"")) {
                val isChecked = trimmed.contains("checked=\"true\"")
                val closingIdx = trimmed.indexOf("/>")
                val content = if (closingIdx != -1) {
                    trimmed.substring(closingIdx + 2)
                } else {
                    trimmed
                }

                val hasRight = content.contains("<right>") && content.contains("</right>")
                val textWithNoRight = extractRightInner(content, hasRight)

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

                val hasRight = content.contains("<right>") && content.contains("</right>")
                val textWithNoRight = extractRightInner(content, hasRight)

                RichTextLine(
                    isCheckbox = false,
                    checked = false,
                    text = unescape(textWithNoRight),
                    isRightAligned = hasRight
                )
            } else {
                val hasRight = line.contains("<right>") && line.contains("</right>")
                val textWithNoRight = extractRightInner(line, hasRight)
                RichTextLine(
                    isCheckbox = false,
                    checked = false,
                    text = textWithNoRight,
                    isRightAligned = hasRight
                )
            }
        }
    }

    fun extractImageFileIds(richText: String): List<String> {
        if (richText.isBlank()) return emptyList()
        return imgTagRegex.findAll(richText)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    /**
     * Map a standard representation of parsed lines back to the Xiaomi rich-text XML tagged format.
     */
    fun toRichTextFromLines(lines: List<RichTextLine>): String {
        return lines.joinToString("\n") { line ->
            if (line.isImage) {
                """<img fileid="${line.fileId}" imgshow="0" imgdes="" />"""
            } else {
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
    }

    /**
     * Convert XML rich text to editable Markdown-like plain text in the editor.
     */
    fun toEditableText(richText: String): String {
        val parsed = parse(richText)
        return parsed.joinToString("\n") { line ->
            when {
                line.isImage -> "[图片]"
                line.isCheckbox && line.checked -> "[x] ${line.text}"
                line.isCheckbox -> "[ ] ${line.text}"
                else -> line.text
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
        return parsed.joinToString("\n") { line ->
            if (line.isImage) "[图片]" else line.text
        }
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
                text = if (line.isImage) "[图片]" else line.text,
                isCheckbox = line.isCheckbox,
                checked = line.checked,
                isImage = line.isImage
            )
        }

        val displayTitle = when {
            title.isNotBlank() -> title
            else -> parsed.firstOrNull { !it.isImage && it.text.isNotBlank() }?.text
                ?: parsed.firstOrNull()?.text?.takeIf { it.isNotBlank() }
                ?: "无标题便签"
        }

        val bodyLines = if (title.isBlank()) {
            parsed.drop(1).take(maxBodyLines)
        } else {
            parsed.take(maxBodyLines)
        }

        val bodyText = bodyLines.joinToString("\n") { line ->
            when {
                line.isImage -> "[图片]"
                line.isCheckbox && line.checked -> "✓ ${line.text}"
                line.isCheckbox -> "○ ${line.text}"
                else -> line.text
            }
        }

        return NoteCardPreview(displayTitle, bodyLines, bodyText)
    }

    private fun extractRightInner(content: String, hasRight: Boolean): String {
        if (!hasRight) return content
        val rs = content.indexOf("<right>")
        val re = content.lastIndexOf("</right>")
        return if (re > rs) {
            content.substring(rs + 7, re)
        } else {
            content.replace("<right>", "").replace("</right>", "")
        }
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
