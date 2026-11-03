package dev.snowseek.app.auth

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.ui.unit.dp
import dev.snowseek.app.MainActivity
import dev.snowseek.app.settings.AppSettings
import dev.snowseek.app.settings.SettingsStore
import dev.snowseek.app.settings.ThemeMode
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
            SnowSeekTheme(settings.copy(theme = ThemeMode.DARK)) { Screen() }
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
        try { openBrowser(url) }
        catch (_: Exception) { Toast.makeText(this, label("Откройте результат входа в обычном браузере.", "Open the sign-in result in your browser."), Toast.LENGTH_LONG).show() }
        finally { finish() }
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
        var manualApproval by remember { mutableStateOf(false) }
        fun close() { model.cancel(); finish() }
        BackHandler { close() }
        LaunchedEffect(phase, returnApproved) {
            if (phase == GoogleSignInPhase.SETUP && model.hasStartUrl && returnApproved == true) model.openBrowser(::openBrowser)
            if (phase == GoogleSignInPhase.DONE) {
                startActivity(Intent(this@GoogleSignInActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Google · DeepSeek", style = MaterialTheme.typography.headlineSmall)
                when (phase) {
                    GoogleSignInPhase.SETUP -> {
                        Text(label("Войдите через Google в браузере, затем подтвердите аккаунт в SnowSeek.",
                            "Sign in with Google in your browser, then confirm your account in SnowSeek."))
                        if (returnApproved != true) {
                            Text(label("Один раз разрешите возврат: в настройках включите «Открывать поддерживаемые ссылки» и выберите chat.deepseek.com.",
                                "Allow return once: enable Open supported links in settings and select chat.deepseek.com."))
                            Button(onClick = ::returnSettings, modifier = Modifier.fillMaxWidth()) { Text(label("Разрешить возврат в приложение", "Enable return to app")) }
                        }
                        if (returnApproved == null) Row {
                            Checkbox(checked = manualApproval, onCheckedChange = { manualApproval = it })
                            Text(label("Я разрешил ссылки возврата", "I enabled return links"), Modifier.padding(top = 12.dp))
                        }
                        if (model.hasStartUrl) {
                            Button(onClick = { model.openBrowser(::openBrowser) }, enabled = returnApproved == true || manualApproval) {
                                Text(label("Войти через Google", "Sign in with Google"))
                            }
                        } else Button(onClick = {
                            startActivity(Intent(this@GoogleSignInActivity, OfficialWebActivity::class.java)
                                .putExtra("language", if (russian) "ru" else "en").putExtra("google_mode", true))
                            finish()
                        }, modifier = Modifier.fillMaxWidth()) { Text(label("Открыть форму DeepSeek", "Open DeepSeek sign-in")) }
                        TextButton(onClick = { close() }) { Text(label("К форме входа", "Back to sign-in")) }
                    }
                    GoogleSignInPhase.WAITING -> {
                        Text(label("Завершите вход в браузере. Для открытия результата выберите SnowSeek.",
                            "Finish signing in in your browser. Choose SnowSeek to open the result."))
                        Text(label("Остались на сайте? Проверьте разрешение возврата и начните вход заново.",
                            "Still on the website? Check return-link permission and start again."))
                        TextButton(onClick = ::returnSettings) { Text(label("Проверить ссылки возврата", "Check return links")) }
                        TextButton(onClick = { close() }) { Text(label("Отменить вход", "Cancel sign-in")) }
                    }
                    GoogleSignInPhase.CHECKING -> { CircularProgressIndicator(); Text(label("Проверяем сессию DeepSeek…", "Verifying the DeepSeek session…")) }
                    GoogleSignInPhase.CONFIRM -> {
                        Text(label("Подключить этот аккаунт? Проверьте, что это выбранный вами аккаунт Google.", "Connect this account? Check that it is the Google account you chose."))
                        Text(model.accountName, style = MaterialTheme.typography.titleLarge)
                        Text(model.accountIdentity)
                        Button(onClick = model::confirm) { Text(label("Подключить аккаунт", "Connect account")) }
                        TextButton(onClick = { close() }) { Text(label("Это другой аккаунт / отмена", "Wrong account / cancel")) }
                    }
                    GoogleSignInPhase.ERROR -> {
                        Text(model.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { close() }) { Text(label("К форме для нового входа", "Back for a new sign-in")) }
                    }
                    GoogleSignInPhase.DONE -> Text(label("Аккаунт подключён", "Account connected"))
                }
            }
        }
    }

    companion object { const val EXTRA_START_URL = "google_authorize_url" }
}
