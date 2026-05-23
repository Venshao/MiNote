package com.example.ui.theme

import androidx.compose.ui.graphics.Color

object MiNoteColors {
    /** 与右下角创建按钮一致的主题色 */
    val ActionYellow = Color(0xFFFFBC13)

    data class ColorPack(
        val bg: Color,
        val border: Color,
        val accent: Color,
        val text: Color = Color(0xFF1C1B1F), // Clean minimalist dark text
        val name: String
    )

    val colors = listOf(
        ColorPack(
            bg = Color(0xFFFFFFFF), 
            border = Color(0xFFE1E3E8), 
            accent = Color(0xFFFF6700), // Clean Xiaomi Orange accent
            name = "简约白"
        ), // Minimalist White (Default)
        ColorPack(
            bg = Color(0xFFFFF4E5), 
            border = Color(0xFFFFE0B2), 
            accent = Color(0xFFFF6700), 
            name = "暖阳黄"
        ), // Warm Pastel Orange/Yellow
        ColorPack(
            bg = Color(0xFFE3F2FD), 
            border = Color(0xFFBBDEFB), 
            accent = Color(0xFF1E88E5), 
            name = "静谧蓝"
        ), // Clean Blue
        ColorPack(
            bg = Color(0xFFE8F5E9), 
            border = Color(0xFFC8E6C9), 
            accent = Color(0xFF43A047), 
            name = "薄荷绿"
        ), // Mint Green
        ColorPack(
            bg = Color(0xFFFCE4EC), 
            border = Color(0xFFF8BBD0), 
            accent = Color(0xFFD81B60), 
            name = "珊瑚粉"
        )  // Pastel Pink
    )

    fun getColor(id: Int): ColorPack {
        return colors.getOrElse(id % colors.size) { colors[0] }
    }
}
