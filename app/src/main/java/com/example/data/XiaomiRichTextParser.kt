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
     * 兼容官方 H5 `pmNode2SavedData` 输出（含 `&lt;new-format/&gt;`、`&lt;bullet/&gt;`）。
     */
    fun parse(richText: String): List<RichTextLine> {
        if (richText.isBlank()) return emptyList()
        val normalized = stripNewFormatPrefix(richText)
        val lines = normalized.split("\n")
        return lines.mapNotNull { line ->
            var trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == "<new-format/>") return@mapNotNull null
            // 行首可能残留 new-format 前缀
            if (trimmed.startsWith("<new-format/>")) {
                trimmed = trimmed.removePrefix("<new-format/>").trim()
                if (trimmed.isEmpty()) return@mapNotNull null
            }

            val imgMatch = imgTagRegex.find(trimmed)
            when {
                imgMatch != null && trimmed.startsWith("<img", ignoreCase = true) -> {
                    RichTextLine(
                        isCheckbox = false,
                        checked = false,
                        text = "",
                        fileId = imgMatch.groupValues[1]
                    )
                }
                trimmed.startsWith("<input") && trimmed.contains("type=\"checkbox\"") -> {
                    val isChecked = trimmed.contains("checked=\"true\"")
                    val content = contentAfterSelfClosing(trimmed)
                    val hasRight = content.contains("<right>") && content.contains("</right>")
                    RichTextLine(
                        isCheckbox = true,
                        checked = isChecked,
                        text = unescape(extractRightInner(content, hasRight)),
                        isRightAligned = hasRight
                    )
                }
                trimmed.startsWith("<bullet") -> {
                    RichTextLine(
                        isCheckbox = false,
                        checked = false,
                        text = "• ${unescape(contentAfterSelfClosing(trimmed))}"
                    )
                }
                trimmed.startsWith("<order") -> {
                    RichTextLine(
                        isCheckbox = false,
                        checked = false,
                        text = unescape(contentAfterSelfClosing(trimmed))
                    )
                }
                trimmed.startsWith("<text") -> {
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
                    RichTextLine(
                        isCheckbox = false,
                        checked = false,
                        text = unescape(extractRightInner(content, hasRight)),
                        isRightAligned = hasRight
                    )
                }
                trimmed.startsWith("<h1") || trimmed.startsWith("<h2") || trimmed.startsWith("<h3") ||
                    trimmed.startsWith("<hr") || trimmed.startsWith("<quote") ||
                    trimmed.startsWith("<ul") || trimmed.startsWith("<ol") || trimmed.startsWith("<li") -> {
                    // 结构标签：列表预览里尽量抽出纯文本
                    val plain = trimmed.replace(Regex("<[^>]+>"), "").trim()
                    if (plain.isEmpty()) null
                    else RichTextLine(isCheckbox = false, checked = false, text = unescape(plain))
                }
                else -> {
                    val hasRight = trimmed.contains("<right>") && trimmed.contains("</right>")
                    RichTextLine(
                        isCheckbox = false,
                        checked = false,
                        text = extractRightInner(trimmed, hasRight),
                        isRightAligned = hasRight
                    )
                }
            }
        }
    }

    /** 官方新格式正文前缀；编辑器 round-trip 可保留，列表解析前剥掉 */
    fun stripNewFormatPrefix(richText: String): String {
        val t = richText.trimStart()
        return if (t.startsWith("<new-format/>")) t.removePrefix("<new-format/>") else richText
    }

    private fun contentAfterSelfClosing(tagLine: String): String {
        val closingIdx = tagLine.indexOf("/>")
        return if (closingIdx != -1) tagLine.substring(closingIdx + 2) else tagLine
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
                // 对齐官方 HtmlParser$ImageElement：fileid / imgshow / imgdes
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

    /** 在正文末尾追加官方格式图片行（Web 编辑器 insertImage 失败时的兜底） */
    fun appendImageTag(richText: String, fileId: String, imgShow: String = "0", imgDes: String = ""): String {
        val tag = """<img fileid="$fileId" imgshow="$imgShow" imgdes="$imgDes" />"""
        return if (richText.isBlank()) tag else richText.trimEnd() + "\n" + tag
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
