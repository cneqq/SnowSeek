package dev.snowseek.app.auth

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.snowseek.app.data.DeepSeekHttpTransport
import dev.snowseek.app.settings.SettingsStore
import dev.snowseek.core.DeepSeekFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

enum class GoogleSignInPhase { SETUP, WAITING, CHECKING, CONFIRM, ERROR, DONE }

class GoogleSignInModel(application: Application) : AndroidViewModel(application) {
    var phase by mutableStateOf(GoogleSignInPhase.SETUP); private set
    var message by mutableStateOf(""); private set
    var accountName by mutableStateOf(""); private set
    var accountIdentity by mutableStateOf(""); private set
    var hasStartUrl by mutableStateOf(false); private set
    private var initialized = false
    private var handledReturn = false
    private var startUrl: String? = null
    private var token: String? = null
    private var attemptId: String? = null
    private var revision = -1L
    private val sessionConnection = VerifiedSessionConnection()
    private val app get() = getApplication<Application>()

    fun prepare(url: String?) {
        if (initialized) return
        initialized = true
        startUrl = url?.takeIf(GoogleWebSignIn::isAuthorizeUrl)
        hasStartUrl = startUrl != null
    }

    /** The official website already performed its normal verification before this URL existed. */
    fun openBrowser(open: (String) -> Unit) {
        if (phase != GoogleSignInPhase.SETUP) return
        val target = startUrl ?: return
        try {
            attemptId = GoogleBrowserAttempt.begin(app)
            open(target) // Keep the official URL intact: no redirect/client/state rewrites.
            startUrl = null
            hasStartUrl = false
            phase = GoogleSignInPhase.WAITING
        } catch (failure: Exception) {
            GoogleBrowserAttempt.cancelIfOwned(app, attemptId)
            message = (failure as? DeepSeekFailure)?.message ?: "Не удалось открыть обычный браузер. Вернитесь к форме и повторите Google-вход. / Could not open the browser; retry Google sign-in."
            phase = GoogleSignInPhase.ERROR
        }
    }

    fun receive(raw: String?) {
        if (handledReturn) return
        handledReturn = true
        initialized = true
        startUrl = null
        hasStartUrl = false
        val callback = raw?.let(GoogleWebSignIn::parseCallback)
        if (callback == null) {
            message = "DeepSeek не вернул успешный Google-вход. Вернитесь к форме и начните заново. / DeepSeek did not return a successful Google sign-in; start again."
            phase = GoogleSignInPhase.ERROR
            return
        }
        attemptId = GoogleBrowserAttempt.claim(app)
        if (attemptId == null) {
            message = "Ожидающий вход отсутствует, истёк или уже обработан. Начните Google-вход из SnowSeek заново. / No fresh pending sign-in; start again from SnowSeek."
            phase = GoogleSignInPhase.ERROR
            return
        }
        phase = GoogleSignInPhase.CHECKING
        viewModelScope.launch {
            try {
                revision = WebSession.awaitReady()
                if (!GoogleBrowserAttempt.canConfirm(app, attemptId.orEmpty())) throw DeepSeekFailure("Вход отменён или истёк. / Sign-in cancelled or expired.")
                val data = DeepSeekHttpTransport(app).exchangeGoogleNonce(callback.nonce).jsonObject
                val received = (data["token"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() && it.length < 16_384 && it != "null" }
                    ?: throw DeepSeekFailure("DeepSeek не выдал сессию Google. Начните вход заново. / No Google session returned; start again.")
                val profile = DeepSeekHttpTransport(app, received, includeWebCookies = false).request("/api/v0/users/current").jsonObject
                if (!stillActive()) throw DeepSeekFailure("Вход отменён или истёк. / Sign-in cancelled or expired.")
                val idProfile = profile["id_profile"] as? JsonObject
                fun text(obj: JsonObject?, key: String) = (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                accountName = text(idProfile, "name") ?: "DeepSeek"
                accountIdentity = text(profile, "email") ?: text(idProfile, "email")
                    ?: text(profile, "mobile_number") ?: text(profile, "id")?.let { "DeepSeek ID: $it" }
                    ?: throw DeepSeekFailure("Не удалось определить аккаунт для подтверждения. / Account identity unavailable.")
                token = received
                // A browser callback is untrusted. Do not silently bind an account: require the
                // user to inspect the server-verified identity before saving any credential.
                phase = GoogleSignInPhase.CONFIRM
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) }
        }
    }

    private suspend fun stillActive() = GoogleBrowserAttempt.canConfirm(app, attemptId.orEmpty()) && WebSession.awaitReady() == revision

    fun confirm() {
        if (phase != GoogleSignInPhase.CONFIRM) return
        val received = token ?: return
        phase = GoogleSignInPhase.CHECKING
        viewModelScope.launch {
            try {
                val saved = sessionConnection.connect(received,
                    current = { token == received && GoogleBrowserAttempt.canConfirm(app, attemptId.orEmpty()) },
                    verify = {
                        if (!stillActive()) throw DeepSeekFailure("Вход отменён или истёк. / Sign-in cancelled or expired.")
                        SettingsStore(app).completeWelcome()
                        if (!stillActive()) throw DeepSeekFailure("Вход отменён или истёк. / Sign-in cancelled or expired.")
                    },
                    save = {
                        GoogleBrowserAttempt.cancelIfOwned(app, attemptId)
                        DeepSeekCredentials.get(app).save(it, webCookies = false)
                    })
                if (!saved) throw DeepSeekFailure("Вход отменён или истёк. / Sign-in cancelled or expired.")
                token = null
                phase = GoogleSignInPhase.DONE
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) }
        }
    }

    fun cancel() {
        token = null; startUrl = null
        GoogleBrowserAttempt.cancelIfOwned(app, attemptId)
    }

    override fun onCleared() {
        cancel()
        super.onCleared()
    }

    private fun fail(failure: Exception) {
        token = null
        GoogleBrowserAttempt.cancelIfOwned(app, attemptId)
        message = (failure as? DeepSeekFailure)?.message ?: "Не удалось подключить Google-сессию. Начните вход заново. / Could not connect the Google session; start again."
        phase = GoogleSignInPhase.ERROR
    }
}
