package dev.snowseek.app.auth

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WebViewDebugLogTest {
    @Test fun urlKeepsResourcePathButRemovesCredentialsQueryValuesAndFragment() {
        val raw = "https://fake-user:fake-password@chat.deepseek.com/static/app.js?code=fake-code&next=%2Fprivate#fake-fragment"
        val safe = DebugRedactor.url(raw)
        assertTrue(safe.startsWith("https://chat.deepseek.com/static/app.js?code="))
        listOf("fake-user", "fake-password", "fake-code", "%2Fprivate", "fake-fragment").forEach { assertFalse(safe.contains(it)) }
        assertTrue(safe.contains("next=[redacted]"))
        assertEquals(safe, DebugRedactor.text(safe))
        assertEquals("data:[redacted]", DebugRedactor.url("data:text/html,private-data"))
        assertFalse(DebugRedactor.url("blob:https://chat.deepseek.com/id?token=fake-token").contains("fake-token"))
    }

    @Test fun knownSecretsAreRedactedBeforeTheyCanBeStoredOrCopied() {
        val raw = """Authorization: Bearer fake-bearer
            |Cookie: session=fake-cookie; other=fake-value
            |{"password":"fake-pass", "userToken":"fake-token", "refresh_token":"fake-refresh", "prompt":"private question"}
            |account test@example.invalid eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.fakeSignature
        """.trimMargin()
        val log = WebViewDebugLog(true)
        log.console("ERROR", raw, "https://chat.deepseek.com/app.js?key=fake-key", 17)
        val stored = log.state.value.entries.single().message
        listOf("fake-bearer", "fake-cookie", "fake-value", "fake-pass", "fake-token", "fake-refresh", "private question", "test@example.invalid", "eyJhbGci", "fake-key")
            .forEach { assertFalse(it, stored.contains(it)); assertFalse(it, log.export().contains(it)) }
        assertTrue(stored, stored.contains("app.js?key=[redacted] · line 17"))
    }

    @Test fun consoleDiagnosticsKeepErrorDetailsAndRealSourceLine() {
        val log = WebViewDebugLog(true)
        log.console("ERROR", "Uncaught SyntaxError: Unexpected token '?'", "https://fe-static.deepseek.com/chat/static/app.abc123.js", 123)
        val line = log.state.value.entries.single()
        assertEquals(WebDebugTab.Console, line.tab)
        assertTrue(line.message.contains("Uncaught SyntaxError: Unexpected token '?'"))
        assertTrue(line.message, line.message.contains("app.abc123.js · line 123"))
        assertTrue(DebugRedactor.text("TLS_ERROR code=3 ERR_NAME_NOT_RESOLVED -2").contains("code=3"))
    }

    @Test fun requestsNeverInferSuccessfulHttpStatusAndAppBlocksAreSeparate() {
        val log = WebViewDebugLog(true)
        log.request("GET", "https://chat.deepseek.com/app.js", false, true)
        log.request("GET", "http://example.invalid/app.js", false, false)
        log.httpError("GET", "https://chat.deepseek.com/app.js", 403, "Forbidden", false)
        val lines = log.state.value.entries.map { it.message }
        assertTrue(lines[0].contains("HTTP ? (not exposed)"))
        assertFalse(lines.any { it.contains("200") })
        assertTrue(lines[1].contains("BLOCKED_BY_APP (no network status)"))
        assertTrue(lines[2].contains("HTTP_ERROR 403 Forbidden"))
    }

    @Test fun boundedBufferDropsOldestAndClearPreservesLiveMetadata() {
        val log = WebViewDebugLog(true, maxEntries = 3, maxCharacters = 4_096)
        log.environment("test WebView 123")
        log.location("https://chat.deepseek.com/sign_in?code=private-code", 70)
        repeat(6) { log.add(WebDebugTab.WebView, "event $it") }
        assertEquals(listOf("event 3", "event 4", "event 5"), log.state.value.entries.map { it.message })
        assertEquals(3, log.state.value.dropped)
        repeat(4) { log.add(WebDebugTab.Console, "message $it " + "!".repeat(2_000)) }
        assertTrue(log.state.value.entries.sumOf { it.message.length } <= 4_096)
        log.clear()
        assertTrue(log.state.value.entries.isEmpty())
        assertEquals(0, log.state.value.dropped)
        assertEquals(70, log.state.value.progress)
        assertEquals("test WebView 123", log.state.value.environment)
        assertFalse(log.export().contains("private-code"))
    }

    @Test fun backgroundRequestCallbacksAndUiCallbacksDoNotLoseOrDuplicateEntries() {
        val log = WebViewDebugLog(true)
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(4) { worker -> pool.submit { repeat(100) { log.add(WebDebugTab.Network, "$worker event $it") } } }
        } finally { pool.shutdown() }
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        val entries = log.state.value.entries
        assertEquals(400, entries.size)
        assertEquals((1L..400L).toList(), entries.map { it.id })
        assertEquals(400, entries.map { it.message }.toSet().size)
    }

    @Test fun exportContainsEveryTabAndDisabledLoggerCollectsNothing() {
        val log = WebViewDebugLog(true)
        WebDebugTab.entries.forEach { log.add(it, "test ${it.name}") }
        val exported = log.export()
        WebDebugTab.entries.forEach { assertTrue(exported.contains("[${it.name}]")) }
        val disabled = WebViewDebugLog(false)
        disabled.console("ERROR", "private", "https://example.invalid", 1)
        disabled.location("https://example.invalid")
        disabled.environment("private")
        assertEquals(WebDebugState(), disabled.state.value)
    }
}
