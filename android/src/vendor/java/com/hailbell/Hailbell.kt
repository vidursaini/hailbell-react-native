package com.hailbell

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * In-app support chat. Replies come from Discord or the Hailbell dashboard.
 *
 *     Hailbell.configure(context, appId = "app_...")
 *     Hailbell.open(activity)
 */
@SuppressLint("StaticFieldLeak") // holds the application context only
object Hailbell {
    private val _unreadCount = MutableStateFlow(0)

    /** Replies the user has not read yet. Use it for a badge on your support button. */
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val chat = ChatModel()
    internal var client: Client? = null
        private set
    internal lateinit var context: Context
        private set

    /** False until [configure]. Calls that read [context] before then must check it. */
    internal val isConfigured: Boolean get() = ::context.isInitialized
    private var identity = Identity()
    private var pushToken: String? = null
    private var placements: List<String> = emptyList()
    private var retryJob: Job? = null
    private var retryDelay = 2L

    /**
     * Opens a paywall when the user taps an offer you sent in the chat. Call `purchased` if they buy,
     * so you can see which offers worked. With Superwall:
     *
     *     Hailbell.onPaywall = { placement, purchased ->
     *         Superwall.instance.register(placement, handler = PaywallPresentationHandler().apply {
     *             onDismiss { _, result -> if (result is PaywallResult.Purchased) purchased() }
     *         })
     *     }
     */
    var onPaywall: ((placement: String, purchased: () -> Unit) -> Unit)? = null

    /**
     * The photo of the person who replies, bundled in your app so the chat shows it at once, offline
     * too. Without it the chat loads the photo set in the dashboard.
     */
    var teamPhoto: Bitmap? = null

    /**
     * After the customer's first message, the chat offers to turn on notifications if they're off,
     * so they see the reply. Set to false if your app asks for notification permission itself.
     */
    var asksForNotifications = true

    /**
     * Whether the chat is dark. Null (the default) follows the phone's setting. Set it when your app
     * has its own fixed look, so the chat matches the app rather than the system: an always-dark app
     * sets true.
     */
    var darkMode: Boolean? = null

    /** Call once at launch, from `Application.onCreate`, before anything else. */
    @JvmStatic
    @JvmOverloads
    fun configure(context: Context, appId: String, baseUrl: String = "https://api.hailbell.com") {
        this.context = context.applicationContext
        client = Client(this.context, appId, baseUrl)
        chat.attach()
        HailbellPush.createChannel(this.context)
        scope.launch { refreshSession() }
    }

    /**
     * Tells Hailbell who the user is. Pass `identityToken` (an HS256 JWT from your backend, signed with
     * the app's identity secret) to link the chat to the account across devices. Without it the name and
     * email are shown to you but the chat stays tied to this install.
     */
    @JvmStatic
    @JvmOverloads
    fun identify(userId: String? = null, name: String? = null, email: String? = null, identityToken: String? = null) {
        identity = identity.copy(userId = userId, name = name, email = email, identityToken = identityToken, firebaseIdToken = null)
        scope.launch { refreshSession() }
    }

    /**
     * Links the chat to a Firebase Auth user, so it follows them to a reinstall or a new phone. Hailbell
     * checks the token against Google's keys and the Firebase project set in the dashboard's push
     * settings, and the Firebase uid becomes the user's id. Pass a function such as
     * `{ Firebase.auth.currentUser?.getIdToken(false)?.await()?.token }`: ID tokens last an hour, so
     * Hailbell asks for a fresh one each time it starts a session. Call [reset] on sign-out.
     */
    fun identify(name: String? = null, email: String? = null, firebaseIdToken: suspend () -> String?) {
        identity = identity.copy(userId = null, identityToken = null, name = name, email = email, firebaseIdToken = firebaseIdToken)
        scope.launch { refreshSession() }
    }

    /** Extra context shown next to the conversation, e.g. mapOf("plan" to "free"). */
    @JvmStatic
    fun setMetadata(metadata: Map<String, String>) {
        identity = identity.copy(metadata = identity.metadata + metadata)
        scope.launch { refreshSession() }
    }

