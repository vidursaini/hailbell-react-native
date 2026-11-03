package com.hailbell

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * After the customer's first message, if notifications are off, offers to let them know when the
 * team replies. Without this, a reply only reaches them if they reopen the app. Asked once per
 * install; set `Hailbell.asksForNotifications = false` if your app asks at a better moment.
 */
internal class NotifyAsk {
    enum class State { HIDDEN, ASK, SETTINGS }

    val state = MutableStateFlow(State.HIDDEN)

    private val prefs get() = Client.prefs(Hailbell.context)
    private var answered: Boolean
        get() = prefs.getBoolean("notifyAsked", false)
        set(value) { prefs.edit().putBoolean("notifyAsked", value).apply() }

    /** Called after a message of theirs reaches the server. */
    fun customerSent() {
        if (!Hailbell.asksForNotifications || answered || state.value != State.HIDDEN) return
        val context = Hailbell.context
        if (HailbellPush.canNotify(context)) return
        // Android 13 and later ask at runtime, once; turned off before that, or refused, means Settings.
        val canAsk = Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notifyRequested", false)
        state.value = if (canAsk) State.ASK else State.SETTINGS
    }

    /** True when the screen should show the system permission prompt. */
    fun allow(context: Context): Boolean {
        val ask = state.value == State.ASK
        finish()
        if (ask) {
            prefs.edit().putBoolean("notifyRequested", true).apply()
            return true
        }
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
        return false
    }

    fun finish() {
        answered = true
        state.value = State.HIDDEN
    }

    companion object {
        const val PERMISSION = Manifest.permission.POST_NOTIFICATIONS
    }
}
