package com.hailbell

import android.graphics.Bitmap
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Holds the conversation and the live socket. One per app, owned by [Hailbell]. Main thread only. */
internal class ChatModel {
    data class Pending(val id: String, val body: String, val images: List<Bitmap> = emptyList(), val failed: Boolean = false)

    enum class Delivery { SENT, DELIVERED, SEEN }

    val messages = MutableStateFlow<List<Message>>(emptyList())
    val pending = MutableStateFlow<List<Pending>>(emptyList())
    val appName = MutableStateFlow("")
    val assistant = MutableStateFlow<Assistant?>(null)
    val appearance = MutableStateFlow(Appearance())
    val team = MutableStateFlow<List<TeamMember>>(emptyList())
    val hasMore = MutableStateFlow(false)
    /** When you last read the chat, and when the customer's messages last reached you, for the ticks under their messages. */
    val agentReadAt = MutableStateFlow(0.0)
    val agentDeliveredAt = MutableStateFlow(0.0)
    val agentTyping = MutableStateFlow(false)
    /** When someone on the team last did something (ms), for the dot on their photo. Null until the server says. */
    val agentActiveAt = MutableStateFlow<Double?>(null)
    val errorText = MutableStateFlow<String?>(null)
    val notifyAsk = NotifyAsk()

    private val scope get() = Hailbell.scope
    private val client get() = Hailbell.client
    private var socket: WebSocket? = null
    private var pingJob: Job? = null
    private var typingClear: Job? = null
    private var typingSentAt = 0L
    private var visible = false
    private var conversationId: String? = null

