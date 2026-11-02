package dev.snowseek.app.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class WebNavigationPolicyTest {
    @Test fun officialHttpsPagesStayInsideWebView() {
        listOf(
            "https://chat.deepseek.com/",
            "https://chat.deepseek.com/sign_in?next=%2F",
            "https://accounts.deepseek.com/login",
            "https://CHAT.DEEPSEEK.COM:443/#chat",
        ).forEach { url -> assertEquals(url, NavigationDecision.INTERNAL, WebNavigationPolicy.classify(url)) }
    }

    @Test fun unrelatedAndDeceptiveHostsNeverLoadInsideWebView() {
        listOf(
            "https://accounts.google.com/o/oauth2/auth",
            "https://appleid.apple.com/auth/authorize",
            "https://chat.deepseek.com.attacker.example/",
            "https://notdeepseek.com/",
            "https://deepseek.com.attacker.example/",
        ).forEach { url -> assertEquals(url, NavigationDecision.EXTERNAL_BROWSER, WebNavigationPolicy.classify(url)) }
    }

    @Test fun rejectsCredentialUrlsEvenOnOfficialHost() {
        listOf(
            "https://user:password@chat.deepseek.com/",
            "https://chat.deepseek.com@attacker.example/",
            "https://%75ser@chat.deepseek.com/",
            "https://@chat.deepseek.com/",
        ).forEach { url -> assertEquals(url, NavigationDecision.BLOCKED, WebNavigationPolicy.classify(url)) }
    }

    @Test fun rejectsNonHttpsMalformedAndNonstandardPortUrls() {
        listOf(
            "http://chat.deepseek.com/", "javascript:alert(1)", "file:///data/data/private",
            "content://contacts/1", "intent://chat.deepseek.com/#Intent;end", "data:text/html,hi",
            "//chat.deepseek.com/", "https:", "https:///chat.deepseek.com", "",
            " https://chat.deepseek.com", "https://chat.deepseek.com\\@attacker.example",
            "https://chat.deepseek.com:8443/", "https://chat.deepseek.com./",
            "https://chat.deepseek.com/%0A", "https://chat.deepseek.com/\n",
            "https://chat.deepseek.com:999999999999999/",
        ).forEach { url -> assertEquals(url, NavigationDecision.BLOCKED, WebNavigationPolicy.classify(url)) }
    }
    @Test fun captchaHttpsFramesMayLoadWhileExternalMainPagesRequireBrowser() {
        assertEquals(true, WebNavigationPolicy.mayLoadInWebView("https://captcha.example/challenge", isMainFrame = false))
        assertEquals(false, WebNavigationPolicy.mayLoadInWebView("https://captcha.example/challenge", isMainFrame = true))
        assertEquals(true, WebNavigationPolicy.mayLoadInWebView("https://chat.deepseek.com/", isMainFrame = true))
        listOf("javascript:alert(1)", "http://captcha.example/", "https://user:pass@captcha.example/").forEach { url ->
            assertEquals(url, false, WebNavigationPolicy.mayLoadInWebView(url, isMainFrame = false))
        }
    }}
