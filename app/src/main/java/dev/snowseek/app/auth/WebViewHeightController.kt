package dev.snowseek.app.auth

import android.graphics.Rect
import android.view.View
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Check native measurement first; only then recover collapsed official login roots. */
class WebViewHeightController(
    private val owner: LifecycleOwner,
    private val log: WebViewDebugLog,
    private val onCorrection: () -> Unit,
) {
    private var view: WebView? = null
    private var source = ""
    private var polling: Job? = null
    private var evaluating = false
    private var evaluationId = 0L
    private var evaluationTimeout: Job? = null
    private var rootWidth = 0
    private var rootHeight = 0
    private var hostWidth = 0
    private var hostHeight = 0
    private var lastNative = ""
    private var lastResult = ""
    private val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> check("android-layout") }

    fun composeRoot(width: Int, height: Int) {
        rootWidth = width; rootHeight = height
        check("compose-root")
    }

    fun composeHost(width: Int, height: Int) {
        hostWidth = width; hostHeight = height
        check("compose-host")
    }

    fun attach(browser: WebView) {
        view = browser
        source = browser.context.assets.open("webview-height-fix.js").bufferedReader().use { it.readText() }
        browser.addOnLayoutChangeListener(listener)
        polling = owner.lifecycleScope.launch {
            while (true) {
                delay(2_000)
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) check("poll")
            }
        }
    }

    fun pageStarted() {
        lastResult = ""
        evaluationId++
        evaluating = false
        evaluationTimeout?.cancel()
    }

    fun check(reason: String = "page-ready") {
        val browser = view ?: return
        val parentMeasurements = mutableListOf<JsonObject>()
        var parent = browser.parent
        repeat(8) {
            val p = parent as? View ?: return@repeat
            parentMeasurements += buildJsonObject {
                put("type", p.javaClass.simpleName)
                put("width", p.width); put("height", p.height)
                put("measuredWidth", p.measuredWidth); put("measuredHeight", p.measuredHeight)
                put("layoutWidth", p.layoutParams?.width ?: 0); put("layoutHeight", p.layoutParams?.height ?: 0)
            }
            parent = p.parent
        }
        val visible = Rect()
        val hasVisibleArea = browser.getGlobalVisibleRect(visible) && visible.width() > 0 && visible.height() > 0
        val parentsReady = parentMeasurements.isNotEmpty() && parentMeasurements.all {
            it.getValue("width").jsonPrimitive.int > 0 && it.getValue("height").jsonPrimitive.int > 0
        }
        val ready = browser.isAttachedToWindow && hasVisibleArea && parentsReady &&
            browser.width > 0 && browser.height > 0 && browser.measuredWidth > 0 && browser.measuredHeight > 0 &&
            rootWidth > 0 && rootHeight > 0 && hostWidth > 0 && hostHeight > 0
        val native = buildJsonObject {
            put("ready", ready)
            put("composeRootWidth", rootWidth); put("composeRootHeight", rootHeight)
            put("hostWidth", hostWidth); put("hostHeight", hostHeight)
            put("webWidth", browser.width); put("webHeight", browser.height)
            put("measuredWidth", browser.measuredWidth); put("measuredHeight", browser.measuredHeight)
            put("layoutWidth", browser.layoutParams?.width ?: 0); put("layoutHeight", browser.layoutParams?.height ?: 0)
            put("visibleWidth", visible.width()); put("visibleHeight", visible.height())
            put("parents", JsonArray(parentMeasurements))
        }
        val nativeText = native.toString()
        if (nativeText != lastNative || reason == "manual") {
            log.detailed(WebDebugTab.WebView, "NATIVE_LAYOUT $reason · pixels (not CSS px)\n$nativeText")
            lastNative = nativeText
        }
        val url = browser.url ?: return
        if (!ready || evaluating || WebNavigationPolicy.classify(url) != NavigationDecision.INTERNAL ||
            !url.startsWith("https://chat.deepseek.com/")) return
        evaluating = true
        val id = ++evaluationId
        evaluationTimeout = owner.lifecycleScope.launch {
            delay(3_000)
            if (view === browser && evaluationId == id && evaluating) {
                evaluating = false
                evaluationId++
                log.add(WebDebugTab.WebView, "HEIGHT_CORRECTION evaluation timed out; no result assumed")
            }
        }
        browser.evaluateJavascript(source + "\n;JSON.stringify(window.__snowseekRepairHeight($native));") { raw ->
            if (view !== browser || evaluationId != id) return@evaluateJavascript
            evaluationTimeout?.cancel()
            evaluating = false
            if (browser.url != url) return@evaluateJavascript
            val result = try {
                val text = Json.parseToJsonElement(raw).jsonPrimitive.content
                Json.parseToJsonElement(text).jsonObject
            } catch (_: Exception) { null }
            val resultText = result?.toString() ?: "unavailable"
            if (resultText != lastResult) {
                log.detailed(WebDebugTab.WebView, "HEIGHT_CORRECTION $reason\n$resultText")
                lastResult = resultText
            }
            if (result?.get("status")?.jsonPrimitive?.content in listOf("applied", "rolled-back", "restored-after-login")) onCorrection()
        }
    }

    fun close() {
        polling?.cancel(); polling = null
        evaluationTimeout?.cancel()
        evaluationId++
        view?.removeOnLayoutChangeListener(listener)
        view = null
    }
}
