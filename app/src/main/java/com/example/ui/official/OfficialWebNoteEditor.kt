package com.example.ui.official

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.NoteImageCache
import org.json.JSONObject
import java.io.FileInputStream

private const val TAG = "OfficialWebEditor"

/**
 * 官方便签编辑器：assets/noteeditor WebView + rich_editor_interface。
 *
 * 注意：官方 themeId=0 时若系统是深色模式，会切到 dark（白字）+ 透明背景，
 * 叠在浅色 Compose 底上会「看不见字」。这里强制 light 主题色。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OfficialWebNoteEditor(
    title: String,
    richText: String,
    themeId: Int = 0,
    modifier: Modifier = Modifier,
    onTitleChanged: (String) -> Unit,
    onRichTextChanged: (String) -> Unit,
    onReadyChanged: (Boolean) -> Unit = {},
    onWebViewCreated: (WebView) -> Unit = {}
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // 仅在官方 onPagePrepared 后再 setData，避免 React 未就绪
    var pagePrepared by remember { mutableStateOf(false) }
    var dataPushed by remember { mutableStateOf(false) }

    // 最新草稿，供 bridge / setData 读取（避免闭包过期）
    val titleRef = remember { mutableStateOf(title) }
    val bodyRef = remember { mutableStateOf(richText) }
    titleRef.value = title
    bodyRef.value = richText

    val bridge = remember {
        OfficialRichEditorBridge(object : OfficialRichEditorBridge.Callbacks {
            override fun onPagePrepared() {
                Log.i(TAG, "onPagePrepared")
                pagePrepared = true
                onReadyChanged(true)
                webViewRef?.let { wv ->
                    forceLightTheme(wv)
                    // 稍延后，等 React 完成首帧
                    mainHandler.postDelayed({
                        pushDataToEditor(wv, titleRef.value, bodyRef.value)
                        dataPushed = true
                    }, 80)
                }
            }

            override fun onSaveData(richText: String) {
                bodyRef.value = richText
                onRichTextChanged(richText)
            }

            override fun onTitleChanged(title: String) {
                titleRef.value = title
                onTitleChanged(title)
            }

            override fun onTextCountChanged(count: Int) = Unit

            override fun showToast(message: String) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }

            override fun contentLength(): Long = bodyRef.value.length.toLong()

            override fun isDarkMode(): Boolean = false
        })
    }

    DisposableEffect(Unit) {
        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            webViewRef?.apply {
                removeJavascriptInterface("rich_editor_interface")
                stopLoading()
                destroy()
            }
            webViewRef = null
            onReadyChanged(false)
        }
    }

    // 外部笔记切换时重新灌入
    androidx.compose.runtime.LaunchedEffect(title, richText, pagePrepared) {
        if (!pagePrepared || !dataPushed) return@LaunchedEffect
        // 仅当外部数据与当前不同（例如换便签）时再推；编辑过程以 Web 回调为准
        val wv = webViewRef ?: return@LaunchedEffect
        if (title != titleRef.value || richText != bodyRef.value) {
            // 外部主动改了（一般不会），忽略
            return@LaunchedEffect
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.WHITE)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mediaPlaybackRequiresUserGesture = false
                settings.textZoom = 100
                settings.useWideViewPort = true
                WebView.setWebContentsDebuggingEnabled(true)

                addJavascriptInterface(bridge, "rich_editor_interface")

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage ?: return super.onConsoleMessage(consoleMessage)
                        Log.d(TAG, "console[${msg.messageLevel()}] ${msg.message()} @${msg.lineNumber()}")
                        return true
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.i(TAG, "onPageFinished $url")
                        view?.let { forceLightTheme(it) }
                        // 若官方迟迟不回调 onPagePrepared，兜底灌数据
                        mainHandler.postDelayed({
                            if (!pagePrepared) {
                                Log.w(TAG, "onPagePrepared timeout, fallback setData")
                                pagePrepared = true
                                onReadyChanged(true)
                                view?.let {
                                    pushDataToEditor(it, titleRef.value, bodyRef.value)
                                    dataPushed = true
                                }
                            }
                        }, 1200)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val uri = request?.url ?: return null
                        return interceptOfficialMedia(ctx.applicationContext, uri)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        url: String?
                    ): WebResourceResponse? {
                        if (url.isNullOrBlank()) return null
                        return interceptOfficialMedia(ctx.applicationContext, Uri.parse(url))
                    }
                }

                // themeId 映射到官方非 0 主题可避开 dark 自动切换；同时注入强制 light
                val officialTheme = mapToOfficialThemeId(themeId)
                loadUrl("file:///android_asset/noteeditor/index.html${buildInitQuery(officialTheme)}")
                webViewRef = this
                onWebViewCreated(this)
            }
        },
        update = { view ->
            webViewRef = view
        }
    )
}

fun requestOfficialEditorSave(webView: WebView?) {
    webView?.evaluateJavascript(
        "(function(){try{if(typeof notifySaveData==='function'){notifySaveData();}}catch(e){}})()",
        null
    )
}

fun pushDataToEditor(webView: WebView, title: String, richText: String) {
    val body = JSONObject.quote(richText)
    val ttl = JSONObject.quote(title)
    val query = JSONObject.quote("")
    // 对齐官方：setData(richTextJson, titleJson, searchJson, isNeedSetData)
    val js = "javascript:(function(){try{if(typeof setData==='function'){setData($body,$ttl,$query,true);}else{console.log('setData missing');}}catch(e){console.log('setData error',e);}})()"
    Log.i(TAG, "pushDataToEditor titleLen=${title.length} bodyLen=${richText.length}")
    webView.evaluateJavascript(js, null)
}

/**
 * 强制浅色：官方 themeId=0 会看 prefers-color-scheme:dark，白字叠浅底会看不见。
 */
