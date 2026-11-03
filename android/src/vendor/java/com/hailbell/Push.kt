package com.hailbell

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * Push notifications for chat replies, sent through Firebase Cloud Messaging. Hailbell has no Firebase
 * dependency of its own: your `FirebaseMessagingService` forwards the token and the messages.
 *
 *     class PushService : FirebaseMessagingService() {
 *         override fun onNewToken(token: String) = Hailbell.setPushToken(token)
 *         override fun onMessageReceived(message: RemoteMessage) {
 *             if (HailbellPush.handleMessage(this, message.data, message.notification?.title, message.notification?.body)) return
 *             // your own pushes
 *         }
 *     }
 *
 * A tap on a reply opens the chat straight away, even from a closed app.
 */
object HailbellPush {
    internal const val CHANNEL = "hailbell_replies"
    internal const val OPEN_ACTION = "HAILBELL_OPEN_CHAT"
    private const val NOTIFICATION_ID = 0x4842

    private var marketing: Boolean? = null

    /**
     * The channel campaign pushes show on. Set it before [Hailbell.configure] when the app already has
     * an offers channel, and enter the same ID in the dashboard's push settings. The app then owns that
     * channel and Hailbell does not create its own.
     */
    @JvmStatic
    var offersChannel: String = Campaigns.CHANNEL

    // Null before Hailbell.configure. The app may set marketing first (from onResume, say); the value
    // is kept and sent with the next subscribe.
    private val subscriptionId: String?
        get() = if (Hailbell.isConfigured) Client.prefs(Hailbell.context).getString("push.subscription", null) else null

    /** True when the data came from Hailbell. */
    @JvmStatic
    fun isHailbell(data: Map<String, String>): Boolean = data.containsKey("hailbell") || data.containsKey("hb")

    /**
     * Shows a campaign push (always: they are data-only) or a reply that arrived while the app was open
     * (only when the chat isn't on screen; in the background Android shows replies by itself). Returns
     * false for pushes that are not Hailbell's. Call it for every message your FirebaseMessagingService
     * receives.
     */
    @JvmStatic
    fun handleMessage(context: Context, data: Map<String, String>, title: String?, body: String?): Boolean {
        Campaigns.parse(data)?.let {
            val look = data["hb_a"]?.let { a -> runCatching { JSONObject(a) }.getOrNull() }
            Campaigns.show(context, it, look, title, body)
            return true
        }
        if (!isHailbell(data)) return false
        if (ChatActivity.visible) return true
        if (!canNotify(context)) return true
        val open = Intent(context, ChatActivity::class.java).setAction(OPEN_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(smallIcon(context))
            .setContentTitle(title ?: L10n.text("supportTitle", mapOf("name" to appLabel(context))).trim())
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .apply { accentColor(context)?.let(::setColor) }
            .build()
        @Suppress("MissingPermission") // checked in canNotify
        NotificationManagerCompat.from(context).notify(data["conversationId"]?.let { "hailbell-$it" }, NOTIFICATION_ID, note)
        return true
    }

    /** Whether the user has allowed notifications. */
    @JvmStatic
    fun canNotify(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    /** Key/value data about the user, for targeting. A null value removes the tag. */
    @JvmStatic
    fun setTags(tags: Map<String, String?>) {
        val body = JSONObject()
        tags.forEach { (k, v) -> body.put(k, v ?: JSONObject.NULL) }
        post("/v1/tags", JSONObject().put("tags", body))
    }

    /** Promotional pushes on or off. Transactional ones (receipts, replies) still arrive. */
    @JvmStatic
    fun setMarketing(enabled: Boolean) {
        marketing = enabled
        val id = subscriptionId ?: return
        post("/v1/push/preferences", JSONObject().put("subscriptionId", id).put("marketing", enabled))
    }

    /** Stops Hailbell pushes to this device until the next [Hailbell.setPushToken]. */
    @JvmStatic
    fun optOut() {
        val id = subscriptionId ?: return
        post("/v1/push/unsubscribe", JSONObject().put("subscriptionId", id))
    }

    // internal

    private fun post(path: String, body: JSONObject) {
        Hailbell.scope.launch { runCatching { Hailbell.client?.request("POST", path, body) } }
    }

    internal fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val name = L10n.text("supportTitle", mapOf("name" to appLabel(context))).trim()
        val channel = NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        Campaigns.createChannel(context)
    }

    /**
     * The status bar icon: the one the app sets for FCM in its manifest
     * (`com.google.firebase.messaging.default_notification_icon`), so pushes shown by Android and by
     * Hailbell match. Without it, the app icon, which Android draws as a grey square.
     */
    internal fun smallIcon(context: Context): Int =
        context.manifestInt("com.google.firebase.messaging.default_notification_icon") ?: context.applicationInfo.icon

    /** The FCM default notification colour from the manifest, if the app sets one. */
    internal fun accentColor(context: Context): Int? =
        context.manifestInt("com.google.firebase.messaging.default_notification_color")?.let { context.getColor(it) }

    /** The chat is on screen: replies waiting in the notification shade are read. */
    internal fun clearReplies(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications.filter { it.notification.channelId == CHANNEL || it.tag?.startsWith("hailbell-") == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    /**
     * Registers (or refreshes) this device. Runs on every session, so Hailbell knows the time zone,
     * app version and the hours the app is used.
     */
    internal suspend fun subscribe(token: String) {
        val client = Hailbell.client ?: return
        val context = Hailbell.context
        val body = JSONObject()
            .put("platform", "fcm").put("token", token).put("sandbox", false)
            .put("language", Locale.getDefault().toLanguageTag())
            .put("timezone", TimeZone.getDefault().id)
            .put("appVersion", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("osVersion", Build.VERSION.RELEASE)
        marketing?.let { body.put("marketing", it) }
        runCatching { client.request("POST", "/v1/push/subscribe", body) }.getOrNull()
            ?.optStringOrNull("subscriptionId")
            ?.let { Client.prefs(context).edit().putString("push.subscription", it).apply() }
    }

    internal fun appLabel(context: Context): String =
        context.applicationInfo.loadLabel(context.packageManager).toString()
}
