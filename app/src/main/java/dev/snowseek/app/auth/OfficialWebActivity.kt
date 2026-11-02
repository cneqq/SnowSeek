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
import android.webkit.ConsoleMessage
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.BugReport
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import kotlin.coroutines.resume
import dev.snowseek.app.data.DeepSeekHttpTransport
import dev.snowseek.core.DeepSeekFailure
import dev.snowseek.app.BuildConfig

/** User signs in on the official website and explicitly connects their session to SnowSeek. */
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
    private var connecting by mutableStateOf(false)
    private var sessionCleared by mutableStateOf(false)
    private var blankCheck: Job? = null
    private var scriptErrors = 0
    private var resourceErrors = 0
    private val diagnostics = WebViewDebugLog(enabled = BuildConfig.DEBUG)
    private val domDiagnostics = WebViewDomDiagnostics(this, diagnostics)
    private var showDiagnostics by mutableStateOf(false)
    private val russian get() = intent.getStringExtra("language") != "en"
    private fun text(ru: String, en: String) = if (russian) ru else en

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(false)
        val engine = WebView.getCurrentWebViewPackage()
        diagnostics.environment("SnowSeek ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) debug=${BuildConfig.DEBUG}\n" +
            "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n" +
            "WebView ${engine?.packageName ?: "unknown"} ${engine?.versionName ?: "unknown"}")
        diagnostics.add(WebDebugTab.WebView, "ACTIVITY_CREATED · remote debugging disabled · no JavaScript bridge")
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
            useWideViewPort = true
            loadWithOverviewMode = true
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
                val allowed = WebNavigationPolicy.mayLoadInWebView(request.url.toString(), request.isForMainFrame)
                diagnostics.add(WebDebugTab.WebView, "NAVIGATION ${request.method} ${DebugRedactor.url(request.url.toString())} · " +
                    "main=${request.isForMainFrame} redirect=${request.isRedirect} gesture=${request.hasGesture()} · " +
                    if (allowed) "ALLOW" else "INTERCEPT $decision")
                // CAPTCHA may embed a third-party HTTPS frame; only top-level pages leave the app.
                if (allowed) return false
                if (request.isForMainFrame) {
                    if (decision == NavigationDecision.EXTERNAL_BROWSER) externalUrl = request.url.toString()
                    else error = text("Небезопасный переход заблокирован.", "Unsafe navigation was blocked.")
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Keep normal valid HTTPS CDN/CAPTCHA resources; block local and cleartext resources.
                val allowed = WebNavigationPolicy.mayLoadResource(request.url.toString())
                diagnostics.request(request.method, request.url.toString(), request.isForMainFrame, allowed)
                return if (allowed) null
                else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                domDiagnostics.pageStarted(view)
                diagnostics.location(url, 0)
                diagnostics.add(WebDebugTab.WebView, "PAGE_STARTED ${DebugRedactor.url(url)}")
                if (url != null && WebNavigationPolicy.classify(url) != NavigationDecision.INTERNAL) {
                    view.stopLoading()
                    if (url != "about:blank") error = text("Переход заблокирован.", "Navigation was blocked.")
                    return
                }
                error = null
                loading = true
                scriptErrors = 0
                resourceErrors = 0
                blankCheck?.cancel()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                diagnostics.location(view.url ?: url)
                diagnostics.add(WebDebugTab.WebView, "PAGE_FINISHED ${DebugRedactor.url(url)} · not proof of HTTP success or app readiness")
                loading = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                // Persist WebView's own cookies without exposing their values to the app.
                CookieManager.getInstance().flush()
                checkPageContent(view, url)
                domDiagnostics.pageReady()
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                diagnostics.add(WebDebugTab.WebView, "PAGE_COMMIT_VISIBLE ${DebugRedactor.url(url)}")
                domDiagnostics.pageReady()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                diagnostics.location(url)
                diagnostics.add(WebDebugTab.WebView, "HISTORY_UPDATED reload=$isReload ${DebugRedactor.url(url)}")
            }

            override fun onLoadResource(view: WebView, url: String?) {
                diagnostics.add(WebDebugTab.Network, "LOAD_RESOURCE ${DebugRedactor.url(url)} · HTTP ? (callback does not expose completion/status)")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                diagnostics.add(WebDebugTab.Network, "REQUEST_ERROR nativeCode=${failure.errorCode} ${failure.description} · ${request.method} " +
                    "${DebugRedactor.url(request.url.toString())} · main=${request.isForMainFrame} · HTTP ? (not exposed by onReceivedError)")
                if (request.isForMainFrame) showLoadError() else resourceErrors++
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                diagnostics.httpError(request.method, request.url.toString(), response.statusCode, response.reasonPhrase.orEmpty(), request.isForMainFrame)
                if (request.isForMainFrame && response.statusCode >= 400) {
                    showLoadError()
                    error = error + " (HTTP ${response.statusCode})"
                } else if (response.statusCode >= 400) resourceErrors++
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, failure: SslError) {
                diagnostics.add(WebDebugTab.Network, "TLS_ERROR code=${failure.primaryError} ${DebugRedactor.url(failure.url)} · cancelled")
                handler.cancel()
                showLoadError()
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                diagnostics.console(message.messageLevel().name, message.message(), message.sourceId(), message.lineNumber())
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) scriptErrors++
                return true // Only the redacted in-memory Debug panel receives console text; never Logcat.
            }
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                this@OfficialWebActivity.progress = newProgress / 100f
                diagnostics.location(view.url, newProgress)
            }
        }
        webView = this
        domDiagnostics.attach(this)
        startPage(this, savedWebState)
    }

    private fun startPage(view: WebView, stateToRestore: Bundle? = null, url: String = SIGN_IN_URL) {
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val revision = WebSession.awaitReady()
                if (webView !== view || sessionCleared) return@launch
                // These operations run consecutively on Main: a new logout cannot interleave.
                WebSession.attach(view)
                loadedRevision = revision
                blankCheck?.cancel()
                error = null
                loading = true
                val restore = stateToRestore?.takeIf { savedWebRevision == revision }
                savedWebState = null
                // WebView restores navigation history, not a guaranteed form/DOM/CAPTCHA snapshot.
                if (restore == null || view.restoreState(restore) == null) {
                    diagnostics.add(WebDebugTab.WebView, "LOAD_URL ${DebugRedactor.url(url)}")
                    view.loadUrl(url, HTML_HEADERS)
                } else {
                    diagnostics.location(view.url)
                    diagnostics.add(WebDebugTab.WebView, "RESTORED_HISTORY ${DebugRedactor.url(view.url)}")
                }
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

    private fun reloadPage() {
        if (clearing || connecting || sessionCleared) return
        webView?.let { view ->
            view.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            diagnostics.add(WebDebugTab.WebView, "RELOAD_REQUESTED · cacheMode=LOAD_NO_CACHE")
            val url = view.url?.takeIf { WebNavigationPolicy.classify(it) == NavigationDecision.INTERNAL } ?: SIGN_IN_URL
            startPage(view, url = url)
        }
    }

    /** Inspect only paint/viewport flags, never page text, input values, cookies or tokens. */
    private fun checkPageContent(view: WebView, finishedUrl: String?) {
        blankCheck?.cancel()
        if (finishedUrl?.toUri()?.host != "chat.deepseek.com") return
        blankCheck = lifecycleScope.launch {
            var emptyChecks = 0
            // Cookie consent can render before the login app. Keep watching until a form appears,
            // including after the user dismisses the consent banner.
            while (true) {
                delay(3_000)
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    emptyChecks = 0
                    continue
                }
                // The SPA can replace / with /sign_in without a new document navigation.
                if (webView !== view || view.url?.toUri()?.host != "chat.deepseek.com" || sessionCleared || clearing || error != null) return@launch
                val raw = withTimeoutOrNull(2_000) { suspendCancellableCoroutine<String> { continuation ->
                    view.evaluateJavascript("""
                        (() => { const body = document.body;
                          const visible = body && (body.innerText.trim().length > 0 ||
                            Array.from(body.querySelectorAll('input,button,form,canvas,iframe,img,svg')).some(e => {
                              const r=e.getBoundingClientRect(); const s=getComputedStyle(e);
                              return r.width>1 && r.height>1 && s.visibility!=='hidden' && s.display!=='none'; }));
                          const nav=performance.getEntriesByType('navigation')[0];
                          const form=body && Array.from(body.querySelectorAll('input:not([type=hidden]),textarea,[contenteditable=true]')).some(e => {
                            const r=e.getBoundingClientRect(); const s=getComputedStyle(e);
                            return r.width>1 && r.height>1 && s.visibility!=='hidden' && s.display!=='none'; });
                          return JSON.stringify({visible:!!visible,width:innerWidth,height:innerHeight,
                            form:!!form,
                            status:nav && typeof nav.responseStatus==='number' ? nav.responseStatus : 0}); })();
                    """.trimIndent()) { value -> if (continuation.isActive) continuation.resume(value) }
                } } ?: "null"
                val probe = try {
                    val inner = Json.parseToJsonElement(raw).jsonPrimitive.content
                    Json.parseToJsonElement(inner).jsonObject
                } catch (_: Exception) { null }
                diagnostics.location(view.url)
                diagnostics.add(WebDebugTab.WebView, "CONTENT_PROBE visible=${(probe?.get("visible") as? JsonPrimitive)?.booleanOrNull} " +
                    "form=${(probe?.get("form") as? JsonPrimitive)?.booleanOrNull} viewport=${probe?.get("width")}×${probe?.get("height")}")
                if ((probe?.get("form") as? JsonPrimitive)?.booleanOrNull == true) return@launch
                if ((probe?.get("visible") as? JsonPrimitive)?.booleanOrNull == true) {
                    emptyChecks = 0
                    continue
                }
                emptyChecks++
                if (emptyChecks >= 3) {
                    val status = (probe?.get("status") as? JsonPrimitive)?.intOrNull?.takeIf { code -> code > 0 }
                    val webVersion = WebView.getCurrentWebViewPackage()?.versionName ?: "unknown"
                    val viewport = "${view.width}×${view.height}"
                    error = text(
                        "DeepSeek не отобразил страницу в WebView. Нажмите обновление. Если в браузере сайт работает, проверьте обновление Android System WebView/Chrome.",
                        "DeepSeek did not render in WebView. Tap reload. If it works in your browser, check Android System WebView/Chrome updates.",
                    ) + "\nWebView $webVersion · ${status?.let { code -> "HTTP $code · " } ?: ""}$viewport · JS $scriptErrors · resources $resourceErrors"
                    return@launch
                }
            }
        }
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

    private fun connectAccount() {
        if (connecting || clearing || sessionCleared) return
        val view = webView ?: return
        fun official() = view.url?.toUri()?.let { it.scheme == "https" && it.host == "chat.deepseek.com" && it.port in listOf(-1, 443) } == true
        if (!official()) { error = text("Откройте официальный DeepSeek и войдите в аккаунт.", "Open the official DeepSeek page and sign in."); return }
        connecting = true
        lifecycleScope.launch {
            try {
                val revision = WebSession.awaitReady()
                val result = suspendCancellableCoroutine<String> { continuation ->
                    view.evaluateJavascript("""
                        (() => { const raw = localStorage.getItem('userToken'); if (!raw) return null;
                          try { const value = JSON.parse(raw); return typeof value === 'string' ? value :
                            (value && typeof value.value === 'string' ? value.value : null); }
                          catch (_) { return raw; } })();
                    """.trimIndent()) { value -> if (continuation.isActive) continuation.resume(value) }
                }
                if (!official() || sessionCleared || WebSession.awaitReady() != revision) throw DeepSeekFailure("Страница входа изменилась. Повторите вход. / Sign-in page changed; retry.")
                val token = (Json.parseToJsonElement(result) as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() && it != "null" && it.length < 16_384 }
                if (token == null) {
                    // A missing session does not prove a CAPTCHA was presented. Open the actual
                    // sign-in route instead of reporting a protection challenge that may not exist.
                    startPage(view, url = SIGN_IN_URL)
                    return@launch
                }
                // Prove native HTTP compatibility before persisting or reporting a connected account.
                DeepSeekHttpTransport(applicationContext, token).request("/api/v0/users/current")
                if (sessionCleared || !official() || WebSession.awaitReady() != revision) throw DeepSeekFailure("Вход был отменён. / Sign-in was cancelled.")
                // Main-thread publication is consecutive with the revision check and logout registration.
                DeepSeekCredentials.get(applicationContext).save(token)
                SettingsStore(applicationContext).completeWelcome()
                finish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = (failure as? DeepSeekFailure)?.message ?: text("Не удалось сохранить подключение. Повторите вход.", "Connection could not be saved; sign in again.")
            } finally { connecting = false }
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
                    if (BuildConfig.DEBUG) {
                        IconButton(onClick = { showDiagnostics = true }) {
                            Icon(Icons.Default.BugReport, text("Отладка WebView", "WebView diagnostics"))
                        }
                    }
                    IconButton(onClick = ::reloadPage, enabled = !clearing && !connecting && !sessionCleared) {
                        Icon(Icons.Default.Refresh, text("Обновить страницу", "Reload page"))
                    }
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
                        "Войдите на официальном сайте, затем нажмите «Подключить SnowSeek» для нативного чата. Пароль остаётся на сайте.",
                        "Sign in on the official website, then tap Connect SnowSeek for native chat. Your password stays on the website.",
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { confirmLogout = true }, enabled = !clearing && !sessionCleared && !connecting) {
                        Text(if (clearing) text("Очистка…", "Clearing…") else text("Выйти с сайта", "Sign out of website"))
                    }
                    Button(onClick = ::connectAccount, enabled = !loading && !clearing && !connecting && !sessionCleared) {
                        Text(if (connecting) text("Проверка…", "Checking…") else text("Подключить SnowSeek", "Connect SnowSeek"))
                    }
                }
                if (clearing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else if (loading) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                val currentError = error
                if (currentError != null) {
                    Column(Modifier.padding(16.dp)) {
                        Text(currentError, color = MaterialTheme.colorScheme.error)
                        if (!sessionCleared) {
                            Button(onClick = ::reloadPage, enabled = !clearing && !connecting) { Text(text("Повторить", "Retry")) }
                            TextButton(onClick = { webView?.let { startPage(it, url = SIGN_IN_URL) } }, enabled = !clearing && !connecting) {
                                Text(text("Открыть страницу входа", "Open sign-in page"))
                            }
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
        if (BuildConfig.DEBUG && showDiagnostics) {
            WebViewDebugPanel(diagnostics, russian, onDismiss = { showDiagnostics = false }, onDomSnapshot = { domDiagnostics.capture() })
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
        domDiagnostics.close()
        webView = null
        view.stopLoading()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.destroy()
    }

    override fun onDestroy() {
        blankCheck?.cancel()
        webView?.let { releaseWebView(it) }
        super.onDestroy()
    }
    companion object {
        const val OFFICIAL_URL = "https://chat.deepseek.com/"
        const val SIGN_IN_URL = "https://chat.deepseek.com/sign_in"
        val HTML_HEADERS = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        private const val STATE_WEB = "official_web_state"
        private const val STATE_REVISION = "official_web_revision"
    }
}