    fun attach() {
        restore()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = backgrounded()
            override fun onStart(owner: LifecycleOwner) = foregrounded()
        })
    }

    /**
     * The ticks under the customer's message: one once we have it, two once it reached you, blue
     * once you read it. Only the last of a run of their messages shows them.
     */
    fun delivery(m: Message, list: List<Message>, readAt: Double, deliveredAt: Double): Delivery? {
        if (!m.isMine) return null
        val i = list.indexOfFirst { it.id == m.id }
        if (i < 0 || (i + 1 < list.size && list[i + 1].isMine)) return null
        if (readAt >= m.createdAt) return Delivery.SEEN
        return if (deliveredAt >= m.createdAt) Delivery.DELIVERED else Delivery.SENT
    }

    /** The person who answers, when the dashboard shows their photo. */
    fun teamPhoto(appearance: Appearance, team: List<TeamMember>): String? {
        if (appearance.teamPhotos == false) return null
        return team.firstOrNull()?.avatarUrl?.let { client?.resolve(it) }
    }

    fun resolve(path: String): String? = client?.resolve(path)

    /** The first and last message of a run from the same sender, for names and photos. */
    fun startsRun(m: Message, list: List<Message>): Boolean {
        val i = list.indexOfFirst { it.id == m.id }
        return i <= 0 || !sameSender(list[i - 1], m)
    }

    fun endsRun(m: Message, list: List<Message>): Boolean {
        val i = list.indexOfFirst { it.id == m.id }
        return i < 0 || i + 1 >= list.size || !sameSender(m, list[i + 1])
    }

    /** Online while the team was active in the last 5 minutes. */
    fun agentOnline(now: Long): Boolean? = agentActiveAt.value?.let { now - it < 5 * 60 * 1000 }

    private fun sameSender(a: Message, b: Message) = a.author == b.author && (a.isMine || a.authorName == b.authorName)

    fun sessionStarted(session: Session) {
        appName.value = session.appName
        assistant.value = session.assistant
        appearance.value = session.appearance ?: Appearance()
        team.value = session.team
        agentActiveAt.value = session.agentActiveAt
        errorText.value = null
        if (conversationId != session.conversationId) {
            // A different person (signed in or out): show their chat, starting from the cached copy.
            disconnect()
            conversationId = session.conversationId
            messages.value = cached(session.conversationId)
            hasMore.value = false
            agentReadAt.value = 0.0
            agentDeliveredAt.value = 0.0
        }
        saveSnapshot()
        // The latest page, not just what is newer: cached cards may have changed state since.
        // The socket only runs while the chat is on screen, so replies at other times arrive as pushes.
        scope.launch { loadHistory(latest = true); if (visible) connect() }
    }

    fun failed(error: Exception) { errorText.value = error.message }

    fun reset() {
        conversationId?.let { cacheFile(it).delete() }
        snapshotFile().delete()
        conversationId = null
        messages.value = emptyList()
        pending.value = emptyList()
        hasMore.value = false
        agentReadAt.value = 0.0
        agentDeliveredAt.value = 0.0
        disconnect()
    }

    /** Loads the page of messages before the oldest one shown. */
    fun loadEarlier() {
        val client = client ?: return
        val first = messages.value.firstOrNull() ?: return
        scope.launch {
            try {
                val page = client.messages(before = first.createdAt)
                page.messages.forEach(::insert)
                hasMore.value = page.hasMore ?: false
            } catch (e: Exception) {
                errorText.value = e.message
            }
        }
    }

    /** Tells you in the dashboard that the customer is typing, at most every 3 seconds. */
    fun typing() {
        val socket = socket ?: return
        val now = System.currentTimeMillis()
        if (now - typingSentAt <= 3000) return
        typingSentAt = now
        socket.send("""{"type":"typing"}""")
    }

    // offline copy, so the chat opens instantly and shows history without a connection

    private fun cacheFile(conversationId: String): File {
        val safe = conversationId.filter { it.isLetterOrDigit() || it == '_' }
        return File(Hailbell.context.cacheDir, "hailbell-$safe.json")
    }

    private fun cached(conversationId: String): List<Message> = runCatching {
        JSONArray(cacheFile(conversationId).readText()).objects().map(Message::parse)
    }.getOrDefault(emptyList())

    private fun snapshotFile() = File(Hailbell.context.cacheDir, "hailbell-session.json")

    /** What the last session said about the chat, so it opens with its name and history while offline. */
    private fun saveSnapshot() {
        val id = conversationId ?: return
        val snap = JSONObject().put("conversationId", id).put("appName", appName.value)
            .putOpt("assistant", assistant.value?.json()).put("appearance", appearance.value.json())
            .put("team", JSONArray(team.value.map { it.json() }))
        runCatching { snapshotFile().writeText(snap.toString()) }
    }

    private fun restore() {
        if (conversationId != null) return
        val snap = runCatching { JSONObject(snapshotFile().readText()) }.getOrNull() ?: return
        conversationId = snap.optStringOrNull("conversationId") ?: return
        appName.value = snap.optString("appName")
        assistant.value = snap.optJSONObject("assistant")?.let(Assistant::parse)
        appearance.value = snap.optJSONObject("appearance")?.let(Appearance::parse) ?: Appearance()
        team.value = snap.optJSONArray("team").objects().map(TeamMember::parse)
        messages.value = cached(conversationId!!)
    }

    private fun saveCache() {
        val id = conversationId ?: return
        val json = JSONArray(messages.value.takeLast(100).map { it.json() })
        runCatching { cacheFile(id).writeText(json.toString()) }
    }

    // visibility, used for read receipts

    fun appeared() {
        visible = true
        markRead()
        // Replies that arrived while the chat was closed came as pushes, not over the socket, so
        // fetch them before reconnecting.
        if (client?.token == null) Hailbell.reconnectNow()
        else if (socket == null) scope.launch { loadHistory(); connect() }
    }

    fun disappeared() {
        visible = false
        disconnect()
    }

    /**
     * The app went to the background. Close the socket so the server stops counting the customer as
     * in the chat and sends replies as pushes.
     */
    private fun backgrounded() = disconnect()

    /** Back in front with the chat on screen: catch up on what arrived meanwhile, then reconnect. */
    private fun foregrounded() {
        if (!visible) return
        scope.launch { loadHistory(); connect(); markRead() }
    }

    private fun markRead() {
        if (!visible) return
        val client = client ?: return
        Hailbell.setUnread(0)
        HailbellPush.clearReplies(Hailbell.context)
        scope.launch { runCatching { client.markRead() } }
    }

    // sending

    fun send(raw: String, images: List<Bitmap> = emptyList()) {
        val text = raw.trim()
        if (text.isEmpty() && images.isEmpty()) return
        val item = Pending(UUID.randomUUID().toString().uppercase(), text, images)
        pending.update { it + item }
        deliver(item)
    }

    fun retry(item: Pending) {
        if (pending.value.none { it.id == item.id }) return
        val fresh = item.copy(failed = false)
        pending.update { list -> list.map { if (it.id == item.id) fresh else it } }
        deliver(fresh)
    }

    private fun deliver(item: Pending) {
        val client = client ?: return
        val token = client.token
        scope.launch {
            try {
                val ids = item.images.mapNotNull { image ->
                    val jpeg = ImageEncoder.chatJpeg(image) ?: return@mapNotNull null
                    client.upload(jpeg.data, jpeg.width, jpeg.height)
                }
                val message = client.send(item.body, ids, item.id)
                pending.update { list -> list.filterNot { it.id == item.id } }
                insert(message)
                saveCache()
                errorText.value = null
                notifyAsk.customerSent()
            } catch (e: Exception) {
                pending.update { list -> list.map { if (it.id == item.id) it.copy(failed = true) else it } }
                showError(e, token)
            }
        }
    }

    /**
     * The customer tapped a card's button ("tapped"), chose "Not now" ("dismissed") or bought from an
     * offer ("purchased"). States only move forward.
     */
    fun cardAction(message: Message, action: String) {
        val rank = mapOf("sent" to 0, "dismissed" to 1, "tapped" to 2, "purchased" to 3)
        val current = messages.value.firstOrNull { it.id == message.id } ?: return
        val card = current.card ?: return
        if ((rank[action] ?: 0) <= (rank[card.state] ?: 0)) return
        messages.update { list -> list.map { if (it.id == message.id) it.copy(card = card.copy(state = action)) else it } }
        saveCache()
        val client = client ?: return
        scope.launch { runCatching { client.cardAction(message.id, action) } }
    }

    /** Adds a message, or updates the copy we have (a card's state can change after it was cached). */
    private fun insert(message: Message) {
        val list = messages.value
        val i = list.indexOfFirst { it.id == message.id }
        if (i >= 0) {
            if (list[i] != message) messages.value = list.toMutableList().also { it[i] = message }
            return
        }
        messages.value = (list + message).sortedBy { it.createdAt }
        message.clientId?.let { cid -> pending.update { p -> p.filterNot { it.id == cid } } }
    }

    /**
     * What arrived since the newest message we have, or with [latest] the newest page. A newest page
     * that does not reach back to our copy replaces it, so the chat never shows a gap.
     */
    private suspend fun loadHistory(latest: Boolean = false) {
        val client = client ?: return
        val token = client.token
        try {
            val page = client.messages(after = if (latest) 0.0 else messages.value.lastOrNull()?.createdAt ?: 0.0)
            val oldest = page.messages.firstOrNull()?.createdAt
            if (latest && page.hasMore == true && oldest != null && oldest > (messages.value.lastOrNull()?.createdAt ?: 0.0)) {
                messages.value = emptyList()
            }
            if (messages.value.isEmpty() || latest) hasMore.value = page.hasMore ?: false
            page.agentReadAt?.let { agentReadAt.value = it }
            page.agentDeliveredAt?.let { agentDeliveredAt.value = it }
            page.messages.forEach(::insert)
            saveCache()
            errorText.value = null
        } catch (e: Exception) {
            showError(e, token)
        }
    }

    /**
     * A request made with a session that has since been replaced (signing in moves the anonymous chat
     * into the account's and deletes it) fails with "Conversation not found". That error belongs to the
     * old session, so it is not shown over the new one.
     */
    private fun showError(e: Exception, token: String?) {
        if (client?.token == token) errorText.value = e.message
    }

    // live socket

    private fun connect() {
        if (socket != null) return
        val client = client ?: return
        val url = client.socketUrl() ?: return
        lateinit var ws: WebSocket
        ws = client.http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { if (socket === ws) handle(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    if (socket !== ws) return@launch
                    disconnect()
                    // Reconnect and catch up on anything missed while offline.
                    delay(3000)
                    if (!visible) return@launch
                    loadHistory()
                    connect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onFailure(webSocket, Exception(reason), null)
        })
        socket = ws
        pingJob = scope.launch {
            while (isActive) {
                delay(30_000)
                ws.send("ping")
            }
        }
    }

    private fun disconnect() {
        pingJob?.cancel()
        // Tells the server the customer left, so a reply that comes in now goes out as a push at once
        // instead of waiting for the server to notice the socket is gone. OkHttp sends queued frames
        // before the close.
        socket?.send("""{"type":"bye"}""")
        socket?.close(1001, null)
        socket = null
    }

    private fun handle(text: String) {
        if (text == "pong") return
        val event = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (event.optString("type")) {
            "card" -> {
                val id = event.optStringOrNull("id")
                val card = event.optJSONObject("card")?.let(Card::parse) ?: return
                if (messages.value.none { it.id == id }) return
                messages.update { list -> list.map { if (it.id == id) it.copy(card = card) else it } }
                saveCache()
            }
            "read" -> event.optDoubleOrNull("at")?.let { agentReadAt.value = it }
            "delivered" -> event.optDoubleOrNull("at")?.let { agentDeliveredAt.value = maxOf(agentDeliveredAt.value, it) }
            "agent_presence" -> agentActiveAt.value = event.optDoubleOrNull("activeAt")
            "typing" -> {
                agentTyping.value = true
                typingClear?.cancel()
                typingClear = scope.launch { delay(5000); agentTyping.value = false }
            }
            "message" -> {
                val message = event.optJSONObject("message")?.let(Message::parse) ?: return
                insert(message)
                saveCache()
                if (!message.isMine) {
                    agentTyping.value = false
                    if (visible) markRead() else Hailbell.setUnread(Hailbell.unreadCount.value + 1)
                }
            }
        }
    }
}