private fun forceLightTheme(webView: WebView) {
    val js = """
        javascript:(function(){
          try {
            var orig = window.matchMedia.bind(window);
            window.matchMedia = function(q) {
              if (String(q).indexOf('dark') >= 0) {
                return { matches:false, media:q, onchange:null,
                  addListener:function(){}, removeListener:function(){},
                  addEventListener:function(){}, removeEventListener:function(){},
                  dispatchEvent:function(){return false;} };
              }
              return orig(q);
            };
            window.color_mode = 'light';
            if (typeof setTheme === 'function') {
              // 传非 0 主题 id 再切回 light 不可靠；直接写 CSS 变量
            }
            var root = document.documentElement;
            root.style.setProperty('--theme-ui-colors-text', 'rgba(0, 0, 0, 0.87)');
            root.style.setProperty('--theme-ui-colors-background', '#FFFFFF');
            root.style.setProperty('color', 'rgba(0, 0, 0, 0.87)');
            root.style.setProperty('background-color', '#FFFFFF');
            if (document.body) {
              document.body.style.color = 'rgba(0, 0, 0, 0.87)';
              document.body.style.backgroundColor = '#FFFFFF';
            }
            console.log('forceLightTheme applied, color_mode=', window.color_mode);
          } catch (e) { console.log('forceLightTheme error', e); }
        })()
    """.trimIndent().replace('\n', ' ')
    webView.evaluateJavascript(js, null)
}

/** 我们的 colorId → 官方 themeId（避开 0 的自动 dark） */
private fun mapToOfficialThemeId(colorId: Int): Int {
    return when (colorId % 5) {
        0 -> 1 // blue（非 0，不会走 dark 自动分支）
        1 -> 2 // yellow
        2 -> 1 // blue
        3 -> 3 // green
        4 -> 4 // red/pink
        else -> 1
    }
}

private fun buildInitQuery(themeId: Int): String {
    // textScale / paddings 用 JSON 数字风格，贴近官方 Gson.toJson
    return "?themeId=$themeId&textScale=1.0&textPadding=[24,0,24,0]&borderPadding=[0,0,0,0]&fontWeight=2"
}

/** 官方编辑器图片：https://com.miui.notes/note_image/{fileId} */
private fun interceptOfficialMedia(context: Context, uri: Uri): WebResourceResponse? {
    if (uri.host != "com.miui.notes") return null
    val parts = uri.path.orEmpty().trim('/').split('/')
    if (parts.size < 2) return null
    val kind = parts[0]
    val fileId = parts[1].substringBefore("--")
    if (fileId.isBlank()) return null
    if (kind != "note_image" && kind != "note_audio") return null

    val file = NoteImageCache.findCachedFile(context, fileId) ?: return null
    val mime = when {
        kind == "note_audio" -> "audio/mpeg"
        file.name.endsWith(".png", true) -> "image/png"
        file.name.endsWith(".webp", true) -> "image/webp"
        file.name.endsWith(".gif", true) -> "image/gif"
        else -> "image/jpeg"
    }
    return WebResourceResponse(mime, null, FileInputStream(file))
}
