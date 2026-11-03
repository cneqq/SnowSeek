package dev.snowseek.app.auth

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.snowseek.app.settings.AppSettings
import dev.snowseek.app.settings.ThemeMode
import dev.snowseek.app.ui.BrandMark
import dev.snowseek.app.ui.theme.SnowSeekTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Website-only browser access. No OAuth receiver, cookie access or native account activation. */
class GoogleSignInActivity : ComponentActivity() {
    private data class BrowserPlan(val provider: String?, val isolated: Boolean)
    private var plan by mutableStateOf<BrowserPlan?>(null)
    private var waiting by mutableStateOf(false)
    private var returned by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var autoOpenHandled = false
    private val russian get() = intent.getStringExtra("language") != "en"
    private fun label(ru: String, en: String) = if (russian) ru else en
    private val browser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Neither closing a tab nor RESULT_OK proves an authenticated SnowSeek session.
        waiting = false
        returned = true
        if (intent.getBooleanExtra(EXTRA_FROM_SIGN_IN, false)) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        waiting = savedInstanceState?.getBoolean("waiting") == true
        returned = savedInstanceState?.getBoolean("returned") == true
        autoOpenHandled = savedInstanceState?.getBoolean("auto_open_handled") == true
        setContent {
            SnowSeekTheme(AppSettings(theme = ThemeMode.DARK)) {
                val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()
                LaunchedEffect(Unit) {
                    plan = withContext(Dispatchers.IO) {
                        try {
                            val provider = CustomTabsClient.getPackageName(this@GoogleSignInActivity, null)
                            BrowserPlan(provider, provider != null &&
                                CustomTabsClient.isEphemeralBrowsingSupported(this@GoogleSignInActivity, provider))
                        } catch (_: SecurityException) { BrowserPlan(null, false) }
                    }
                    // The embedded form already presented the explanation and browser action.
                    if (intent.getBooleanExtra(EXTRA_FROM_SIGN_IN, false) && !autoOpenHandled) {
                        autoOpenHandled = true
                        plan?.let { openBrowser(it, toolbarColor) }
                    }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        TextButton(onClick = { finish() }) { Text(label("Назад", "Back")) }
                        BrandMark(64)
                        Text(label("Google в браузере", "Google in your browser"), style = MaterialTheme.typography.headlineMedium)
                        Text(label("На сайте DeepSeek выберите Google. Чаты откроются в браузере, а не в SnowSeek.",
                            "Choose Google on the DeepSeek website. Your chats will open in the browser, not in SnowSeek."))
                        Text(label("Для чатов в приложении войдите через DeepSeek по почте.",
                            "For chats in the app, sign in to DeepSeek with email."),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val currentPlan = plan
                        if (currentPlan == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(label("Готовим браузер…", "Preparing browser…"))
                        } else {
                            Text(if (currentPlan.isolated) label(
                                "Запросим отдельную временную вкладку для нового входа.",
                                "We will request a separate temporary tab for a fresh sign-in.")
                            else label(
                                "Браузер может открыть текущий аккаунт DeepSeek: отдельный вход не поддерживается.",
                                "Your browser may open your current DeepSeek account: separate sign-in is not supported."),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (returned) Text(label("Вход в браузере не подключает аккаунт к SnowSeek.",
                            "Browser sign-in does not connect an account to SnowSeek."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (failed) Text(label("Не удалось открыть браузер. Проверьте, установлен ли браузер, и повторите попытку.",
                            "Could not open a browser. Check that a browser is installed and try again."), color = MaterialTheme.colorScheme.error)
                        if (waiting) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Button(onClick = { currentPlan?.let { openBrowser(it, toolbarColor) } }, enabled = currentPlan != null && !waiting,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null)
                            Spacer(Modifier.width(10.dp))
                            Text(label("Открыть в браузере", "Open in browser"))
                        }
                        OutlinedButton(onClick = {
                            if (!intent.getBooleanExtra(EXTRA_FROM_SIGN_IN, false)) {
                                startActivity(Intent(this@GoogleSignInActivity, OfficialWebActivity::class.java)
                                    .putExtra("language", if (russian) "ru" else "en"))
                            }
                            finish()
                        }, enabled = !waiting, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
                            Text(label("Войти в приложении", "Sign in to the app"))
                        }
                    }
                }
            }
        }
    }

    private fun openBrowser(target: BrowserPlan, toolbarColor: Int) {
        try {
            // Start at the official form: its Google button supplies all required verification.
            // No invented force-login parameters, redirect URIs or authentication result handling.
            val tab = CustomTabsIntent.Builder()
                .setEphemeralBrowsingEnabled(target.isolated)
                .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
                .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build())
                .setShowTitle(true).build()
            tab.intent.setPackage(target.provider)
            tab.intent.data = OfficialWebActivity.SIGN_IN_URL.toUri()
            failed = false
            waiting = true
            browser.launch(tab.intent)
        } catch (_: ActivityNotFoundException) { waiting = false; failed = true }
        catch (_: SecurityException) { waiting = false; failed = true }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("waiting", waiting)
        outState.putBoolean("returned", returned)
        outState.putBoolean("auto_open_handled", autoOpenHandled)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_FROM_SIGN_IN = "from_embedded_sign_in"
    }
}
