package dev.snowseek.app.auth

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.net.toUri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.io.IOException
import dev.snowseek.app.settings.AppSettings
import dev.snowseek.app.settings.SettingsStore
import dev.snowseek.app.ui.theme.SnowSeekTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/** Official website only; its sign-in never enables the native demo provider. */
class OfficialWebActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var savedWebState: Bundle? = null
    private var savedWebRevision = -1L
    private var loadedRevision = -1L
    private var settingsError by mutableStateOf<String?>(null)
    private var progress by mutableFloatStateOf(0f)
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var externalUrl by mutableStateOf<String?>(null)
    private var confirmLogout by mutableStateOf(false)
    private var clearing by mutableStateOf(false)
    private var sessionCleared by mutableStateOf(false)
    private val russian get() = intent.getStringExtra("language") != "en"
    private fun text(ru: String, en: String) = if (russian) ru else en

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(false)
        savedWebState = savedInstanceState?.getBundle(STATE_WEB)
        savedWebRevision = savedInstanceState?.getLong(STATE_REVISION, -1L) ?: -1L
        setContent {
            val settings by remember {
                SettingsStore(applicationContext).settings
                    .onEach { settingsError = null }
                    .retryWhen { cause, _ ->
                        if (cause is IOException) {
                            settingsError = text("Не удалось прочитать настройки. Повторяем попытку…", "Settings could not be read. Retrying…")
                            delay(1_000)
                            true
                        } else false
                    }
            }.collectAsState(initial = AppSettings())
            SnowSeekTheme(settings) {
                OfficialScreen()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        // JavaScript and DOM storage are required by the official website, with no native bridge.
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val decision = WebNavigationPolicy.classify(request.url.toString())
                // CAPTCHA may embed a third-party HTTPS frame; only top-level pages leave the app.
                if (WebNavigationPolicy.mayLoadInWebView(request.url.toString(), request.isForMainFrame)) return false
                if (request.isForMainFrame) {
                    if (decision == NavigationDecision.EXTERNAL_BROWSER) externalUrl = request.url.toString()
                    else error = text("Небезопасный переход заблокирован.", "Unsafe navigation was blocked.")
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Keep normal valid HTTPS CDN/CAPTCHA resources; block local and cleartext resources.
                return if (WebNavigationPolicy.classify(request.url.toString()) != NavigationDecision.BLOCKED) null
                else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null && WebNavigationPolicy.classify(url) != NavigationDecision.INTERNAL) {
                    view.stopLoading()
                    if (url != "about:blank") error = text("Переход заблокирован.", "Navigation was blocked.")
                    return
                }
                error = null
                loading = true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loading = false
                // Persist WebView's own cookies without exposing their values to the app.
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                if (request.isForMainFrame) showLoadError()
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) showLoadError()
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, failure: SslError) {
                handler.cancel()
                showLoadError()
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                this@OfficialWebActivity.progress = newProgress / 100f
            }
        }
        webView = this
        startPage(this, savedWebState)
    }

    private fun startPage(view: WebView, stateToRestore: Bundle? = null, url: String = OFFICIAL_URL) {
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val revision = WebSession.awaitReady()
                if (webView !== view || sessionCleared) return@launch
                // These operations run consecutively on Main: a new logout cannot interleave.
                WebSession.attach(view)
                loadedRevision = revision
                error = null
                loading = true
                val restore = stateToRestore?.takeIf { savedWebRevision == revision }
                savedWebState = null
                // WebView restores navigation history, not a guaranteed form/DOM/CAPTCHA snapshot.
                if (restore == null || view.restoreState(restore) == null) view.loadUrl(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                loading = false
                error = text("Веб-сессия не очищена полностью. Повторите выход в настройках перед новым входом.", "Session cleanup is incomplete. Retry signing out in settings before signing in again.")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (!sessionCleared && loadedRevision >= 0L) {
            webView?.let { view ->
                val state = Bundle()
                if (view.saveState(state) != null) {
                    outState.putBundle(STATE_WEB, state)
                    outState.putLong(STATE_REVISION, loadedRevision)
                }
            }
        }
        super.onSaveInstanceState(outState)
    }
    private fun showLoadError() {
        loading = false
        error = text(
            "Страница не загрузилась. Проверьте сеть и повторите попытку. Для входа также можно открыть браузер.",
            "The page could not load. Check your connection and retry, or use your browser to sign in.",
        )
    }

    private fun navigateBack() {
        val view = webView
        if (view != null && view.canGoBack() && !sessionCleared) view.goBack() else finish()
    }

    private fun openBrowser(url: String) {
        if (WebNavigationPolicy.classify(url) == NavigationDecision.BLOCKED) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            error = text("Браузер не найден.", "No browser is available.")
        } catch (_: SecurityException) {
            error = text("Не удалось открыть браузер.", "The browser could not be opened.")
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun OfficialScreen() {
        val scope = rememberCoroutineScope()
        BackHandler(enabled = !clearing) { navigateBack() }
        BackHandler(enabled = clearing) { /* Wait for session cleanup before leaving. */ }
        Scaffold(topBar = {
            TopAppBar(
                title = { Text("DeepSeek") },
                navigationIcon = {
                    IconButton(onClick = { navigateBack() }, enabled = !clearing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, text("Назад", "Back"))
                    }
                },
                actions = {
                    IconButton(onClick = { externalUrl = webView?.url?.takeIf {
                        WebNavigationPolicy.classify(it) == NavigationDecision.INTERNAL
                    } ?: OFFICIAL_URL }, enabled = !clearing) {
                        Icon(Icons.Default.OpenInBrowser, text("Открыть в браузере", "Open in browser"))
                    }
                },
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                settingsError?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                Text(
                    text(
                        "Официальный сайт. Вход здесь не подключает нативный демо-чат. CAPTCHA и некоторые способы входа могут потребовать браузер.",
                        "Official website. Signing in here does not connect the native demo chat. CAPTCHA and some sign-in methods may require a browser.",
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { confirmLogout = true }, enabled = !clearing && !sessionCleared) {
                        Text(if (clearing) text("Очистка…", "Clearing…") else text("Выйти с сайта", "Sign out of website"))
                    }
                }
                if (clearing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else if (loading) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                val currentError = error
                if (currentError != null) {
                    Column(Modifier.padding(16.dp)) {
                        Text(currentError, color = MaterialTheme.colorScheme.error)
                        if (!sessionCleared) {
                            Button(onClick = {
                                error = null
                                loading = true
                                val retryUrl = webView?.url?.takeIf {
                                    WebNavigationPolicy.classify(it) == NavigationDecision.INTERNAL
                                } ?: OFFICIAL_URL
                                webView?.let { startPage(it, url = retryUrl) }
                            }, enabled = !clearing) { Text(text("Повторить", "Retry")) }
                        } else {
                            OutlinedButton(onClick = { finish() }) { Text(text("Закрыть", "Close")) }
                        }
                    }
                }
                if (!sessionCleared) {
                    AndroidView(
                        factory = { createWebView() },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onRelease = { releaseWebView(it) },
                    )
                }
            }
        }
        externalUrl?.let { target ->
            AlertDialog(
                onDismissRequest = { externalUrl = null },
                title = { Text(text("Открыть браузер?", "Open browser?")) },
                text = { Text(text(
                    "Сайт ${target.toUri().host}. Сессия браузера отдельная: вход в браузере не переносится обратно в приложение.",
                    "Website: ${target.toUri().host}. Browser sessions are separate: signing in there does not sign you in inside this app.",
                )) },
                confirmButton = { TextButton(onClick = { externalUrl = null; openBrowser(target) }) { Text(text("Открыть", "Open")) } },
                dismissButton = { TextButton(onClick = { externalUrl = null }) { Text(text("Отмена", "Cancel")) } },
            )
        }
        if (confirmLogout) {
            AlertDialog(
                onDismissRequest = { confirmLogout = false },
                title = { Text(text("Выйти с сайта?", "Sign out of website?")) },
                text = { Text(text("Cookies, хранилище и кэш сайта будут очищены на устройстве. Сессия внешнего браузера сохранится.", "Website cookies, storage and cache will be cleared on this device. External browser sessions are separate.")) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmLogout = false
                        clearing = true
                        sessionCleared = true
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                WebSession.clear(this@OfficialWebActivity)
                                finish()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = text("Не удалось полностью очистить веб-сессию. Повторите выход из настроек приложения.", "The web session could not be fully cleared. Retry signing out from app settings.")
                            } finally {
                                clearing = false
                            }
                        }
                    }) { Text(text("Выйти", "Sign out")) }
                },
                dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(text("Отмена", "Cancel")) } },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    private fun releaseWebView(view: WebView) {
        if (webView !== view) return
        WebSession.detach(view)
        webView = null
        view.stopLoading()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.destroy()
    }

    override fun onDestroy() {
        webView?.let { releaseWebView(it) }
        super.onDestroy()
    }
    companion object {
        const val OFFICIAL_URL = "https://chat.deepseek.com/"
        private const val STATE_WEB = "official_web_state"
        private const val STATE_REVISION = "official_web_revision"
    }
}
