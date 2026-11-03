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
import androidx.compose.foundation.BorderStroke
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
    private val russian get() = intent.getStringExtra("language") != "en"
    private fun label(ru: String, en: String) = if (russian) ru else en
    private val browser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Neither closing a tab nor RESULT_OK proves an authenticated SnowSeek session.
        waiting = false
        returned = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        waiting = savedInstanceState?.getBoolean("waiting") == true
        returned = savedInstanceState?.getBoolean("returned") == true
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
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        TextButton(onClick = { finish() }) { Text(label("Назад", "Back")) }
                        BrandMark(64)
                        Text(label("Google в браузере", "Google in your browser"), style = MaterialTheme.typography.headlineMedium)
                        Text(label("На официальной странице DeepSeek выберите «Войти через Google». Данные входа обрабатывают DeepSeek и Google.",
                            "Choose Sign in with Google on the official DeepSeek page. DeepSeek and Google handle your sign-in details."))
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(label("Вход на сайт, без подключения SnowSeek", "Website sign-in, without connecting SnowSeek"),
                                    style = MaterialTheme.typography.titleMedium)
                                Text(label("Поддерживаемый способ перенести этот вход в SnowSeek не найден. Даже после входа через Google чаты будут доступны на сайте, а аккаунт в SnowSeek не подключится. Для чатов в приложении используйте встроенную форму DeepSeek.",
                                    "No supported way to transfer this sign-in to SnowSeek has been found. After signing in with Google, chats are available on the website, but your SnowSeek account remains unconnected. Use the built-in DeepSeek form for chats in the app."),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        val currentPlan = plan
                        if (currentPlan == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(label("Проверяем возможности браузера…", "Checking browser capabilities…"))
                        } else {
                            Text(if (currentPlan.isolated) label(
                                "Браузер сообщает о поддержке отдельной временной вкладки. Запросим новый вход без использования вашей обычной сессии. После закрытия вкладки её данные удаляются браузером.",
                                "Your browser reports support for a separate temporary tab. We will request a fresh sign-in without your regular session. The browser deletes this tab’s data when it closes.")
                            else label(
                                "Браузер не подтвердил поддержку отдельной сессии. Если вы уже вошли в DeepSeek, сайт может сразу открыть ваши чаты. Принудительно показать новый вход нельзя; обычная сессия браузера сохранится.",
                                "Your browser has not confirmed support for a separate session. If you are already signed in to DeepSeek, the site may open your chats directly. A fresh sign-in cannot be forced; your regular browser session is preserved."),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (returned) Text(label("Вы вернулись из браузера. Аккаунт SnowSeek через этот способ не подключён.",
                            "You returned from the browser. This method has not connected a SnowSeek account."), color = MaterialTheme.colorScheme.primary)
                        if (failed) Text(label("Не удалось открыть браузер. Проверьте, установлен ли браузер, и повторите попытку.",
                            "Could not open a browser. Check that a browser is installed and try again."), color = MaterialTheme.colorScheme.error)
                        if (waiting) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Button(onClick = { currentPlan?.let { openBrowser(it, toolbarColor) } }, enabled = currentPlan != null && !waiting,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null)
                            Spacer(Modifier.width(10.dp))
                            Text(label("Открыть DeepSeek в браузере", "Open DeepSeek in browser"))
                        }
                        OutlinedButton(onClick = {
                            startActivity(Intent(this@GoogleSignInActivity, OfficialWebActivity::class.java)
                                .putExtra("language", if (russian) "ru" else "en"))
                            finish()
                        }, enabled = !waiting, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
                            Text(label("Войти через DeepSeek в SnowSeek", "Sign in to SnowSeek with DeepSeek"))
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
        super.onSaveInstanceState(outState)
    }
}
