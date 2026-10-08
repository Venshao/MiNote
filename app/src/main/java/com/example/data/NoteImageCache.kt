package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

enum class NoteImageDownloadResult {
    /** 本地已有，或本次下载成功并落盘 */
    Cached,
    /** 网络/解析失败（可下次重试） */
    Failed,
    /** Cookie/登录失效，应中止本轮同步 */
    AuthExpired
}

/**
 * 通过 H5 接口下载便签图片并**永久缓存**到应用私有目录。
 * 展示时只读本地文件，不再依赖 Cookie。
 *
 * GET https://i.mi.com/file/full?type=note_img&fileid={fileId}
 */
object NoteImageCache {
    private const val DIR_NAME = "note_images"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

    fun cacheDir(context: Context): File {
        return File(context.filesDir, DIR_NAME).also { it.mkdirs() }
    }

    fun safeFileId(fileId: String): String {
        return fileId.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    fun findCachedFile(context: Context, fileId: String): File? {
        if (fileId.isBlank()) return null
        val dir = cacheDir(context)
        val base = safeFileId(fileId)
        val exact = File(dir, base)
        if (exact.isFile && exact.length() > 0L) return exact
        return dir.listFiles()?.firstOrNull { file ->
            file.isFile && file.length() > 0L &&
                (file.name == base || file.name.startsWith("$base."))
        }
    }

    fun isCached(context: Context, fileId: String): Boolean {
        return findCachedFile(context, fileId) != null
    }

    fun deleteCachedFile(context: Context, fileId: String): Boolean {
        val file = findCachedFile(context, fileId) ?: return false
        return try {
            file.delete()
        } catch (_: Exception) {
            false
        }
    }

    /** 与官方便签一致：本地附件 fileId = 文件内容 SHA-1 十六进制 */
    fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    /**
     * 从相册/文件选择器导入图片：压缩后按 SHA1 作为 fileId 落盘。
     * @return fileId，失败返回 null
     */
    fun importFromUri(context: Context, uri: Uri): String? {
        return try {
            val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (raw.isEmpty()) return null
            val bytes = compressIfNeeded(raw)
            val fileId = sha1Hex(bytes)
            saveBytes(context, fileId, bytes) ?: return null
            fileId
        } catch (_: Exception) {
            null
        }
    }

    /** 过大图片压缩为 JPEG，控制体积，贴近官方 AttachmentUtils.compress 行为 */
    fun compressIfNeeded(bytes: ByteArray, maxEdge: Int = 2048, maxBytes: Int = 1_500_000): ByteArray {
        if (bytes.size <= maxBytes) return bytes
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return bytes

            var sample = 1
            val longer = maxOf(bounds.outWidth, bounds.outHeight)
            while (longer / sample > maxEdge * 2) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return bytes
            val scaled = if (maxOf(bitmap.width, bitmap.height) > maxEdge) {
                val ratio = maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height)
                val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
                val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, w, h, true).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            scaled.recycle()
            out.toByteArray().takeIf { it.isNotEmpty() } ?: bytes
        } catch (_: Exception) {
            bytes
        }
    }

    /** 从备份导入时写入本地缓存（按 fileId 索引） */
    fun saveBytes(context: Context, fileId: String, bytes: ByteArray, fileNameHint: String? = null): File? {
        if (fileId.isBlank() || bytes.isEmpty()) return null
        findCachedFile(context, fileId)?.let { return it }

        val ext = when {
            !fileNameHint.isNullOrBlank() && fileNameHint.contains('.') ->
                "." + fileNameHint.substringAfterLast('.').lowercase().take(8)
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> ".png"
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> ".jpg"
            bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" -> ".webp"
            else -> ".img"
        }
        return writeAtomically(context, fileId, ext, bytes)
    }

    fun saveBase64(context: Context, fileId: String, base64: String, fileNameHint: String? = null): File? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            saveBytes(context, fileId, bytes, fileNameHint)
        } catch (_: Exception) {
            null
        }
    }

    fun readBase64(context: Context, fileId: String): String? {
        val file = findCachedFile(context, fileId) ?: return null
        return try {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeAtomically(context: Context, fileId: String, ext: String, bytes: ByteArray): File? {
        val outFile = File(cacheDir(context), safeFileId(fileId) + ext)
        val tmp = File(outFile.absolutePath + ".tmp")
        return try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(outFile)) {
                tmp.copyTo(outFile, overwrite = true)
                tmp.delete()
            }
            outFile.takeIf { it.isFile && it.length() > 0L }
        } catch (_: Exception) {
            tmp.delete()
            null
        }
    }

    fun buildDownloadRequest(cookie: String, fileId: String): Request {
        val url = "https://i.mi.com/file/full".toHttpUrl().newBuilder()
            .addQueryParameter("type", "note_img")
            .addQueryParameter("fileid", fileId)
            .build()

        return Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("Referer", "https://i.mi.com/note/h5")
            .get()
            .build()
    }

    /**
     * 已缓存则直接 Cached；否则用 Cookie 下载并落盘。
     * AuthExpired 表示应停止本轮同步，已落盘的文件不受影响。
     */
    fun downloadIfNeeded(
        context: Context,
        client: OkHttpClient,
        cookie: String,
        fileId: String
    ): NoteImageDownloadResult {
        if (fileId.isBlank()) return NoteImageDownloadResult.Failed
        if (isCached(context, fileId)) return NoteImageDownloadResult.Cached

        val cleanCookie = cookie.replace("\n", "").replace("\r", "").trim()
        if (cleanCookie.isBlank()) return NoteImageDownloadResult.AuthExpired

        return try {
            client.newCall(buildDownloadRequest(cleanCookie, fileId)).execute().use { response ->
                val code = response.code
                if (code == 401 || code == 403) return NoteImageDownloadResult.AuthExpired

                val body = response.body ?: return NoteImageDownloadResult.Failed
                val bytes = body.bytes()
                if (bytes.isEmpty()) {
                    // 空体：仅 401/403 视为登录失效，404 等单图缺失只记 Failed，避免中止整轮同步
                    return NoteImageDownloadResult.Failed
                }

                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.contains("json") || contentType.contains("text/html")) {
                    val text = bytes.toString(Charsets.UTF_8).lowercase()
                    val authHint = text.contains("login") || text.contains("unauthorized") ||
                        text.contains("重新登录") || text.contains("\"code\":401") ||
                        text.contains("\"code\":10001") || text.contains("\"code\":10017") ||
                        (text.contains("cookie") && (text.contains("invalid") || text.contains("expire")))
                    // 必须有明确鉴权线索，或本就是 401/403；普通 404 JSON 不得当成 Cookie 失效
                    return if (authHint || code == 401 || code == 403) {
                        NoteImageDownloadResult.AuthExpired
                    } else {
                        NoteImageDownloadResult.Failed
                    }
                }

                if (!response.isSuccessful) return NoteImageDownloadResult.Failed

                val ext = when {
                    contentType.contains("png") -> ".png"
                    contentType.contains("jpeg") || contentType.contains("jpg") -> ".jpg"
                    contentType.contains("webp") -> ".webp"
                    contentType.contains("gif") -> ".gif"
                    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> ".png"
                    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> ".jpg"
                    else -> ".img"
                }

                val saved = writeAtomically(context, fileId, ext, bytes)
                if (saved != null) NoteImageDownloadResult.Cached else NoteImageDownloadResult.Failed
            }
        } catch (_: Exception) {
            NoteImageDownloadResult.Failed
        }
    }
}
