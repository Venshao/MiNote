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
import com.example.data.NoteImageCache
import com.example.data.XiaomiRichTextParser
import org.json.JSONObject
import java.io.FileInputStream

/**
 * 官方 noteeditor WebView 控制器（对齐 WebRichEditor + HybridEditorScriptInterface）。
 * 供原生 Activity 使用，不依赖 Compose。
 */
class OfficialWebEditorController(
    private val context: Context,
    private val webView: WebView,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onReady()
        fun onTitleChanged(title: String)
        fun onContentChanged(richText: String)
        fun onToast(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pagePrepared = false
    private var titleCache = ""
    private var bodyCache = ""
    private var infoTimeMillis = System.currentTimeMillis()

    private val bridge = OfficialRichEditorBridge(object : OfficialRichEditorBridge.Callbacks {
        override fun onPagePrepared() {
            pagePrepared = true
            applyEditorUiFixes()
            mainHandler.postDelayed({
                pushData(titleCache, bodyCache)
                applyEditorUiFixes()
                refreshInfoBar()
                callbacks.onReady()
            }, 80)
        }

        override fun onSaveData(richText: String) {
            bodyCache = richText
            callbacks.onContentChanged(richText)
            if (pagePrepared) refreshInfoBar()
        }

        override fun onTitleChanged(title: String) {
            titleCache = title
            callbacks.onTitleChanged(title)
        }

        override fun onTextCountChanged(count: Int) {
            refreshInfoBar(textCount = count.coerceAtLeast(0))
        }
        override fun showToast(message: String) = callbacks.onToast(message)
        override fun contentLength(): Long = bodyCache.length.toLong()
        override fun isDarkMode(): Boolean = false
    })

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(themeId: Int = 1) {
        webView.setBackgroundColor(Color.WHITE)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            textZoom = 100
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
        }
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        WebView.setWebContentsDebuggingEnabled(true)
        webView.addJavascriptInterface(bridge, "rich_editor_interface")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val msg = consoleMessage ?: return false
                Log.d(TAG, "js[${msg.messageLevel()}] ${msg.message()}")
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                applyEditorUiFixes()
                mainHandler.postDelayed({
                    if (!pagePrepared) {
                        pagePrepared = true
                        pushData(titleCache, bodyCache)
                        applyEditorUiFixes()
                        callbacks.onReady()
                    }
                }, 1200)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                // 拦截官方自定义 scheme，避免跳出 WebView
                if (uri.host == "com.miui.notes") return true
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val uri = request?.url ?: return null
                return interceptMedia(uri)
            }
        }
        val tid = if (themeId == 0) 1 else themeId
        webView.loadUrl(
            "file:///android_asset/noteeditor/index.html" +
                "?themeId=$tid&textScale=1.0&textPadding=[24,0,24,0]&borderPadding=[0,0,0,0]&fontWeight=2"
        )
    }

    fun setInitialData(title: String, richText: String, modifyTimeMillis: Long = System.currentTimeMillis()) {
        titleCache = title
        bodyCache = richText
        infoTimeMillis = if (modifyTimeMillis > 0L) modifyTimeMillis else System.currentTimeMillis()
        bridge.infoTimeMillis = infoTimeMillis
        if (pagePrepared) {
            pushData(title, richText)
            refreshInfoBar()
        }
    }

    /** 对齐官方 WebRichEditor.updateExtraInto → window.updateExtraInto */
    fun refreshInfoBar(textCount: Int? = null) {
        val count = textCount ?: estimateTextCount(bodyCache)
        val timeText = bridge.formatInfoTime(infoTimeMillis)
        val js = "if(typeof updateExtraInto==='function'){" +
            "updateExtraInto(${JSONObject.quote(timeText)},$infoTimeMillis,$count,${JSONObject.quote("")},false);}"
        eval(js)
    }

    private fun estimateTextCount(richText: String): Int {
        return XiaomiRichTextParser.toPlainText(richText)
            .replace("[图片]", "")
            .replace("\u200B", "")
            .replace(Regex("\\s+"), "")
            .length
    }

    /**
     * 强制把当前 ProseMirror 文档刷回 native。
     * 官方 H5 无 window.notifySaveData；实际靠 editor.pmNode2SavedData → onSaveData。
     */
    fun requestSave(onFlushed: (() -> Unit)? = null) {
        val js = """
            (function(){
              try{
                var ed=window.editor;
                if(!ed||!ed.view||typeof ed.pmNode2SavedData!=='function'){return 'no-editor';}
                var data=ed.pmNode2SavedData(ed.view.state.doc);
                if(window.rich_editor_interface&&window.rich_editor_interface.onSaveData){
                  window.rich_editor_interface.onSaveData(data||'');
                }
                return 'ok:'+((data&&data.length)||0);
              }catch(e){return 'err:'+e;}
            })()
        """.trimIndent().replace('\n', ' ')
        webView.evaluateJavascript(js) {
            onFlushed?.let { cb -> mainHandler.post(cb) }
        }
    }

    fun latestBody(): String = bodyCache
    fun latestTitle(): String = titleCache

    // JS 名对齐官方 WebRichEditor.evaluateJavascript
    fun undo() = eval("if(typeof undo==='function'){undo();}")
    fun redo() = eval("if(typeof redo==='function'){redo();}")
    fun setCheckbox() = eval("if(typeof toggleCheckbox==='function'){toggleCheckbox();}")
    fun setBullet() = eval(
        "if(typeof toggleEditorBullet==='function'){toggleEditorBullet();}" +
            "else if(typeof toggleListBullet==='function'){toggleListBullet();}"
    )
    fun setBold() = eval("if(typeof toggleFontBold==='function'){toggleFontBold();}")
    fun setItalic() = eval("if(typeof toggleFontItalic==='function'){toggleFontItalic();}")
    fun setUnderline() = eval("if(typeof toggleUnderline==='function'){toggleUnderline();}")
    fun setH1() = eval("if(typeof toggleH1==='function'){toggleH1();}")
    fun setH2() = eval("if(typeof toggleH2==='function'){toggleH2();}")
    fun alignLeft() = eval("if(typeof setAlignLeft==='function'){setAlignLeft();}")
    fun alignCenter() = eval("if(typeof toggleAlignCenter==='function'){toggleAlignCenter();}")
    fun alignRight() = eval("if(typeof toggleAlignRight==='function'){toggleAlignRight();}")

    fun insertImageByFileId(fileId: String) {
        val json = JSONObject.quote(fileId)
        eval("if(typeof insertImageEnd==='function'){insertImageEnd([$json]);}")
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        webView.removeJavascriptInterface("rich_editor_interface")
        webView.stopLoading()
        webView.loadUrl("about:blank")
        // 不在此 destroy WebView：仍挂在布局树上时会崩溃，交由 Activity 销毁
    }

    private fun pushData(title: String, richText: String) {
        val prepared = com.example.data.NoteSchema.prepareForEditor(richText)
        val body = JSONObject.quote(prepared)
        val ttl = JSONObject.quote(title)
        val query = JSONObject.quote("")
        eval("if(typeof setData==='function'){setData($body,$ttl,$query,true);}")
        // setData 后占位/思维笔记按钮可能又被插回来；同时刷新日期/字数信息栏
        mainHandler.postDelayed({
            applyEditorUiFixes()
            refreshInfoBar()
        }, 120)
        mainHandler.postDelayed({
            applyEditorUiFixes()
            refreshInfoBar()
        }, 500)
        Log.i(TAG, "setData title=${title.length} body=${prepared.length}")
    }

    /**
     * 修复官方 H5 在独立壳里的几类 UI 问题：
     * 1) #fake-root / placeholder 叠在正文背后
     * 2) 选区文字前景发白/对比度差
     * 3) 「思维笔记」入口（canCreateMindNote=false 时仍可能渲染）
     */
    private fun applyEditorUiFixes() {
        eval(
            """
            (function(){
              try{
                var orig=window.matchMedia.bind(window);
                window.matchMedia=function(q){
                  if(String(q).indexOf('dark')>=0){
                    return {matches:false,media:q,onchange:null,
                      addListener:function(){},removeListener:function(){},
                      addEventListener:function(){},removeEventListener:function(){},
                      dispatchEvent:function(){return false;}};
                  }
                  return orig(q);
                };
                window.color_mode='light';
                var r=document.documentElement;
                r.style.setProperty('--theme-ui-colors-text','rgba(0,0,0,0.87)');
                r.style.setProperty('--theme-ui-colors-background','#FFFFFF');
                r.style.setProperty('--theme-ui-colors-selectArea','rgba(255,221,139,0.65)');
                r.style.setProperty('--theme-ui-colors-highlight','rgba(255,221,139,0.6)');
                r.style.setProperty('--text-color-selection','rgba(255,221,139,0.65)');
                r.style.color='rgba(0,0,0,0.87)';
                r.style.backgroundColor='#FFFFFF';
                if(document.body){
                  document.body.style.color='rgba(0,0,0,0.87)';
                  document.body.style.backgroundColor='#FFFFFF';
                }
                var fake=document.getElementById('fake-root');
                if(fake){fake.remove();}
                function docHasContent(){
                  var ed=window.editor;
                  if(ed && typeof ed.isEmptyDoc==='function'){
                    try{ if(!ed.isEmptyDoc()) return true; }catch(err){}
                  }
                  var pm=document.querySelector('.pm-container .ProseMirror')||document.querySelector('.ProseMirror');
                  if(!pm) return false;
                  if(pm.querySelector('img,audio,video,[data-fileid],.pm-image,.image')) return true;
                  var text=(pm.innerText||'').replace(/[\u200B\u00A0]/g,'').trim();
                  return text.length>0;
                }
                function hidePlaceholderIfNeeded(){
                  document.querySelectorAll('.placeholder--btn').forEach(function(el){el.remove();});
                  var has=docHasContent();
                  document.querySelectorAll('.placeholderContainer,.placeholder').forEach(function(el){
                    if(has){
                      el.style.setProperty('display','none','important');
                      el.style.setProperty('visibility','hidden','important');
                      el.setAttribute('aria-hidden','true');
                    }
                  });
                }
                hidePlaceholderIfNeeded();
                if(!document.getElementById('minote-ui-fix')){
                  var st=document.createElement('style');
                  st.id='minote-ui-fix';
                  st.textContent=[
                    '#fake-root{display:none!important;visibility:hidden!important;pointer-events:none!important;}',
                    '.placeholder--btn{display:none!important;visibility:hidden!important;pointer-events:none!important;}',
                    '.placeholder--btn--text,.placeholder--btn--icon{display:none!important;}',
                    'html.minote-has-content .placeholderContainer{display:none!important;visibility:hidden!important;}',
                    'html.minote-has-content .placeholder{display:none!important;visibility:hidden!important;}',
                    '::selection{background:rgba(255,221,139,0.7)!important;color:rgba(0,0,0,0.87)!important;}',
                    '::-moz-selection{background:rgba(255,221,139,0.7)!important;color:rgba(0,0,0,0.87)!important;}',
                    '.ProseMirror ::selection{background:rgba(255,221,139,0.7)!important;color:rgba(0,0,0,0.87)!important;}',
                    '.ProseMirror *::selection{background:rgba(255,221,139,0.7)!important;color:rgba(0,0,0,0.87)!important;}',
                    '.title-textarea::selection{background:rgba(255,221,139,0.7)!important;color:rgba(0,0,0,0.87)!important;}',
                    '.ProseMirror{caret-color:rgba(0,0,0,0.87)!important;}'
                  ].join('');
                  document.head.appendChild(st);
                }
                if(docHasContent()){document.documentElement.classList.add('minote-has-content');}
                else{document.documentElement.classList.remove('minote-has-content');}
                if(!window.__minotePlaceholderObserver){
                  window.__minotePlaceholderObserver=new MutationObserver(function(){
                    var f=document.getElementById('fake-root'); if(f) f.remove();
                    hidePlaceholderIfNeeded();
                    if(docHasContent()){document.documentElement.classList.add('minote-has-content');}
                    else{document.documentElement.classList.remove('minote-has-content');}
                  });
                  window.__minotePlaceholderObserver.observe(document.documentElement,{childList:true,subtree:true});
                }
              }catch(e){console.log('applyEditorUiFixes',e);}
            })()
            """.trimIndent().replace('\n', ' ')
        )
    }

    private fun eval(js: String) {
        // evaluateJavascript 不要加 javascript: 前缀（那是 loadUrl 的写法）
        webView.evaluateJavascript(js, null)
    }

    private fun interceptMedia(uri: Uri): WebResourceResponse? {
        if (uri.host != "com.miui.notes") return null
        val parts = uri.path.orEmpty().trim('/').split('/')
        if (parts.size < 2) return null
        val kind = parts[0]
        val fileId = parts[1].substringBefore("--")
        if (fileId.isBlank() || (kind != "note_image" && kind != "note_audio")) return null
        val file = NoteImageCache.findCachedFile(context, fileId) ?: return null
        val mime = when {
            kind == "note_audio" -> "audio/mpeg"
            file.name.endsWith(".png", true) -> "image/png"
            file.name.endsWith(".webp", true) -> "image/webp"
            else -> "image/jpeg"
        }
        return WebResourceResponse(mime, null, FileInputStream(file))
    }

    companion object {
        private const val TAG = "OfficialWebEditor"
    }
}
