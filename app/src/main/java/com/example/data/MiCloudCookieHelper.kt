package com.example.data

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 从小米云 WebView / CookieManager 提取可用于便签 API 的 Cookie，并做轻量有效性探测。
 */
object MiCloudCookieHelper {
    const val MI_CLOUD_ORIGIN = "https://i.mi.com"
    const val NOTE_H5_URL = "https://i.mi.com/note/h5"
    const val LOGIN_ENTRY_URL = "https://i.mi.com/"

    private val REQUIRED_MARKERS = listOf("serviceToken", "userId", "cUserId")

    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun cookieForMiCloud(): String? {
        val cm = CookieManager.getInstance()
        cm.flush()
        val raw = cm.getCookie(MI_CLOUD_ORIGIN)?.trim().orEmpty()
        return raw.takeIf { isLikelyValid(it) }
    }

    fun isLikelyValid(cookie: String): Boolean {
        if (cookie.isBlank()) return false
        val lower = cookie.lowercase()
        // 便签接口至少需要 serviceToken；userId/cUserId 常见但非绝对必须
        return lower.contains("servicetoken")
    }

    fun looksLoggedInOnUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase()
        if (u.contains("account.xiaomi.com") || u.contains("account.xiaomi.cn")) return false
        if (u.contains("pass/serviceLogin", ignoreCase = true)) return false
        return u.contains("i.mi.com")
    }

    fun clearMiCloudCookies() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
    }

    /**
     * 打便签列表接口（limit=1）确认 Cookie 对云端 API 仍有效。
     * 本地有 serviceToken 不等于接口可用——过期后 WebView 常会刷成新 Cookie。
     */
    suspend fun probeCookieValid(cookie: String): Boolean = withContext(Dispatchers.IO) {
        val clean = cookie.replace("\n", "").replace("\r", "").trim()
        if (!isLikelyValid(clean)) return@withContext false
        try {
            val ts = System.currentTimeMillis()
            val request = Request.Builder()
                .url("https://i.mi.com/note/full/page?ts=$ts&limit=1")
                .header("Cookie", clean)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"
                )
                .header("Accept", "*/*")
                .header("Referer", "https://i.mi.com/note/h5")
                .build()
            probeClient.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) return@withContext false
                if (!response.isSuccessful) return@withContext false
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext false
                val json = JSONObject(body)
                val code = json.optInt("code", -1)
                val result = json.optString("result", "")
                code == 0 && result == "ok"
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 调试用：看 Cookie 里命中了哪些关键字段（不输出完整值）。 */
    fun summarize(cookie: String): String {
        val names = cookie.split(';')
            .map { it.trim().substringBefore('=') }
            .filter { it.isNotBlank() }
        val hit = REQUIRED_MARKERS.filter { marker ->
            names.any { it.equals(marker, ignoreCase = true) }
        }
        return "keys=${names.size}, hit=$hit"
    }
}
