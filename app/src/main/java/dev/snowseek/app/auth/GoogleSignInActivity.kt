package dev.snowseek.app.auth

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.snowseek.app.settings.AppSettings
import dev.snowseek.app.settings.SettingsStore
import dev.snowseek.app.ui.theme.SnowSeekTheme

/** Browser-only Google sign-in. No receiver or session exchange for unbound callbacks. */
class GoogleSignInActivity : ComponentActivity() {
    private var waiting by mutableStateOf(false)
    private var returned by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var startUrl: String? = null
    private val russian get() = intent.getStringExtra("language") != "en"
    private fun label(ru: String, en: String) = if (russian) ru else en
    private val browser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Closing a tab says nothing about authentication. Never report success from RESULT_OK.
        waiting = false
        returned = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startUrl = intent.getStringExtra(EXTRA_START_URL)?.takeIf(GoogleWebSignIn::isAuthorizeUrl)
        intent.removeExtra(EXTRA_START_URL)
        waiting = savedInstanceState?.getBoolean("waiting") == true
        returned = savedInstanceState?.getBoolean("returned") == true
        setContent {
            val settings by remember { SettingsStore(applicationContext).settings }.collectAsState(initial = AppSettings())
            SnowSeekTheme(settings) {
                val color = MaterialTheme.colorScheme.surface.toArgb()
                LaunchedEffect(Unit) {
                    if (savedInstanceState == null && (startUrl != null || intent.getBooleanExtra(EXTRA_OPEN_BROWSER, false))) openBrowser(color)
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Spacer(Modifier.height(40.dp))
                        Text("SnowSeek", style = MaterialTheme.typography.headlineLarge)
                        Text(label("Вход в DeepSeek", "Sign in to DeepSeek"), style = MaterialTheme.typography.titleMedium)
                        Text(label("Google откроется на официальном сайте DeepSeek в защищённой вкладке браузера. Выберите там «Войти через Google».",
                            "The official DeepSeek site opens in a secure browser tab. Choose Sign in with Google there."))
                        Text(label("Google-сессия и чаты останутся на сайте. В исследованном потоке DeepSeek нет подтверждённого способа автоматически перенести Google-вход в нативный чат SnowSeek.",
                            "Your Google session and chats stay on the website. The inspected DeepSeek flow has no verified way to transfer Google sign-in to SnowSeek native chat."),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (waiting) {
                            CircularProgressIndicator()
                            Text(label("Открыта вкладка DeepSeek…", "DeepSeek tab is open…"))
                        }
                        if (returned) Text(label("Вкладка закрыта. Это не подтверждает вход в SnowSeek. Можно продолжить на сайте или выбрать другой способ для нативных чатов.",
                            "The tab was closed. This does not confirm sign-in to SnowSeek. Continue on the website or choose another method for native chat."))
                        if (failed) Text(label("Не удалось открыть браузер. Включите браузер по умолчанию и повторите попытку.",
                            "Could not open a browser. Enable a default browser and try again."), color = MaterialTheme.colorScheme.error)
                        Button(onClick = { openBrowser(color) }, enabled = !waiting,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
                            Text(label(if (returned) "Продолжить на сайте DeepSeek" else "Войти через Google",
                                if (returned) "Continue on DeepSeek" else "Sign in with Google"))
                        }
                        OutlinedButton(onClick = {
                            startActivity(Intent(this@GoogleSignInActivity, OfficialWebActivity::class.java).putExtra("language", if (russian) "ru" else "en"))
                            finish()
                        }, enabled = !waiting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(label("Другой способ для нативного чата", "Another method for native chat"))
                        }
                        TextButton(onClick = { finish() }) { Text(label("Назад", "Back")) }
                    }
                }
            }
        }
    }

    private fun openBrowser(color: Int) {
        try {
            val target = startUrl ?: OfficialWebActivity.SIGN_IN_URL
            startUrl = null // Never persist or retry a verification URL.
            val probe = Intent(Intent.ACTION_VIEW, "https://example.com/".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
            @Suppress("DEPRECATION")
            val browserPackage = packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                ?.takeUnless { it == "android" || it == packageName }
                ?: throw IllegalStateException("No default browser")
            val tab = CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(color).build())
                .setShowTitle(true).build()
            tab.intent.setPackage(browserPackage)
            tab.intent.data = target.toUri()
            failed = false
            waiting = true
            browser.launch(tab.intent)
        } catch (_: Exception) { waiting = false; failed = true }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("waiting", waiting)
        outState.putBoolean("returned", returned)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_START_URL = "google_authorize_url"
        const val EXTRA_OPEN_BROWSER = "open_browser"
    }
}
