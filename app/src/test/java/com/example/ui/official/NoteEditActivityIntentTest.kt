package com.example.ui.official

import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：不依赖 Robolectric，避免本地首次拉镜像卡住 */
class NoteEditActivityIntentTest {

    @Test
    fun mapToOfficialThemeId_avoidsZero() {
        for (colorId in 0..4) {
            val themeId = NoteEditActivity.mapToOfficialThemeId(colorId)
            assertTrue("themeId must be non-zero for colorId=$colorId", themeId != 0)
        }
    }
}
