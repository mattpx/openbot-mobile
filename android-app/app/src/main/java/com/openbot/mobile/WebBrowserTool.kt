package com.openbot.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

data class WebPageResult(
    val url: String,
    val title: String,
    val text: String,
    val screenshotPath: String? = null
)

/**
 * Lightweight web fetch and Android WebView-backed browser tool with DOM text
 * extraction and optional screenshot capture into `/workspace/screenshots/`.
 */
object WebBrowserTool {

    private const val MAX_FETCH_CHARS = 256 * 1024
    private const val MAX_TEXT_CHARS = 16_000
    private const val RENDER_WIDTH = 1080
    private const val RENDER_HEIGHT = 1920

    suspend fun fetchText(url: String): WebPageResult = withContext(Dispatchers.IO) {
        if (!LlmConfig.isHttpUrl(url)) {
            throw IllegalArgumentException("URL must start with http:// or https://")
        }
        val conn = (URL(url.trim()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "OpenBotMobile/1.0 (Android)")
            setRequestProperty("Accept", "text/html,application/json,text/plain,*/*")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.let { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    val sb = StringBuilder()
                    val buf = CharArray(4096)
                    while (sb.length < MAX_FETCH_CHARS) {
                        val n = reader.read(buf)
                        if (n <= 0) break
                        sb.append(buf, 0, minOf(n, MAX_FETCH_CHARS - sb.length))
                    }
                    sb.toString()
                }
            } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${raw.take(400)}")
            }
            val contentType = conn.contentType ?: ""
            if (contentType.contains("html", ignoreCase = true) || raw.trimStart().startsWith("<")) {
                val title = TITLE_REGEX.find(raw)?.groupValues?.get(1)?.trim().orEmpty()
                val text = stripHtml(raw).take(MAX_TEXT_CHARS)
                WebPageResult(url = conn.url.toString(), title = title, text = text)
            } else {
                WebPageResult(url = conn.url.toString(), title = "", text = raw.take(MAX_TEXT_CHARS))
            }
        } finally {
            conn.disconnect()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun browseWithWebView(
        context: Context,
        url: String,
        captureScreenshot: Boolean,
        workspaceDir: File
    ): WebPageResult {
        if (!LlmConfig.isHttpUrl(url)) {
            throw IllegalArgumentException("URL must start with http:// or https://")
        }
        val result = withTimeoutOrNull(25_000L) {
            withContext(Dispatchers.Main) {
                renderAndExtract(context, url.trim(), captureScreenshot, workspaceDir)
            }
        }
        return result ?: fetchText(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderAndExtract(
        context: Context,
        url: String,
        captureScreenshot: Boolean,
        workspaceDir: File
    ): WebPageResult? {
        val webView = try {
            WebView(context.applicationContext)
        } catch (_: Throwable) {
            return null
        }

        return try {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                loadsImagesAutomatically = captureScreenshot
            }
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(RENDER_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(RENDER_HEIGHT, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, RENDER_WIDTH, RENDER_HEIGHT)

            val loadedUrl = suspendCancellableCoroutine<String?> { cont ->
                var resumed = false
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        if (!resumed && cont.isActive) {
                            resumed = true
                            cont.resume(finishedUrl ?: url)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true && !resumed && cont.isActive) {
                            resumed = true
                            cont.resume(null)
                        }
                    }
                }
                webView.loadUrl(url)
            } ?: return null

            val extractedJson = suspendCancellableCoroutine<String?> { cont ->
                val js = "(function(){return JSON.stringify({" +
                    "title: document.title || '', " +
                    "text: (document.body && document.body.innerText) ? document.body.innerText.slice(0, $MAX_TEXT_CHARS) : ''" +
                    "});})();"
                webView.evaluateJavascript(js) { value ->
                    if (cont.isActive) cont.resume(value)
                }
            }

            var title = ""
            var text = ""
            if (!extractedJson.isNullOrBlank() && extractedJson != "null") {
                runCatching {
                    // evaluateJavascript returns a JSON-encoded string literal.
                    val decoded = JSONObject("{\"v\":$extractedJson}").getString("v")
                    val obj = JSONObject(decoded)
                    title = obj.optString("title", "").trim()
                    text = obj.optString("text", "").trim()
                }
            }

            var screenshotPath: String? = null
            if (captureScreenshot) {
                val bitmap = Bitmap.createBitmap(RENDER_WIDTH, RENDER_HEIGHT, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                webView.draw(canvas)
                screenshotPath = withContext(Dispatchers.IO) {
                    val dir = File(workspaceDir, "screenshots").apply { mkdirs() }
                    val file = File(dir, "page-${System.currentTimeMillis()}.png")
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                    bitmap.recycle()
                    "/workspace/screenshots/${file.name}"
                }
            }

            WebPageResult(
                url = loadedUrl,
                title = title,
                text = text.ifBlank { "(Page rendered with no visible text)" },
                screenshotPath = screenshotPath
            )
        } finally {
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(SCRIPT_STYLE_REGEX, " ")
            .replace(TAG_REGEX, " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private val TITLE_REGEX = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
    private val SCRIPT_STYLE_REGEX =
        Regex("<(script|style|noscript)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
    private val TAG_REGEX = Regex("<[^>]+>")
    private val WHITESPACE_REGEX = Regex("\\s+")
}
