package dev.snowseek.app.auth

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Clears both the native encrypted token and WebView's own website session. */
object WebSession {
    private val cleanup = SessionCleanupGate(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    private val activeViews = Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())

    internal fun attach(view: WebView) { activeViews.add(view) }
    internal fun detach(view: WebView) { activeViews.remove(view) }
    internal suspend fun awaitReady(): Long = cleanup.awaitReady()

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun clear(context: Context) {
        val appContext = context.applicationContext
        // Register cleanup on Main, the same dispatcher used by attach and page loading.
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            cleanup.clear {
                GoogleBrowserAttempt.cancel(appContext)
                withContext(Dispatchers.IO) { DeepSeekCredentials.get(appContext).clear() }
                // Prevent live pages from recreating credentials during cookie removal.
                activeViews.toList().forEach { view ->
                    view.stopLoading()
                    view.settings.javaScriptEnabled = false
                    view.settings.blockNetworkLoads = true
                    view.loadUrl("about:blank")
                    view.clearHistory()
                    view.clearFormData()
                    view.clearSslPreferences()
                }
                WebView.setWebContentsDebuggingEnabled(false)
                val temporaryView = WebView(appContext)
                try {
                    temporaryView.clearCache(true)
                    temporaryView.clearHistory()
                    temporaryView.clearFormData()
                    WebStorage.getInstance().deleteAllData()
                    WebViewDatabase.getInstance(appContext).apply {
                        clearHttpAuthUsernamePassword()
                        clearFormData()
                    }
                    val cookies = CookieManager.getInstance()
                    suspendCancellableCoroutine { continuation ->
                        cookies.removeAllCookies {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                    // Persist deletion before the gate releases a newly created WebView.
                    withContext(Dispatchers.IO) { cookies.flush() }
                } finally {
                    temporaryView.destroy()
                }
            }
        }
    }
}
