package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiRichTextParserTest {
    @Test
    fun parse_keepsImageFileIdBetweenTexts() {
        val content = """
            <text indent="1">第一句文本</text>
            <text indent="1">第二句文本</text>
            <img fileid="122752156.enzBL6Bst4InsxFfRG6DQQ" imgshow="0" imgdes="" />
            <text indent="1">图片之后的文本</text>
        """.trimIndent()

        val lines = XiaomiRichTextParser.parse(content)
        assertEquals(4, lines.size)
        assertEquals("第一句文本", lines[0].text)
        assertEquals("第二句文本", lines[1].text)
        assertTrue(lines[2].isImage)
        assertEquals("122752156.enzBL6Bst4InsxFfRG6DQQ", lines[2].fileId)
        assertEquals("图片之后的文本", lines[3].text)
    }

    @Test
    fun roundTrip_preservesImgTag() {
        val content = """
            <text indent="1">hello</text>
            <img fileid="122752156.enzBL6Bst4InsxFfRG6DQQ" imgshow="0" imgdes="" />
        """.trimIndent()
        val again = XiaomiRichTextParser.toRichTextFromLines(XiaomiRichTextParser.parse(content))
        assertTrue(again.contains("""fileid="122752156.enzBL6Bst4InsxFfRG6DQQ""""))
        assertTrue(again.contains("<text indent=\"1\">hello</text>"))
    }

    @Test
    fun extractImageFileIds_dedupes() {
        val content = """
            <img fileid="a.b" imgshow="0" imgdes="" />
            <text indent="1">x</text>
            <img fileid="a.b" imgshow="0" imgdes="" />
            <img fileid="c.d" />
        """.trimIndent()
        assertEquals(listOf("a.b", "c.d"), XiaomiRichTextParser.extractImageFileIds(content))
    }

    @Test
    fun toRichText_insertImageLine_matchesOfficialFormat() {
        val lines = listOf(
            RichTextLine(isCheckbox = false, checked = false, text = "前"),
            RichTextLine(isCheckbox = false, checked = false, text = "", fileId = "abc123"),
            RichTextLine(isCheckbox = false, checked = false, text = "后")
        )
        val xml = XiaomiRichTextParser.toRichTextFromLines(lines)
        assertTrue(xml.contains("""<img fileid="abc123" imgshow="0" imgdes="" />"""))
        assertTrue(xml.contains("""<text indent="1">前</text>"""))
        assertTrue(xml.contains("""<text indent="1">后</text>"""))
    }

    @Test
    fun sha1Hex_isStable() {
        val hex = NoteImageCache.sha1Hex("hello".toByteArray())
        assertEquals(40, hex.length)
        assertEquals(hex, NoteImageCache.sha1Hex("hello".toByteArray()))
    }

    @Test
    fun parse_officialH5SavedCheckboxAndText() {
        // 对齐 official noteeditor pmNode2SavedData 输出
        val h5 = """
            <text indent="1">标题下正文</text>
            <input type="checkbox" indent="1" level="3" />待办未勾
            <input type="checkbox" checked="true" indent="1" level="3" />已完成
            <img fileid="deadbeef" imgshow="0" imgdes="" />
        """.trimIndent()
        val lines = XiaomiRichTextParser.parse(h5)
        assertEquals(4, lines.size)
        assertEquals("标题下正文", lines[0].text)
        assertTrue(lines[1].isCheckbox && !lines[1].checked)
        assertEquals("待办未勾", lines[1].text)
        assertTrue(lines[2].isCheckbox && lines[2].checked)
        assertTrue(lines[3].isImage)
        assertEquals("deadbeef", lines[3].fileId)
    }

    @Test
    fun parse_stripsNewFormatAndBullet() {
        val h5 = """
            <new-format/>
            <bullet indent="1" />项目一
            <text indent="1">普通行</text>
        """.trimIndent()
        val lines = XiaomiRichTextParser.parse(h5)
        assertEquals(2, lines.size)
        assertTrue(lines[0].text.startsWith("•"))
        assertTrue(lines[0].text.contains("项目一"))
        assertEquals("普通行", lines[1].text)
    }

    @Test
    fun prepareForEditor_thenParse_roundTripModern() {
        val modern = """
            <text indent="1">hello</text>
            <input type="checkbox" indent="1" level="3" />todo
            <img fileid="a.b" imgshow="0" imgdes="" />
        """.trimIndent()
        val prepared = NoteSchema.prepareForEditor(modern)
        val lines = XiaomiRichTextParser.parse(prepared)
        assertEquals(3, lines.size)
        assertEquals("hello", lines[0].text)
        assertTrue(lines[1].isCheckbox)
        assertTrue(lines[2].isImage)
    }
}
