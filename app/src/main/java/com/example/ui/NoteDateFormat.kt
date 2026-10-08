package com.example.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 列表时间展示：今天 / 昨天 / 本年月日 / 完整日期 */
fun formatMiNoteDate(timestamp: Long): String {
    val date = Date(timestamp)
    val now = Date()
    val sdfToday = SimpleDateFormat("HH:mm", Locale.getDefault())
    val sdfThisYear = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
    val sdfFull = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    val diff = now.time - timestamp
    val oneDayMs = 24 * 60 * 60 * 1000L

    @Suppress("DEPRECATION")
    return when {
        diff in 0 until oneDayMs && date.date == now.date -> "今天 ${sdfToday.format(date)}"
        diff in oneDayMs until (2 * oneDayMs) -> "昨天 ${sdfToday.format(date)}"
        else -> {
            val yearSdf = SimpleDateFormat("yyyy", Locale.getDefault())
            if (yearSdf.format(date) == yearSdf.format(now)) {
                sdfThisYear.format(date)
            } else {
                sdfFull.format(date)
            }
        }
    }
}
