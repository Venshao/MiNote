package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.data.MiCloudCookieHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 用 WebView 打开小米云，登录后从 CookieManager 取出便签 API 所需 Cookie。
 * 检测到本地 Cookie 后先后台校验有效性，确认可用再弹窗。
 */
class MiCloudLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var hint: TextView
    private lateinit var btnUseCookie: MaterialButton

    private var finished = false
    private var confirmDialogShowing = false
    private var validating = false
    private var validateJob: Job? = null
    private var lastProbedCookie: String? = null
    private var lastProbeOk: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mi_cloud_login)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        progress = findViewById(R.id.progress)
        hint = findViewById(R.id.hint)
        webView = findViewById(R.id.webview)
        btnUseCookie = findViewById(R.id.btn_use_cookie)
        val btnClear = findViewById<MaterialButton>(R.id.btn_clear)

        toolbar.setNavigationOnClickListener { finishCanceled() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) webView.goBack() else finishCanceled()
                }
            }
        )

        btnClear.setOnClickListener {
            validateJob?.cancel()
            validating = false
            lastProbedCookie = null
            lastProbeOk = false
            MiCloudCookieHelper.clearMiCloudCookies()
            btnUseCookie.isEnabled = false
            confirmDialogShowing = false
            setHintNormal()
            webView.loadUrl(MiCloudCookieHelper.LOGIN_ENTRY_URL)
            Toast.makeText(this, "已清除登录状态", Toast.LENGTH_SHORT).show()
        }
        btnUseCookie.setOnClickListener { requestValidatedFinish(force = true) }

        setupWebView()
        webView.loadUrl(MiCloudCookieHelper.NOTE_H5_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (validating) {
                    progress.visibility = View.VISIBLE
                    return
                }
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                refreshCookieButton()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                refreshCookieButton()
                if (MiCloudCookieHelper.looksLoggedInOnUrl(url)) {
                    // 稍等 WebView / SSO 把 Cookie 刷成最新，再探测
                    requestValidatedFinish(force = false, settleMs = 800L)
                }
            }
        }
    }

    private fun refreshCookieButton() {
        val hasShape = MiCloudCookieHelper.cookieForMiCloud() != null
        btnUseCookie.isEnabled = hasShape && !validating
    }

    private fun setHintNormal() {
        hint.text = "在页面中登录小米账号；确认登录有效后会提示开始同步。"
    }

    private fun setHintValidating() {
        hint.text = "正在确认登录是否有效…"
    }

    private fun setHintExpired() {
        hint.text = "登录已过期，请在页面中重新登录。"
    }

    /**
     * @param force 用户点了「开始同步」：校验失败时要 Toast
     * @param settleMs 等 Cookie 刷新的短暂延迟
     */
    private fun requestValidatedFinish(force: Boolean, settleMs: Long = 0L) {
        if (finished || confirmDialogShowing) return

        validateJob?.cancel()
        validateJob = lifecycleScope.launch {
            if (settleMs > 0) delay(settleMs)
            if (finished || confirmDialogShowing) return@launch

            val rawCookie = MiCloudCookieHelper.cookieForMiCloud()
            if (rawCookie.isNullOrBlank()) {
                validating = false
                progress.visibility = View.GONE
                refreshCookieButton()
                if (force) {
                    Toast.makeText(
                        this@MiCloudLoginActivity,
                        "尚未检测到有效登录，请完成登录后再试",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            val cookie: String = rawCookie

            // 同一串 Cookie 刚探测过且有效：直接弹窗
            if (cookie == lastProbedCookie && lastProbeOk) {
                showSuccessDialog(cookie)
                return@launch
            }
            // 同一串刚探测过且无效：非强制不再重复打接口
            if (!force && cookie == lastProbedCookie && !lastProbeOk) {
                return@launch
            }

            validating = true
            setHintValidating()
            progress.visibility = View.VISIBLE
            btnUseCookie.isEnabled = false

            // 过期 Cookie 进页后 WebView 可能稍后刷成新值：自动路径多探几次
            var working: String = cookie
            var ok = false
            val attempts = if (force) 1 else 3
            for (i in 0 until attempts) {
                if (finished) return@launch
                if (i > 0) {
                    delay(1200L)
                    working = MiCloudCookieHelper.cookieForMiCloud() ?: working
                }
                ok = MiCloudCookieHelper.probeCookieValid(working)
                if (ok) break
            }
            if (finished) return@launch

            lastProbedCookie = working
            lastProbeOk = ok
            validating = false
            progress.visibility = View.GONE
            refreshCookieButton()

            if (ok) {
                showSuccessDialog(working)
            } else {
                setHintExpired()
                if (force) {
                    Toast.makeText(
                        this@MiCloudLoginActivity,
                        "登录无效或已过期，请重新登录后再试",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showSuccessDialog(cookie: String) {
        if (finished || confirmDialogShowing) return
        confirmDialogShowing = true
        setHintNormal()
        AlertDialog.Builder(this)
            .setTitle("登录成功")
            .setMessage("登录有效，关闭后将返回并开始同步便签。")
            .setPositiveButton("开始同步") { _, _ ->
                // 再取一次，避免弹窗期间 Cookie 又被 WebView 刷新
                val latest = MiCloudCookieHelper.cookieForMiCloud() ?: cookie
                finishWithCookie(latest)
            }
            .setNegativeButton("稍后再说") { _, _ ->
                confirmDialogShowing = false
            }
            .setOnCancelListener {
                confirmDialogShowing = false
            }
            .show()
    }

    private fun finishWithCookie(cookie: String) {
        if (finished) return
        finished = true
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_COOKIE, cookie)
        )
        finish()
    }

    private fun finishCanceled() {
        if (finished) return
        finished = true
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        validateJob?.cancel()
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_COOKIE = "mi_cloud_cookie"

        fun createIntent(context: Context): Intent =
            Intent(context, MiCloudLoginActivity::class.java)
    }
}
