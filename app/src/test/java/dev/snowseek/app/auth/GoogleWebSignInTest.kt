package dev.snowseek.app.auth

import org.junit.Assert.*
import org.junit.Test

class GoogleWebSignInTest {
    @Test fun acceptsOnlyExactOfficialGoogleReturnAndNeverPrintsNonce() {
        val nonce = "a".repeat(64)
        val result = GoogleWebSignIn.parseCallback("https://chat.deepseek.com/authorized?provider=GOOGLE&nonce=$nonce&new=0&case=1")
        assertNotNull(result)
        assertEquals(nonce, result!!.nonce)
        assertFalse(result.toString().contains(nonce))
        listOf(
            "http://chat.deepseek.com/authorized?provider=GOOGLE&nonce=$nonce",
            "https://chat.deepseek.com.attacker.example/authorized?provider=GOOGLE&nonce=$nonce",
            "https://user@chat.deepseek.com/authorized?provider=GOOGLE&nonce=$nonce",
            "https://chat.deepseek.com:8443/authorized?provider=GOOGLE&nonce=$nonce",
            "https://chat.deepseek.com/authorized/extra?provider=GOOGLE&nonce=$nonce",
            "https://chat.deepseek.com/authorized?provider=APPLE&nonce=$nonce",
            "https://chat.deepseek.com/authorized?provider=GOOGLE&nonce=$nonce&nonce=bad",
            "https://chat.deepseek.com/authorized?provider=GOOGLE&nonce=$nonce#extra",
            "https://chat.deepseek.com/authorized?provider=GOOGLE&nonce=short",
        ).forEach { assertNull(it.substringBefore('?'), GoogleWebSignIn.parseCallback(it)) }
    }

    @Test fun acceptsUnmodifiedOfficialAuthorizeUrlWithoutAllowingArbitraryGoogleClients() {
        val start = "https://chat.deepseek.com/api/v0/users/oauth/google/authorize?os=web&device_id=device&hcaptcha_token=proof&shumei_verification=%7B%7D"
        assertTrue(GoogleWebSignIn.isAuthorizeUrl(start))
        assertFalse(GoogleWebSignIn.isAuthorizeUrl(start.replace("/google/", "/apple/")))
        assertFalse(GoogleWebSignIn.isAuthorizeUrl(start.replace("chat.deepseek.com", "accounts.google.com")))
        assertFalse(GoogleWebSignIn.isAuthorizeUrl(start + "&redirect_uri=https://attacker.example"))
        assertFalse(GoogleWebSignIn.isAuthorizeUrl(start + "&os=web"))
    }

    @Test fun attemptsExpireAndReturnCanBeClaimedOnlyOnce() {
        var time = 100L
        val attempt = GoogleSignInAttempt(clock = { time })
        assertFalse(attempt.claim())
        attempt.begin()
        assertTrue(attempt.claim());assertFalse(attempt.claim())
        attempt.begin();time += 600_001
        assertFalse(attempt.claim())
        attempt.begin();attempt.cancel();assertFalse(attempt.claim())
    }

    @Test fun nonGoogleOfficialCallbacksReturnToBrowserWithoutBecomingGoogleAttempts() {
        val url = "https://chat.deepseek.com/authorized?provider=APPLE&nonce=" + "b".repeat(64)
        assertTrue(GoogleWebSignIn.shouldReturnToBrowser(url))
        assertNull(GoogleWebSignIn.parseCallback(url))
        assertFalse(GoogleWebSignIn.shouldReturnToBrowser(url.replace("APPLE", "GOOGLE")))
        assertFalse(GoogleWebSignIn.shouldReturnToBrowser(url.replace("chat.deepseek.com", "attacker.example")))
    }
}
