package com.vanipy.bidik

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * Captures pages in an embedded WebView at the device's real screen metrics.
 *
 * Layout settings approximate Chrome for Android's behavior (per Chromium
 * web-page-layout docs): wide viewport + overview mode + TEXT_AUTOSIZING.
 * Same Blink engine, same DPR/font scale as the device - but NOT Chrome:
 * no Chrome cookies/logins, no Sync/Data Saver.
 *
 * No JavascriptInterface bridges are ever added: page JS gets no access
 * to app code. Fidelity is validated by instrumented pixel tests
 * (capture-check workflow) and finally on a real device, not assumed
 * from settings alone.
 */
class WebViewCaptureEngine(private val activity: Activity) {

    data class CaptureOutput(
        val urlFinal: String,
        val pageTitle: String,
        val viewportPng: File,
        val fullPng: File?,
        val loadMs: Long,
        val engineVersion: String
    )

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun capture(
        job: QueueProtocol.PageJob,
        outDir: File
    ): CaptureOutput = withContext(Dispatchers.Main) {
        // Literal-IP guard already ran at queue parse; now resolve the host
        // off the UI thread and refuse private resolved addresses. A DNS
        // change between this check and the actual connect (rebinding) is
        // not defended in v1 - see SECURITY.md known limits.
        assertPublicTarget(job.url)
        val metrics = activity.resources.displayMetrics
        val widthPx = metrics.widthPixels
        val heightPx = metrics.heightPixels

        val webView = WebView(activity)
        try {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                @Suppress("DEPRECATION")
                layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                mediaPlaybackRequiresUserGesture = true
            }
            // Software layer so view.draw(canvas) renders WebView content
            // into our bitmap without needing a window for PixelCopy.
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            webView.layout(0, 0, widthPx, heightPx)
            // Attach to the activity window: an unattached WebView counts as
            // hidden and Chromium can defer the load indefinitely (CI runs
            // 43685608708/43688783344: fixture server accepted and served,
            // renderer alive, yet no page_started/page_error before the 30s
            // timeout). Attached and laid out at full size it loads
            // normally; it still renders into our bitmap offscreen.
            activity.addContentView(
                webView,
                android.view.ViewGroup.LayoutParams(widthPx, heightPx)
            )

            val loaded = CompletableDeferred<Boolean>()
            var finalUrl = job.url
            var pageTitle = ""
            val loadStart = System.currentTimeMillis()
            webView.webViewClient = object : WebViewClient() {
                // The queue was validated at parse time, but redirects can
                // retarget the load: re-check every navigation against the
                // same public-http(s) guard.
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest
                ): Boolean = !UrlGuard.isAllowed(request.url.toString())

                override fun onPageStarted(
                    view: WebView, url: String, favicon: android.graphics.Bitmap?
                ) {
                    DiagLog.log(activity, "capture", "page_started")
                }

                override fun onPageFinished(view: WebView, url: String) {
                    DiagLog.log(activity, "capture", "page_finished")
                    finalUrl = url
                    pageTitle = view.title ?: ""
                    if (!loaded.isCompleted) loaded.complete(true)
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (request.isForMainFrame && !loaded.isCompleted) {
                        // Bounded platform error code only; never the URL or
                        // the platform's free-form error text.
                        DiagLog.log(activity, "capture", "page_error", detail = "code=${error.errorCode}")
                        loaded.complete(false)
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView, detail: android.webkit.RenderProcessGoneDetail
                ): Boolean {
                    // Diagnostic only: record renderer death (crash vs system
                    // kill), then keep the platform's default handling.
                    DiagLog.log(activity, "capture", "render_gone", detail = "crashed=${detail.didCrash()}")
                    return false
                }
            }
            webView.webChromeClient = WebChromeClient()
            webView.loadUrl(job.url)

            val ok = withTimeoutOrNull(30_000) { loaded.await() } ?: false
            val loadMs = System.currentTimeMillis() - loadStart
            if (!ok) {
                DiagLog.log(activity, "capture", "page_timeout")
                throw CaptureException("page load failed or timed out: ${job.url}")
            }

            // Settle: wait for readyState complete + images decoded, then the
            // per-page wait budget (dynamic pages keep mutating after load).
            awaitReady(webView, job.waitMs)

            val viewportPng = File(outDir, "${job.id}-viewport.png")
            drawToFile(webView, widthPx, heightPx, viewportPng)

            var fullPng: File? = null
            if (job.fullPage) {
                val contentPx = (webView.contentHeight * webView.scale).toInt()
                if (contentPx in (heightPx + 1)..MAX_FULLPAGE_PX) {
                    webView.layout(0, 0, widthPx, contentPx)
                    fullPng = File(outDir, "${job.id}-full.png")
                    drawToFile(webView, widthPx, contentPx, fullPng)
                }
            }

            CaptureOutput(
                urlFinal = finalUrl,
                pageTitle = pageTitle,
                viewportPng = viewportPng,
                fullPng = fullPng,
                loadMs = loadMs,
                engineVersion = WebView.getCurrentWebViewPackage()?.versionName ?: "unknown"
            )
        } finally {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    private suspend fun assertPublicTarget(url: String) {
        withContext(Dispatchers.IO) {
            val host = try {
                URI(url).host
            } catch (e: Exception) {
                null
            } ?: throw CaptureException("no host: $url")
            // Test-only fixture path: loopback never leaves the device,
            // so resolution and public-address checks are skipped. The
            // flag is honored only in debug builds and set only by
            // instrumented tests.
            if (UrlGuard.loopbackUnlocked() && UrlGuard.isLoopbackHost(host.lowercase())) {
                return@withContext
            }
            val addrs = try {
                InetAddress.getAllByName(host)
            } catch (e: UnknownHostException) {
                throw CaptureException("host does not resolve: $host")
            }
            for (a in addrs) {
                if (a.isLoopbackAddress || a.isSiteLocalAddress ||
                    a.isLinkLocalAddress || a.isAnyLocalAddress ||
                    a.isMulticastAddress
                ) {
                    throw CaptureException("host resolves to a private address: $host")
                }
                val b = a.address
                // CGNAT 100.64.0.0/10 and IPv6 ULA fc00::/7 are not covered
                // by the InetAddress convenience predicates.
                if (b.size == 4 && (b[0].toInt() and 0xFF) == 100 &&
                    (b[1].toInt() and 0xFF) in 64..127
                ) {
                    throw CaptureException("host resolves to a CGNAT address: $host")
                }
                if (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC) {
                    throw CaptureException("host resolves to a ULA address: $host")
                }
            }
        }
    }

    /**
     * Bounded settle: poll every 500ms (max 10s) until the document is
     * complete, all images decoded and fonts loaded, then wait the
     * per-page budget. Dynamic canvas pages keep mutating after this
     * point; the wait_ms budget defines the capture moment for them, it
     * does not prove the page stopped changing.
     */
    private suspend fun awaitReady(webView: WebView, extraWaitMs: Long) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val probe = CompletableDeferred<Boolean>()
            webView.evaluateJavascript(
                """
                (function() {
                  if (document.readyState !== 'complete') return 'no';
                  var imgs = Array.from(document.images || []);
                  if (!imgs.every(function(i){ return i.complete; })) return 'no';
                  if (document.fonts && document.fonts.status !== 'loaded') return 'no';
                  return 'yes';
                })()
                """.trimIndent()
            ) { v -> probe.complete(v?.contains("yes") == true) }
            val ok = withTimeoutOrNull(2_000) { probe.await() } ?: false
            if (ok) break
            kotlinx.coroutines.delay(500)
        }
        if (extraWaitMs > 0) kotlinx.coroutines.delay(extraWaitMs)
    }

    private fun drawToFile(view: View, w: Int, h: Int, out: File) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        view.draw(canvas)
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    class CaptureException(message: String) : Exception(message)

    companion object {
        // Guard against unbounded content heights on infinite-scroll pages.
        const val MAX_FULLPAGE_PX = 16_000
    }
}
