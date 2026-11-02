package dev.snowseek.app.auth

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

enum class NavigationDecision { INTERNAL, EXTERNAL_BROWSER, BLOCKED }

/** External HTTPS URLs require explicit browser confirmation; they never load in WebView. */
object WebNavigationPolicy {
    fun mayLoadInWebView(url: String, isMainFrame: Boolean): Boolean {
        if (!isMainFrame && (url == "about:blank" || isHttpsBlob(url))) return true
        return when (classify(url)) {
        NavigationDecision.INTERNAL -> true
        NavigationDecision.EXTERNAL_BROWSER -> !isMainFrame
        NavigationDecision.BLOCKED -> false
        }
    }

    /** Embedded resources are not top-level navigation. WebView applies origin isolation and CSP. */
    fun mayLoadResource(url: String): Boolean = url == "about:blank" ||
        url.startsWith("data:", ignoreCase = true) || isHttpsBlob(url) || classify(url) != NavigationDecision.BLOCKED

    private fun isHttpsBlob(url: String): Boolean = url.startsWith("blob:", ignoreCase = true) &&
        classify(url.substring(5)) != NavigationDecision.BLOCKED

    fun classify(url: String): NavigationDecision {
        // Avoid differences between URI parsing and browser URL normalization.
        if (url.any { it <= ' ' || it == '\\' || it == '\u007f' } || encodedControl.containsMatchIn(url)) {
            return NavigationDecision.BLOCKED
        }
        val uri = try { URI(url) } catch (_: URISyntaxException) { return NavigationDecision.BLOCKED }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.isOpaque || uri.rawUserInfo != null) {
            return NavigationDecision.BLOCKED
        }
        val host = uri.host?.lowercase(Locale.ROOT) ?: return NavigationDecision.BLOCKED
        if (host.endsWith('.') || uri.port !in listOf(-1, 443)) return NavigationDecision.BLOCKED
        return if (host == "deepseek.com" || host.endsWith(".deepseek.com")) {
            NavigationDecision.INTERNAL
        } else {
            NavigationDecision.EXTERNAL_BROWSER
        }
    }

    private val encodedControl = Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE)
}
