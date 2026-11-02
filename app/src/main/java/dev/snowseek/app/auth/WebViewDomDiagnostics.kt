package dev.snowseek.app.auth

import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.snowseek.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

/** Observation only: no WebSettings changes, JS bridge, HTTP replay or DOM writes. */
class WebViewDomDiagnostics(
    private val owner: LifecycleOwner,
    private val log: WebViewDebugLog,
) {
    private var view: WebView? = null
    private var source = ""
    private var polling: Job? = null
    private var documentStart = false
    private var evaluating = false

    fun attach(browser: WebView) {
        if (!BuildConfig.DEBUG) return
        view = browser
        source = browser.context.assets.open("webview-debug.js").bufferedReader().use { it.readText() }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            documentStart = true
            WebViewCompat.addDocumentStartJavaScript(browser, source, setOf("https://chat.deepseek.com"))
            log.add(WebDebugTab.WebView, "DOM_DIAGNOSTICS document-start hook registered for official top document")
        } else {
            documentStart = false
            log.add(WebDebugTab.WebView, "DOM_DIAGNOSTICS document-start unsupported; late fallback can miss early errors/rejections")
        }
        polling = owner.lifecycleScope.launch {
            while (true) {
                delay(2_000)
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) collect("poll")
            }
        }
    }

    fun pageStarted(browser: WebView) {
        if (!documentStart && BuildConfig.DEBUG) browser.evaluateJavascript(source, null)
    }

    fun pageReady() = capture("page-ready")

    fun capture(reason: String = "manual") {
        if (BuildConfig.DEBUG) owner.lifecycleScope.launch { collect(reason) }
    }

    private suspend fun collect(reason: String) {
        val browser = view ?: return
        if (evaluating || !official(browser.url)) return
        evaluating = true
        try {
            // Idempotent fallback handles SPA/page-finished and engines without document-start.
            val result = withTimeoutOrNull(3_000) {
                suspendCancellableCoroutine<String> { continuation ->
                    browser.evaluateJavascript(source + "\n;(function(){var d=window.__snowseekDebugV2;if(!d)return null;" +
                        "d.sample(${JsonPrimitive(reason)});return JSON.stringify(d.drain());})();") { raw ->
                        if (continuation.isActive) continuation.resume(raw)
                    }
                }
            }
            if (view !== browser || !official(browser.url)) return
            if (result == null) {
                log.add(WebDebugTab.WebView, "DOM_DIAGNOSTICS evaluation timed out; renderer may be busy")
                return
            }
            if (result.length > 2_000_000) {
                log.add(WebDebugTab.WebView, "DOM_DIAGNOSTICS result exceeded diagnostic limit; discarded")
                return
            }
            val inner = (Json.parseToJsonElement(result) as? JsonPrimitive)?.contentOrNull
            if (inner == null) {
                if (reason != "poll") log.add(WebDebugTab.WebView, "DOM_DIAGNOSTICS hook not available in current document")
                return
            }
            log.location(browser.url)
            Json.parseToJsonElement(inner).jsonArray.take(65).forEach { record(it.jsonObject) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            log.add(WebDebugTab.WebView, "DOM_DIAGNOSTICS evaluation/decoding failed (no raw page result logged)")
        } finally {
            evaluating = false
        }
    }

    private fun record(event: JsonObject) {
        val kind = event["kind"]?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
        val tab = when (kind) {
            "WINDOW_ERROR", "UNHANDLED_REJECTION", "REJECTION_HANDLED" -> WebDebugTab.Console
            "RESOURCE_ERROR" -> WebDebugTab.Network
            else -> WebDebugTab.WebView
        }
        val text = event["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val at = event["at"]?.jsonPrimitive?.longOrNull
        log.detailed(tab, "$kind · pageTime=$at\n$text")
        val data = event["data"] as? JsonObject ?: return
        // Emit each measurement separately to retain large snapshots without per-message truncation.
        data.forEach { (key, value) ->
            if (value is JsonArray) {
                if (value.isEmpty()) log.add(tab, "$kind $key: []")
                value.forEachIndexed { index, item -> log.detailed(tab, "$kind $key[$index]:\n$item") }
            } else log.detailed(tab, "$kind $key:\n$value")
        }
    }

    fun close() {
        polling?.cancel()
        polling = null
        view = null
    }

    private fun official(url: String?): Boolean = url != null &&
        WebNavigationPolicy.classify(url) == NavigationDecision.INTERNAL &&
        url.startsWith("https://chat.deepseek.com/")
}
