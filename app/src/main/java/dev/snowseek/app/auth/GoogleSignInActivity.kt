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
import dev.snowseek.app.ui.theme.SnowSeekTheme

class GoogleSignInActivity : ComponentActivity() {
    private lateinit var model: GoogleSignInModel
    private var returnApproved by mutableStateOf<Boolean?>(null)
    private val russian get() = intent.getStringExtra("language") != "en"
    private fun label(ru: String, en: String) = if (russian) ru else en

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        LaunchedEffect(phase) {
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
                        Text(label("Google откроется в обычном браузере. Пароль и проверки Google остаются там. После входа SnowSeek получит одноразовый результат DeepSeek и покажет аккаунт для подтверждения.",
                            "Google opens in your normal browser. Passwords and Google verification stay there. SnowSeek receives DeepSeek's one-time return and asks you to confirm the account."))
                        Text(label("На Android 12+ один раз разрешите возврат: «Открывать по умолчанию» → «Открывать поддерживаемые ссылки» → chat.deepseek.com. Это касается ссылки завершения входа; настройки меняете вы сами.",
                            "On Android 12+, allow the return once: Open by default → Open supported links → chat.deepseek.com. You choose this system setting yourself."))
                        OutlinedButton(onClick = ::returnSettings) { Text(label("Настроить возврат в SnowSeek", "Set up return to SnowSeek")) }
                        Text(when (returnApproved) {
                            true -> label("Возврат разрешён.", "Return links are enabled.")
                            false -> label("Возврат пока не разрешён. Включите chat.deepseek.com в настройках выше.", "Return links are not enabled yet. Enable chat.deepseek.com above.")
                            null -> label("Android не сообщил статус. Проверьте разрешение в настройках.", "Android did not report link status. Check it in settings.")
                        })
                        if (returnApproved == null) Row {
                            Checkbox(checked = manualApproval, onCheckedChange = { manualApproval = it })
                            Text(label("Я разрешил ссылки возврата", "I enabled return links"), Modifier.padding(top = 12.dp))
                        }
                        if (model.hasStartUrl) {
                            Button(onClick = { model.openBrowser(::openBrowser) }, enabled = returnApproved == true || manualApproval) {
                                Text(label("Продолжить в браузере", "Continue in browser"))
                            }
                        } else Text(label("Теперь вернитесь к форме DeepSeek в SnowSeek и нажмите «Войти с помощью Google».",
                            "Now return to the DeepSeek form in SnowSeek and tap Sign in with Google."))
                        TextButton(onClick = { close() }) { Text(label("К форме входа", "Back to sign-in")) }
                    }
                    GoogleSignInPhase.WAITING -> {
                        Text(label("Завершите Google-вход в браузере. На вопрос Android об открытии ссылки выберите SnowSeek. Затем здесь появится подтверждение аккаунта.",
                            "Complete Google sign-in in the browser. If Android asks where to open the return link, choose SnowSeek. Then confirm the account here."))
                        Text(label("Если браузер остался на DeepSeek и возврата не было, одноразовый результат мог уже использоваться сайтом. Проверьте ссылки возврата и начните новый вход из формы SnowSeek.",
                            "If the browser stayed on DeepSeek, the website may already have used the one-time return. Check return-link settings and start a fresh sign-in from SnowSeek."))
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
