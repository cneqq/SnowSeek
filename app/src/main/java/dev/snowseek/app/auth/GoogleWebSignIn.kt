package dev.snowseek.app.auth

import java.net.URI
import java.net.URLDecoder

/** These URLs are received from the official page; never construct a Google OAuth client. */
object GoogleWebSignIn {
    const val HOST = "chat.deepseek.com"
    const val CALLBACK_PATH = "/authorized"
    private const val AUTHORIZE_PATH = "/api/v0/users/oauth/google/authorize"
    class Callback(val nonce: String) { override fun toString() = "GoogleCallback([redacted])" }

    private fun official(raw: String): URI? {
        if (raw.length > 32_768 || WebNavigationPolicy.classify(raw) != NavigationDecision.INTERNAL) return null
        val uri = try { URI(raw) } catch (_: Exception) { return null }
        return uri.takeIf { it.host == HOST && it.rawFragment == null }
    }

    private fun query(uri: URI): Map<String, String>? = try {
        val pairs = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        if (pairs.map { it.first }.distinct().size != pairs.size) null else pairs.toMap()
    } catch (_: Exception) { null }

    fun isAuthorizeUrl(raw: String): Boolean {
        val uri = official(raw) ?: return false
        if (uri.rawPath != AUTHORIZE_PATH) return false
        val q = query(uri) ?: return false
        return q["os"] == "web" && q.keys.all { it in setOf("os", "device_id", "shumei_verification", "hcaptcha_token") }
    }

    fun parseCallback(raw: String): Callback? {
        val uri = official(raw) ?: return null
        if (uri.rawPath != CALLBACK_PATH) return null
        val q = query(uri) ?: return null
        if (q["provider"] != "GOOGLE" || q["code"]?.let { it != "0" } == true || q.containsKey("error")) return null
        val nonce = q["nonce"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{64}")) } ?: return null
        return Callback(nonce)
    }

    fun shouldReturnToBrowser(raw: String): Boolean {
        val uri = official(raw) ?: return false
        return uri.rawPath == CALLBACK_PATH && query(uri)?.get("provider") in setOf("APPLE", "WECHAT")
    }
}

/** Persist only attempt metadata, never authorization URLs, nonces, codes or tokens. */
class GoogleSignInAttempt(
    private val clock: () -> Long,
    var startedAt: Long? = null,
    var claimed: Boolean = false,
) {
    val active get() = startedAt?.let { clock() - it in 0..600_000L } == true
    fun begin() { startedAt = clock(); claimed = false }
    fun claim(): Boolean {
        if (!active || claimed) return false
        claimed = true
        return true
    }
    fun cancel() { startedAt = null; claimed = false }
}
