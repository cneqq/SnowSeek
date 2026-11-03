package dev.snowseek.app.auth

import android.content.Context
import android.os.SystemClock
import java.util.UUID

object GoogleBrowserAttempt {
    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("google_browser_attempt", Context.MODE_PRIVATE)
    private fun load(context: Context): GoogleSignInAttempt {
        val prefs = preferences(context)
        return GoogleSignInAttempt({ SystemClock.elapsedRealtime() }, prefs.getLong("started", -1).takeIf { it >= 0 }, prefs.getBoolean("claimed", false))
    }
    @Synchronized fun begin(context: Context): String {
        val attempt = GoogleSignInAttempt(clock = { SystemClock.elapsedRealtime() }).apply { begin() }
        val id = UUID.randomUUID().toString()
        check(preferences(context).edit().putLong("started", attempt.startedAt!!).putBoolean("claimed", false).putString("id", id).commit())
        return id
    }
    @Synchronized fun claim(context: Context): String? {
        val attempt = load(context)
        if (!attempt.claim()) return null
        val prefs = preferences(context)
        val id = prefs.getString("id", null) ?: return null
        if (!prefs.edit().putBoolean("claimed", true).commit()) return null
        return id
    }
    @Synchronized fun canConfirm(context: Context, id: String) = load(context).let { it.active && it.claimed } && preferences(context).getString("id", null) == id
    @Synchronized fun cancel(context: Context) { check(preferences(context).edit().clear().commit()) }
    @Synchronized fun cancelIfOwned(context: Context, id: String?) {
        if (id != null && preferences(context).getString("id", null) == id) cancel(context)
    }
}
