package com.hailbell.reactnative

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hailbell.Hailbell
import com.hailbell.HailbellPush
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The `Hailbell` native module that `@hailbell/react-native` calls. It is a thin layer over the Android
 * SDK: each function forwards to the matching `Hailbell` or `HailbellPush` call.
 */
class HailbellModule : Module() {
  private val main = Handler(Looper.getMainLooper())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  /** `purchased` callbacks for offers whose paywall JS is showing, oldest first. Main thread only. */
  private val paywalls = LinkedHashMap<String, () -> Unit>()
  /** Firebase ID token requests waiting for JS. */
  private val tokenRequests = ConcurrentHashMap<String, CompletableDeferred<String?>>()
  private var ownsPaywallHandler = false
  @Volatile private var lastPushToken: String? = null

  private val context: Context
    get() = appContext.reactContext?.applicationContext ?: throw Exceptions.ReactContextLost()

  /** Runs [block] on the main thread after anything already queued, so calls keep the order JS made them in. */
  private fun onMain(block: () -> Unit) {
    main.post(block)
  }

  override fun definition() = ModuleDefinition {
    Name("Hailbell")

    Events("onUnreadCountChange", "onPaywall", "onFirebaseTokenRequest", "onPushOpen")

    OnCreate {
      scope.launch {
        Hailbell.unreadCount.collect { sendEvent("onUnreadCountChange", mapOf("count" to it)) }
      }
    }

    OnDestroy {
      scope.cancel()
      if (ownsPaywallHandler) onMain { Hailbell.onPaywall = null }
      tokenRequests.values.forEach { it.complete(null) }
      tokenRequests.clear()
    }

    // Hailbell

    Function("configure") { appId: String, baseUrl: String? ->
      val context = context
      onMain { HailbellRN.configure(context, appId, baseUrl) }
    }

    Function("open") {
      val context = context
      onMain { HailbellRN.open(appContext.currentActivity, context) }
    }

    Function("close") {
      onMain { HailbellRN.close() }
    }

    Function("identify") { userId: String?, name: String?, email: String?, identityToken: String? ->
      onMain { Hailbell.identify(userId = userId, name = name, email = email, identityToken = identityToken) }
    }

    Function("identifyWithFirebase") { name: String?, email: String? ->
      // The SDK calls this for every new session; JS asks Firebase for a current token each time.
      onMain { Hailbell.identify(name = name, email = email, firebaseIdToken = { firebaseToken() }) }
    }

    Function("provideFirebaseToken") { requestId: String, token: String? ->
      tokenRequests.remove(requestId)?.complete(token?.takeIf { it.isNotEmpty() })
    }

    Function("setMetadata") { metadata: Map<String, String> ->
      onMain { Hailbell.setMetadata(metadata) }
    }

    Function("track") { event: String, properties: Map<String, String>? ->
      onMain { Hailbell.track(event, properties ?: emptyMap()) }
    }

    Function("setPaywallPlacements") { placements: List<String> ->
      onMain { Hailbell.setPaywallPlacements(placements) }
    }

    Function("setPaywallHandler") { enabled: Boolean ->
      onMain {
        ownsPaywallHandler = enabled
        Hailbell.onPaywall = if (!enabled) null else { placement, purchased ->
          val id = UUID.randomUUID().toString()
          paywalls[id] = purchased
          // Only the latest offers can still be bought; older callbacks are let go.
          while (paywalls.size > 20) paywalls.remove(paywalls.keys.first())
          sendEvent("onPaywall", mapOf("placement" to placement, "requestId" to id))
        }
      }
    }

    Function("paywallPurchased") { requestId: String ->
      onMain { paywalls.remove(requestId)?.invoke() }
    }

    Function("setPushToken") { token: String, _: Boolean? ->
      val clean = token.trim()
      // An APNs token (64 hex characters) can't reach an Android phone: FCM tokens always carry a colon.
      if (clean.isEmpty() || Regex("^[0-9a-fA-F]+$").matches(clean)) return@Function false
      if (clean != lastPushToken) {
        lastPushToken = clean
        onMain { Hailbell.setPushToken(clean) }
      }
      true
    }

    Function("reset") {
      onMain { Hailbell.reset() }
    }

    AsyncFunction("deleteData") Coroutine { ->
      withContext(Dispatchers.Main) { Hailbell.deleteData() }
    }

    Function("getUnreadCount") {
      Hailbell.unreadCount.value
    }

    AsyncFunction("setTeamPhoto") Coroutine { uri: String? ->
      val context = context
      val bitmap = uri?.let { withContext(Dispatchers.IO) { HailbellRN.loadImage(context, it) } }
      withContext(Dispatchers.Main) { Hailbell.teamPhoto = bitmap }
      uri == null || bitmap != null
    }

    Function("trackScreen") { name: String ->
      onMain { Hailbell.trackScreen(name) }
    }

    Function("reportBug") {
      onMain { appContext.currentActivity?.let { Hailbell.reportBug(it) } }
    }

    Function("setShakeToReport") { enabled: Boolean ->
      onMain { Hailbell.shakeToReport = enabled }
    }

    Function("setAsksForNotifications") { asks: Boolean ->
      onMain { Hailbell.asksForNotifications = asks }
    }

    Function("setDarkMode") { dark: Boolean? ->
      onMain { Hailbell.darkMode = dark }
    }

    // HailbellPush

    AsyncFunction("requestPushPermission") { _: Boolean, promise: Promise ->
      val context = context
      val permissions = appContext.permissions
      if (Build.VERSION.SDK_INT < 33 || HailbellPush.canNotify(context) || permissions == null) {
        promise.resolve(HailbellPush.canNotify(context))
        return@AsyncFunction
      }
      permissions.askForPermissions({ promise.resolve(HailbellPush.canNotify(context)) }, Manifest.permission.POST_NOTIFICATIONS)
    }

    AsyncFunction("canNotify") {
      HailbellPush.canNotify(context)
    }

    Function("setTags") { set: Map<String, String>, remove: List<String> ->
      val tags = HashMap<String, String?>(set)
      remove.forEach { tags[it] = null }
      onMain { HailbellPush.setTags(tags) }
    }

    Function("setMarketing") { enabled: Boolean ->
      onMain { HailbellPush.setMarketing(enabled) }
    }

    Function("optOut") {
      onMain { HailbellPush.optOut() }
    }

    Function("setOffersChannel") { channel: String ->
      HailbellPush.offersChannel = channel
    }

    // iOS only: on Android a tapped campaign push opens its link through the SDK's own activity.
    Function("setPushOpenHandler") { _: Boolean -> }

    // iOS only: Android has no app icon badge count to reset.
    Function("clearBadge") { _: String? -> }

    // Shows a reply that arrived in the foreground, or any campaign push (they are data-only). It can
    // download the push's image, so it runs off the main thread, as FirebaseMessagingService does.
    AsyncFunction("handleMessage") Coroutine { data: Map<String, String>, title: String?, body: String? ->
      val context = context
      withContext(Dispatchers.IO) { HailbellPush.handleMessage(context, data, title, body) }
    }

    // A tapped notification that a push library showed. Campaign pushes open through the SDK's own
    // activity and never come here; a chat reply opens the chat.
    AsyncFunction("handleTap") { userInfo: Map<String, Any?>, _: String? ->
      if (!userInfo.containsKey("hailbell")) return@AsyncFunction null
      val context = context
      onMain { HailbellRN.open(appContext.currentActivity, context) }
      mapOf("kind" to "chat")
    }
  }

  /**
   * Asks JS for a current Firebase ID token. Gives up after 15 seconds, and the session then starts
   * without one, as it does on native when the lambda returns null.
   */
  private suspend fun firebaseToken(): String? {
    val id = UUID.randomUUID().toString()
    val request = CompletableDeferred<String?>()
    tokenRequests[id] = request
    sendEvent("onFirebaseTokenRequest", mapOf("requestId" to id))
    return try {
      withTimeoutOrNull(15_000) { request.await() }
    } finally {
      tokenRequests.remove(id)
    }
  }
}
