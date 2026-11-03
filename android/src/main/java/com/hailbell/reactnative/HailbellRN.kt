package com.hailbell.reactnative

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.hailbell.ChatActivity
import com.hailbell.Hailbell
import com.hailbell.HailbellPush
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL

/** Glue between the React Native module and the Android SDK. Call everything here on the main thread. */
internal object HailbellRN {
  private const val TAG = "Hailbell"
  const val DEFAULT_BASE_URL = "https://api.hailbell.com"

  /** The App ID and server Hailbell was configured with, so a second identical call does nothing. */
  private var configured: Pair<String, String>? = null
  private var chat: WeakReference<Activity>? = null
  private var lifecycleRegistered = false

  fun onApplicationCreate(application: Application) {
    watchChat(application)
    val meta = runCatching {
      application.packageManager.getApplicationInfo(application.packageName, PackageManager.GET_META_DATA).metaData
    }.getOrNull() ?: return
    // Before configure: the SDK reads it when it creates its channels.
    meta.getString("com.hailbell.OFFERS_CHANNEL")?.takeIf { it.isNotEmpty() }?.let { HailbellPush.offersChannel = it }
    val appId = meta.getString("com.hailbell.APP_ID")?.takeIf { it.isNotEmpty() } ?: return
    configure(application, appId, meta.getString("com.hailbell.BASE_URL"))
  }

  fun configure(context: Context, appId: String, baseUrl: String?) {
    val base = baseUrl?.takeIf { it.isNotEmpty() } ?: DEFAULT_BASE_URL
    if (appId.isEmpty()) {
      Log.w(TAG, "configure: the App ID is empty.")
      return
    }
    if (configured == appId to base) return
    if (configured != null) Log.w(TAG, "configure: switching to App ID $appId. Configure once, with one App ID.")
    configured = appId to base
    (context.applicationContext as? Application)?.let(::watchChat)
    Hailbell.configure(context, appId, base)
  }

  /** Opens the chat over the current activity, or in a new task when there is none (a headless start). */
  fun open(activity: Activity?, context: Context) {
    if (chat?.get()?.isFinishing == false) return
    if (activity != null && !activity.isFinishing) {
      Hailbell.open(activity)
    } else {
      context.startActivity(Intent(context, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }

  /** Closes the chat if it is open, for example before showing a paywall drawn in React Native. */
  fun close() {
    chat?.get()?.takeIf { !it.isFinishing }?.finish()
  }

  /** Keeps a weak reference to the open chat activity, for [open] and [close]. */
  private fun watchChat(application: Application) {
    if (lifecycleRegistered) return
    lifecycleRegistered = true
    application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is ChatActivity) chat = WeakReference(activity)
      }
      override fun onActivityDestroyed(activity: Activity) {
        if (chat?.get() === activity) chat = null
      }
      override fun onActivityStarted(activity: Activity) {}
      override fun onActivityResumed(activity: Activity) {}
      override fun onActivityPaused(activity: Activity) {}
      override fun onActivityStopped(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    })
  }

  /**
   * Loads the team photo from what `Image.resolveAssetSource` returns: an http URL from Metro in
   * development, or a drawable resource name in a release build. Call off the main thread.
   */
  fun loadImage(context: Context, uri: String): Bitmap? = runCatching {
    val parsed = Uri.parse(uri)
    when (parsed.scheme?.lowercase()) {
      "http", "https" -> {
        val connection = URL(uri).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
          if (connection.responseCode >= 400) null else connection.inputStream.use(BitmapFactory::decodeStream)
        } finally {
          connection.disconnect()
        }
      }
      "file" -> BitmapFactory.decodeFile(parsed.path)
      "content", "android.resource" -> context.contentResolver.openInputStream(parsed)?.use(BitmapFactory::decodeStream)
      else -> {
        val res = context.resources
        @Suppress("DiscouragedApi") // asset names from the JS bundle are only known at run time
        val id = res.getIdentifier(uri, "drawable", context.packageName).takeIf { it != 0 }
          ?: res.getIdentifier(uri, "raw", context.packageName)
        if (id == 0) null else BitmapFactory.decodeResource(res, id)
      }
    }
  }.getOrNull()
}