    /** Clears the identity, for example on sign-out. The next session starts as this install's anonymous chat. */
    @JvmStatic
    fun reset() {
        identity = Identity()
        chat.reset()
        scope.launch { refreshSession() }
    }

    /**
     * Deletes this user's chat, images and details from Hailbell, for example when they delete their
     * account in your app. It also clears [identify], so the next session starts a fresh, empty,
     * anonymous chat. Call [identify] again if the person stays signed in to a new account.
     */
    suspend fun deleteData() {
        val client = client ?: return
        client.deleteData()
        // Forget who they were too. Otherwise the next session signs the same person straight back in
        // and recreates the contact, name and email that were just deleted.
        identity = Identity(metadata = identity.metadata)
        chat.reset()
        refreshSession()
    }

    /** Records a signal such as "paywall_decline". Signals show next to the chat and drive nudges later. */
    @JvmStatic
    @JvmOverloads
    fun track(event: String, properties: Map<String, String> = emptyMap()) {
        scope.launch { runCatching { client?.track(event, properties) } }
    }

    /** The paywall placements an offer can open, so you can pick them by name in the dashboard and Discord. */
    @JvmStatic
    fun setPaywallPlacements(placements: List<String>) {
        this.placements = placements
        scope.launch { runCatching { client?.reportPlacements(placements) } }
    }

    /** Pass the token from `FirebaseMessagingService.onNewToken`, and the current one at launch. */
    @JvmStatic
    fun setPushToken(token: String) {
        pushToken = token
        scope.launch {
            runCatching { client?.registerPush(token) }
            HailbellPush.subscribe(token)
        }
    }

    /** Opens the chat over the given activity. */
    @JvmStatic
    fun open(activity: Activity) {
        activity.startActivity(Intent(activity, ChatActivity::class.java))
    }

    // internal

    private val sessionGeneration = java.util.concurrent.atomic.AtomicInteger()

    internal fun setUnread(count: Int) { _unreadCount.value = count }

    internal suspend fun refreshSession() {
        val client = client ?: return
        // configure, setMetadata and identify each start a session, often in the same instant at launch.
        // Only the newest may win: an older anonymous session finishing last would send the signed-in
        // person's messages to the install's anonymous chat.
        val generation = sessionGeneration.incrementAndGet()
        try {
            val session = client.startSession(identity)
            if (generation != sessionGeneration.get()) return
            client.token = session.token
            _unreadCount.value = session.unread
            chat.sessionStarted(session)
            pushToken?.let { token ->
                runCatching { client.registerPush(token) }
                HailbellPush.subscribe(token)
            }
            if (placements.isNotEmpty()) runCatching { client.reportPlacements(placements) }
            retryDelay = 2
        } catch (e: Exception) {
            if (generation != sessionGeneration.get()) return
            // The one place a session failure shows up in logcat, for example a 401 for a Firebase
            // token from the wrong project.
            android.util.Log.w("Hailbell", "Session failed: ${(e as? ApiError)?.status ?: e.javaClass.simpleName} ${e.message}", e.cause)
            chat.failed(e)
            scheduleRetry(e)
        }
    }

    /** The chat opened while offline: try now instead of waiting for the next retry. */
    internal fun reconnectNow() {
        retryJob?.cancel()
        retryJob = null
        retryDelay = 2
        scope.launch { refreshSession() }
    }

    /**
     * Offline or the server is down: try again after 2, 4, 8… up to 60 seconds. A request the server
     * refused (a wrong App ID, a blocked user) is not retried.
     */
    private fun scheduleRetry(error: Exception) {
        if (error is ApiError && error.status in 400..499 && error.status != 408 && error.status != 429) return
        if (retryJob != null) return
        val wait = retryDelay
        retryDelay = minOf(retryDelay * 2, 60)
        retryJob = scope.launch {
            delay(wait * 1000)
            retryJob = null
            refreshSession()
        }
    }
}
