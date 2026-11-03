package com.hailbell

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Campaign pushes from the dashboard's Push section. They carry `hb` (a JSON string) in the FCM data,
 * where chat replies carry `hailbell`, and `hb_a` with how the notification looks. They are data-only,
 * so the SDK shows each one itself, in the foreground and the background, confirms its delivery, and a
 * tap opens [PushOpenActivity].
 */
internal object Campaigns {
    const val CHANNEL = "hailbell_offers"
    const val OPEN_ACTION = "HAILBELL_OPEN_PUSH"

    fun parse(data: Map<String, String>): JSONObject? = parse(data["hb"], data["hb_s"])

    /** The server sends the device's subscription id beside `hb` as `hb_s`, so one `hb` serves every device. */
    fun parse(hb: String?, subscription: String?): JSONObject? =
        hb?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.has("n") }
            ?.apply { if (!has("s") && !subscription.isNullOrEmpty()) put("s", subscription) }

    /** The open and receipt pings name the notification, the device and the version that was sent. */
    fun ping(hb: JSONObject): JSONObject = JSONObject().put("n", hb.getString("n")).putOpt("s", hb.opt("s")).putOpt("v", hb.opt("v"))

    /** Shows a campaign. `look` is the `hb_a` data; `title` and `body` come from an FCM notification block. */
    fun show(context: Context, hb: JSONObject, look: JSONObject?, notificationTitle: String?, notificationBody: String?) {
        val title = notificationTitle ?: look?.optString("t")?.takeIf { it.isNotEmpty() }
        val body = notificationBody ?: look?.optString("b")?.takeIf { it.isNotEmpty() }
        // Large sends ask only a share of devices to confirm delivery (`r`), as the iOS extension does.
        if (Math.random() < hb.optDouble("r", 1.0)) {
            Hailbell.scope.launch {
                // A push can start the app before it configures Hailbell, so wait a few seconds for it.
                val client = withTimeoutOrNull(5_000) { while (Hailbell.client == null) delay(100); Hailbell.client }
                runCatching { client?.request("POST", "/v1/push/received", ping(hb), authorized = false) }
            }
        }
        // A silent push carries no text; it is for the app, not the shade.
        if (title == null && body == null) return
        if (!HailbellPush.canNotify(context)) return
        val open = Intent(context, PushOpenActivity::class.java).setAction(OPEN_ACTION).putExtra("hb", hb.toString())
        val tap = PendingIntent.getActivity(context, hb.getString("n").hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, channel(context, look?.optString("c")))
            .setSmallIcon(HailbellPush.smallIcon(context))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(tap)
        val color = look?.optString("col")?.takeIf { it.isNotEmpty() }?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
        (color ?: HailbellPush.accentColor(context))?.let(builder::setColor)
        if (look?.optString("snd") == "none") builder.setSilent(true)
        look?.optInt("cnt", -1)?.takeIf { it >= 0 }?.let(builder::setNumber)
        hb.optString("i").takeIf { it.isNotEmpty() }?.let(::image)?.let {
            builder.setLargeIcon(it).setStyle(NotificationCompat.BigPictureStyle().bigPicture(it).setSummaryText(body))
        }
        val tag = look?.optString("tag")?.takeIf { it.isNotEmpty() }
        @Suppress("MissingPermission") // checked in canNotify
        NotificationManagerCompat.from(context).notify(tag?.let { "hbpush.$it" } ?: "hbpush", if (tag != null) 0 else hb.getString("n").hashCode(), builder.build())
    }

    /** The channel the server named, when the app has it, else [HailbellPush.offersChannel]. */
    private fun channel(context: Context, named: String?): String {
        if (named.isNullOrEmpty() || Build.VERSION.SDK_INT < 26) return HailbellPush.offersChannel
        val exists = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(named) != null
        return if (exists) named else HailbellPush.offersChannel
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26 || HailbellPush.offersChannel != CHANNEL) return
        val channel = NotificationChannel(CHANNEL, L10n.text("offersChannel"), NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** FCM calls the service on a background thread, so a short blocking download is fine here. */
    private fun image(url: String): Bitmap? = runCatching {
        val http = Hailbell.client?.http?.newBuilder()?.callTimeout(8, TimeUnit.SECONDS)?.build() ?: return null
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.byteStream()?.let(BitmapFactory::decodeStream)
        }
    }.getOrNull()
}

/**
 * A tapped campaign push: records the open, then opens its link, or the app when it has none. It has
 * no screen of its own and finishes at once.
 */
class PushOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // In the background Android puts the FCM data in the extras, `hb_s` included.
        val hb = Campaigns.parse(intent.getStringExtra("hb"), intent.getStringExtra("hb_s"))
        if (hb != null) {
            Hailbell.scope.launch { runCatching { Hailbell.client?.request("POST", "/v1/push/opened", Campaigns.ping(hb), authorized = false) } }
        }
        val link = hb?.optString("u")?.takeIf { it.isNotEmpty() }
        val next = link?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(packageName).takeIf { i -> i.resolveActivity(packageManager) != null } }
            ?: link?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
            ?: packageManager.getLaunchIntentForPackage(packageName)
        next?.let { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        finish()
    }
}

/** A resource the app names in its manifest meta-data, such as the FCM default notification icon. */
internal fun Context.manifestInt(key: String): Int? = runCatching {
    packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData?.getInt(key)?.takeIf { it != 0 }
}.getOrNull()
