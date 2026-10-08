package com.example.data

import java.util.regex.Pattern

/**
 * 精简自官方 `com.miui.richeditor.schema.NoteSchema`。
 * 编辑器 setData 前需 convertToWeb；存储侧保持现代 img/checkbox 格式。
 */
object NoteSchema {
    private const val MULTILINE = Pattern.MULTILINE

    private val PATTERN_ANCIENT_CHECK =
        Pattern.compile("^√ (.+?)$", MULTILINE)
    private val PATTERN_ANCIENT_IMAGE_OLD =
        Pattern.compile("☺ ([^\r\n<]+)", MULTILINE)
    private val PATTERN_ANCIENT_IMAGE =
        Pattern.compile("☺ ([^\r\n<]+)<([^\r\n<]+)/><([^\r\n<]*)/>", MULTILINE)
    private val PATTERN_ANCIENT_MIUI_DOODLE =
        Pattern.compile("☼ ([^\r\n<]+)<([^\r\n<]+)/>", MULTILINE)
    private val PATTERN_ANCIENT_CONTACT =
        Pattern.compile("##([^\r\n]+?)@([^\r\n]+?)##")

    /** 官方 setData 入口转换：古格式图片 → 现代 img 标签 */
    fun convertToWeb(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        return PATTERN_ANCIENT_IMAGE.matcher(raw)
            .replaceAll("""<img fileid="$1" imgshow="$2" imgdes="$3" />""")
    }

    /** 古格式全文 → 现代可编辑格式（checkbox/图片/涂鸦/联系人） */
    fun convertFromAncient(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var s = raw
        s = PATTERN_ANCIENT_CHECK.matcher(s).replaceAll("<del>$1</del>")
        s = PATTERN_ANCIENT_IMAGE.matcher(s)
            .replaceAll("""<img fileid="$1" imgshow="$2" imgdes="$3" />""")
        s = PATTERN_ANCIENT_IMAGE_OLD.matcher(s)
            .replaceAll("""<img fileid="$1" imgshow="0" imgdes="" />""")
        s = PATTERN_ANCIENT_MIUI_DOODLE.matcher(s)
            .replaceAll("""<midoodle fileid="$1" imgshow="$2" />""")
        s = PATTERN_ANCIENT_CONTACT.matcher(s)
            .replaceAll("""<miui class="contact" phone="$1">$2</miui>""")
        return s
    }

    /** 写入编辑器前：先古→现，再 convertToWeb（与官方链路一致） */
    fun prepareForEditor(raw: String?): String {
        return convertToWeb(convertFromAncient(raw))
    }
}
