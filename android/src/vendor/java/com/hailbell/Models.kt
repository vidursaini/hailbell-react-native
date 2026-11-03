package com.hailbell

import org.json.JSONArray
import org.json.JSONObject

// The same shapes as the Swift SDK's Client.swift, parsed with org.json so the library needs no
// serialization plugin.

internal data class Session(
    val token: String,
    val conversationId: String,
    val unread: Int,
    val appName: String,
    val assistant: Assistant?,
    val appearance: Appearance?,
    val team: List<TeamMember>,
    val agentActiveAt: Double?,
) {
    companion object {
        fun parse(o: JSONObject) = Session(
            token = o.getString("token"),
            conversationId = o.getString("conversationId"),
            unread = o.optInt("unread"),
            appName = o.optString("appName"),
            assistant = o.optJSONObject("assistant")?.let(Assistant::parse),
            appearance = o.optJSONObject("appearance")?.let(Appearance::parse),
            team = o.optJSONArray("team").objects().map(TeamMember::parse),
            agentActiveAt = o.optDoubleOrNull("agentActiveAt"),
        )
    }
}

/** Set when an AI may answer before a person does, so the chat can say so. */
internal data class Assistant(val name: String, val provider: String) {
    fun json() = JSONObject().put("name", name).put("provider", provider)

    companion object {
        fun parse(o: JSONObject) = Assistant(o.optString("name"), o.optString("provider"))
    }
}

/** The chat design published from the dashboard. Anything unset falls back to the built-in look. */
internal data class Appearance(
    val title: String? = null,
    val subtitle: String? = null,
    val attachments: Boolean? = null,
    val welcomeTitle: String? = null,
    val welcomeText: String? = null,
    val placeholder: String? = null,
    val agentNames: Boolean? = null,
    val teamPhotos: Boolean? = null,
    val avatarUrl: String? = null,
    /** Quick topics on an empty chat. Null means the built-in ones, an empty list turns them off. */
    val topics: List<String>? = null,
    val light: Palette? = null,
    val dark: Palette? = null,
) {
    data class Palette(
        val accent: String?, val onAccent: String?, val background: String?, val incoming: String?, val incomingText: String?,
    ) {
        fun json(): JSONObject = JSONObject().putOpt("accent", accent).putOpt("onAccent", onAccent)
            .putOpt("background", background).putOpt("incoming", incoming).putOpt("incomingText", incomingText)

        companion object {
            fun parse(o: JSONObject) = Palette(
                o.optStringOrNull("accent"), o.optStringOrNull("onAccent"), o.optStringOrNull("background"),
                o.optStringOrNull("incoming"), o.optStringOrNull("incomingText"),
            )
        }
    }

    fun json(): JSONObject = JSONObject().putOpt("title", title).putOpt("subtitle", subtitle).putOpt("attachments", attachments)
        .putOpt("welcomeTitle", welcomeTitle).putOpt("welcomeText", welcomeText).putOpt("placeholder", placeholder)
        .putOpt("agentNames", agentNames).putOpt("teamPhotos", teamPhotos).putOpt("avatarUrl", avatarUrl)
        .putOpt("topics", topics?.let { JSONArray(it) }).putOpt("light", light?.json()).putOpt("dark", dark?.json())

    companion object {
        fun parse(o: JSONObject) = Appearance(
            title = o.optStringOrNull("title"),
            subtitle = o.optStringOrNull("subtitle"),
            attachments = o.optBoolOrNull("attachments"),
            welcomeTitle = o.optStringOrNull("welcomeTitle"),
            welcomeText = o.optStringOrNull("welcomeText"),
            placeholder = o.optStringOrNull("placeholder"),
            agentNames = o.optBoolOrNull("agentNames"),
            teamPhotos = o.optBoolOrNull("teamPhotos"),
            avatarUrl = o.optStringOrNull("avatarUrl"),
            topics = o.optJSONArray("topics")?.let { a -> (0 until a.length()).map { a.optString(it) } },
            light = o.optJSONObject("light")?.let(Palette::parse),
            dark = o.optJSONObject("dark")?.let(Palette::parse),
        )
    }
}

/** The person who answers, as customers see them. `avatarUrl` is relative to the API's base URL. */
internal data class TeamMember(val name: String, val avatarUrl: String?) {
    fun json(): JSONObject = JSONObject().put("name", name).putOpt("avatarUrl", avatarUrl)

    companion object {
        fun parse(o: JSONObject) = TeamMember(o.optString("name"), o.optStringOrNull("avatarUrl"))
    }
}

data class Message(
    val id: String,
    /** "user" | "agent" | "bot" (the AI) | "system" */
    val author: String,
    val authorName: String?,
    val body: String,
    val attachments: List<Attachment>,
    val clientId: String?,
    /** ms since 1970 */
    val createdAt: Double,
    val card: Card?,
) {
    val isMine: Boolean get() = author == "user"

    internal fun json(): JSONObject = JSONObject().put("id", id).put("author", author).putOpt("authorName", authorName)
        .put("body", body).put("attachments", JSONArray(attachments.map { it.json() })).putOpt("clientId", clientId)
        .put("createdAt", createdAt).putOpt("card", card?.json())

    internal companion object {
        fun parse(o: JSONObject) = Message(
            id = o.getString("id"),
            author = o.getString("author"),
            authorName = o.optStringOrNull("authorName"),
            body = o.optStringOrNull("body") ?: "", // empty for image-only messages
            attachments = o.optJSONArray("attachments").objects().map(Attachment::parse),
            clientId = o.optStringOrNull("clientId"),
            createdAt = o.getDouble("createdAt"),
            card = o.optJSONObject("card")?.let(Card::parse),
        )
    }
}

/** An image in a message. `url` is relative to the API's base URL. */
data class Attachment(val id: String, val url: String, val width: Double?, val height: Double?) {
    internal fun json(): JSONObject = JSONObject().put("id", id).put("url", url).putOpt("width", width).putOpt("height", height)

    internal companion object {
        fun parse(o: JSONObject) = Attachment(o.getString("id"), o.getString("url"), o.optDoubleOrNull("width"), o.optDoubleOrNull("height"))
    }
}

/** A message from you with a button: a review request (opens `url`) or an offer (opens `placement`). */
data class Card(
    /** "review" | "offer" */
    val type: String,
    val button: String,
    val url: String?,
    val placement: String?,
    /** "sent" | "dismissed" | "tapped" | "purchased" */
    val state: String,
) {
    /** Cards open a paywall placement or an https link; anything else shows as a plain message. */
    val isShown: Boolean get() = placement != null || url?.startsWith("https://") == true

    internal fun json(): JSONObject = JSONObject().put("type", type).put("button", button).putOpt("url", url)
        .putOpt("placement", placement).put("state", state)

    internal companion object {
        fun parse(o: JSONObject) = Card(
            o.optString("type"), o.optString("button"), o.optStringOrNull("url"), o.optStringOrNull("placement"), o.optString("state", "sent"),
        )
    }
}

internal class ApiError(val status: Int, message: String) : Exception(message)

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
internal fun JSONObject.optBoolOrNull(key: String): Boolean? = if (has(key) && !isNull(key)) optBoolean(key) else null
internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null
