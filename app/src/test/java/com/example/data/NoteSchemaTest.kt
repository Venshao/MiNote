package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSchemaTest {
    @Test
    fun convertToWeb_ancientImage_toModernImg() {
        val ancient = "前言\n☺ abc123<0/><描述/>\n后记"
        val web = NoteSchema.convertToWeb(ancient)
        assertTrue(web.contains("""<img fileid="abc123" imgshow="0" imgdes="描述" />"""))
        assertFalse(web.contains("☺"))
    }

    @Test
    fun convertFromAncient_checkboxAndImage() {
        val ancient = "√ 买牛奶\n☺ file1<1/><备注/>"
        val modern = NoteSchema.convertFromAncient(ancient)
        assertTrue(modern.contains("<del>买牛奶</del>"))
        assertTrue(modern.contains("""<img fileid="file1" imgshow="1" imgdes="备注" />"""))
    }

    @Test
    fun prepareForEditor_keepsModernFormat() {
        val modern = """
            <text indent="1">hello</text>
            <img fileid="a.b" imgshow="0" imgdes="" />
        """.trimIndent()
        assertEquals(modern, NoteSchema.prepareForEditor(modern))
    }

    @Test
    fun prepareForEditor_nullOrEmpty() {
        assertEquals("", NoteSchema.prepareForEditor(null))
        assertEquals("", NoteSchema.prepareForEditor(""))
    }
}
