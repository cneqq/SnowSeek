package dev.snowseek.app.auth

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.snowseek.app.MainActivity
import dev.snowseek.app.settings.AppSettings
import dev.snowseek.app.settings.SettingsStore
import dev.snowseek.app.ui.theme.SnowSeekTheme

class GoogleSignInActivity : ComponentActivity() {
    private lateinit var model: GoogleSignInModel
    private var returnApproved by mutableStateOf<Boolean?>(null)
    private val russian get() = intent.getStringExtra("language") != "en"
    private fun label(ru: String, en: String) = if (russian) ru else en

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val incoming = intent.dataString
        if (intent.action == Intent.ACTION_VIEW && incoming != null && GoogleWebSignIn.shouldReturnToBrowser(incoming)) {
            // Android intent filters cannot distinguish query providers. Delegate other providers
            // back to the browser explicitly, without claiming/cancelling a Google attempt.
            intent = Intent(this, GoogleSignInActivity::class.java)
            forwardOtherProvider(incoming)
            return
        }
        model = ViewModelProvider(this)[GoogleSignInModel::class.java]
        val language = intent.getStringExtra("language")
        if (intent.action == Intent.ACTION_VIEW) model.receive(intent.dataString)
        else model.prepare(intent.getStringExtra(EXTRA_START_URL))
        // Never keep callback nonces or verification URLs in activity instance state.
        intent = Intent(this, GoogleSignInActivity::class.java).putExtra("language", language)
        setContent {
            val settings by remember { SettingsStore(applicationContext).settings }.collectAsState(initial = AppSettings())
            SnowSeekTheme(settings) { Screen() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val raw = intent.dataString
        val language = this.intent.getStringExtra("language")
        this.intent = Intent(this, GoogleSignInActivity::class.java).putExtra("language", language)
        if (intent.action == Intent.ACTION_VIEW && raw != null) {
            if (GoogleWebSignIn.shouldReturnToBrowser(raw)) forwardOtherProvider(raw)
            else model.receive(raw)
        }
    }

    private fun forwardOtherProvider(url: String) {
        try {
            openBrowser(url)
            finish()
        } catch (_: Exception) {
            setContent {
                val settings by remember { SettingsStore(applicationContext).settings }.collectAsState(initial = AppSettings())
                SnowSeekTheme(settings) {
                    Surface(Modifier.fillMaxSize()) {
                        AlertDialog(
                            onDismissRequest = { finish() },
                            title = { Text(label("Не удалось открыть браузер", "Could not open a browser")) },
                            text = { Text(label("Откройте результат входа в обычном браузере.", "Open the sign-in result in your browser.")) },
                            confirmButton = { TextButton(onClick = { finish() }) { Text(label("Закрыть", "Close")) } },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        returnApproved = approved()
    }

    private fun approved(): Boolean? {
        if (Build.VERSION.SDK_INT < 31) return true // Older Android can show its link-handler chooser.
        return try {
            val state = getSystemService(DomainVerificationManager::class.java).getDomainVerificationUserState(packageName) ?: return null
            state.isLinkHandlingAllowed && state.hostToStateMap[GoogleWebSignIn.HOST] in setOf(
                DomainVerificationUserState.DOMAIN_STATE_VERIFIED, DomainVerificationUserState.DOMAIN_STATE_SELECTED,
            )
        } catch (_: Exception) { null }
    }

    private fun returnSettings() {
        val action = if (Build.VERSION.SDK_INT >= 31) Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS else Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        try { startActivity(Intent(action, "package:$packageName".toUri())) }
        catch (_: Exception) { returnApproved = null }
    }

    private fun openBrowser(url: String) {
        // Resolve the ordinary default browser against an unrelated public origin without
        // visiting it, so DeepSeek's first-party app cannot take over the authorize request.
        val probe = Intent(Intent.ACTION_VIEW, "https://example.com/".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        @Suppress("DEPRECATION")
        val browser = packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (browser == null || browser == "android" || browser == packageName) {
            throw dev.snowseek.core.DeepSeekFailure("Назначьте обычный браузер по умолчанию в настройках Android, затем повторите Google-вход. / Set a default browser in Android settings, then retry Google sign-in.")
        }
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(browser))
    }

    @Composable private fun Screen() {
        val phase = model.phase
        fun close() { model.cancel(); finish() }
        fun continueSignIn() {
            if (model.hasStartUrl) model.openBrowser(::openBrowser)
            else {
                startActivity(Intent(this@GoogleSignInActivity, OfficialWebActivity::class.java)
                    .putExtra("language", if (russian) "ru" else "en").putExtra("google_mode", true))
                finish()
            }
        }
        BackHandler { close() }
        LaunchedEffect(phase, returnApproved) {
            if (phase == GoogleSignInPhase.SETUP && model.hasStartUrl && returnApproved == true) model.openBrowser(::openBrowser)
            if (phase == GoogleSignInPhase.DONE) {
                startActivity(Intent(this@GoogleSignInActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        }
        val title = when (phase) {
            GoogleSignInPhase.SETUP -> if (returnApproved == true) label("Вход через Google", "Sign in with Google")
                else label("Вернитесь в SnowSeek после входа", "Return to SnowSeek after sign-in")
            GoogleSignInPhase.WAITING -> label("Завершите вход в браузере", "Finish signing in in your browser")
            GoogleSignInPhase.CHECKING -> label("Подключаем аккаунт", "Connecting your account")
            GoogleSignInPhase.CONFIRM -> label("Войти в этот аккаунт?", "Sign in with this account?")
            GoogleSignInPhase.ERROR -> label("Не удалось войти", "Could not sign in")
            GoogleSignInPhase.DONE -> label("Аккаунт подключён", "Account connected")
        }
        val detail = when (phase) {
            GoogleSignInPhase.SETUP -> if (returnApproved == true)
                label("Продолжите на официальной форме DeepSeek.", "Continue on the official DeepSeek form.")
                else label("В настройках Android включите открытие поддерживаемых ссылок для SnowSeek и выберите chat.deepseek.com. Это нужно один раз.",
                    "In Android settings, allow SnowSeek to open supported links and select chat.deepseek.com. You only need to do this once.")
            GoogleSignInPhase.WAITING -> label("После входа выберите SnowSeek, чтобы подключить аккаунт DeepSeek.",
                "After signing in, choose SnowSeek to connect your DeepSeek account.")
            GoogleSignInPhase.CHECKING -> label("Проверяем сессию DeepSeek…", "Verifying your DeepSeek session…")
            GoogleSignInPhase.CONFIRM -> label("Проверьте аккаунт перед подключением к SnowSeek.", "Check the account before connecting it to SnowSeek.")
            GoogleSignInPhase.ERROR -> model.message
            GoogleSignInPhase.DONE -> ""
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                val availableHeight = maxHeight
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .heightIn(min = availableHeight).padding(horizontal = 24.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("SnowSeek", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyLarge,
                    color = if (phase == GoogleSignInPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                when (phase) {
                    GoogleSignInPhase.SETUP -> {
                        if (returnApproved != true) {
                            Button(onClick = ::returnSettings, modifier = Modifier.fillMaxWidth()) {
                                Text(label("Открыть настройки", "Open settings"))
                            }
                            if (returnApproved == null) {
                                Text(label("Не удалось проверить настройку автоматически. При возврате из браузера выберите SnowSeek.",
                                    "The setting could not be checked automatically. Choose SnowSeek when returning from the browser."),
                                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = { continueSignIn() }, modifier = Modifier.fillMaxWidth()) {
                                    Text(label("Продолжить вход", "Continue sign-in"))
                                }
                            }
                        } else Button(onClick = { continueSignIn() }, modifier = Modifier.fillMaxWidth()) {
                            Text(label("Войти через Google", "Sign in with Google"))
                        }
                        TextButton(onClick = { close() }) { Text(label("К форме входа", "Back to sign-in")) }
                    }
                    GoogleSignInPhase.WAITING -> {
                        OutlinedButton(onClick = ::returnSettings, modifier = Modifier.fillMaxWidth()) {
                            Text(label("Проверить возврат в приложение", "Check return to app"))
                        }
                        TextButton(onClick = { close() }) { Text(label("Отменить вход", "Cancel sign-in")) }
                    }
                    GoogleSignInPhase.CHECKING -> CircularProgressIndicator(Modifier.size(32.dp))
                    GoogleSignInPhase.CONFIRM -> {
                        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(model.accountName, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                                Text(model.accountIdentity, style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Button(onClick = model::confirm, modifier = Modifier.fillMaxWidth()) { Text(label("Войти в DeepSeek", "Sign in to DeepSeek")) }
                        TextButton(onClick = { close() }) { Text(label("Отмена", "Cancel")) }
                    }
                    GoogleSignInPhase.ERROR -> {
                        Button(onClick = { close() }, modifier = Modifier.fillMaxWidth()) { Text(label("Вернуться к форме входа", "Back to sign-in")) }
                    }
                    GoogleSignInPhase.DONE -> Unit
                }
                }
                }
            }
        }
    }

    companion object { const val EXTRA_START_URL = "google_authorize_url" }
}
