package com.hailbell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The chat: header, messages and composer. [onClose] dismisses the sheet. */
@Composable
internal fun ChatScreen(model: ChatModel, theme: ChatTheme, onClose: () -> Unit, onAllowNotifications: () -> Unit) {
    val messages by model.messages.collectAsState()
    val pending by model.pending.collectAsState()
    val appearance by model.appearance.collectAsState()
    val team by model.team.collectAsState()
    val hasMore by model.hasMore.collectAsState()
    val typing by model.agentTyping.collectAsState()
    val readAt by model.agentReadAt.collectAsState()
    val deliveredAt by model.agentDeliveredAt.collectAsState()
    val error by model.errorText.collectAsState()
    val ask by model.notifyAsk.state.collectAsState()
    val list = rememberLazyListState()
    val bundledAllowed by model.bundledPhotoAllowed.collectAsState()
    val replying by model.replying.collectAsState()
    val appName by model.appName.collectAsState()
    val scope = rememberCoroutineScope()
    var highlighted by remember { mutableStateOf<String?>(null) }
    val title = appearance.title ?: L10n.text("supportTitle", mapOf("name" to appName)).trim()
    val nameFor = { author: String, authorName: String? -> replyName(author, authorName, title, appearance.agentNames) }
    // Tapping a quote scrolls to the original, if it's loaded, and tints it for a moment.
    val showOriginal: (String) -> Unit = { id ->
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) scope.launch {
            list.animateScrollToItem((if (messages.isEmpty() && pending.isEmpty()) 2 else 0) + (if (hasMore) 1 else 0) + i)
            highlighted = id
            delay(1200)
            if (highlighted == id) highlighted = null
        }
    }

    // New messages, a reply arriving, "Typing…" or a sent message: keep the newest in view.
    val tail = "${messages.size}|${messages.lastOrNull()?.id}|${pending.size}|$typing|$ask"
    LaunchedEffect(tail) {
        delay(50) // the list only knows its new items after the next layout pass
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().background(theme.surface)) {
        Header(model, theme, onClose)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (messages.isEmpty() && pending.isEmpty()) {
                item("intro") { Intro(model, theme) }
                item("topics") { TopicChips(resolveTopics(appearance.topics), theme) { model.send(it) } }
            }
            if (hasMore) item("earlier") {
                Text(
                    L10n.text("earlier"), style = ChatType.footnote, color = theme.accent, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { model.loadEarlier() }.padding(bottom = 8.dp),
                )
            }
            items(messages, key = { it.id }) { m ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val i = messages.indexOf(m)
                    if (i == 0 || !sameDay(messages[i - 1].createdAt, m.createdAt)) DayChip(m.createdAt, theme)
                    val photo = model.photo(m, appearance, team)
                    MessageRow(
                        m, caption(model, m, messages, appearance), photo,
                        model.bundledPhoto(m, bundledAllowed, appearance, team), (m.author == "agent" || m.author == "bot") && model.endsRun(m, messages),
                        model::resolve, theme, { model.cardAction(m, it) },
                        quoteName = m.replyTo?.let { nameFor(it.author, it.authorName) },
                        onQuote = showOriginal, onReply = { model.reply(m) }, highlighted = highlighted == m.id,
                    )
                    model.delivery(m, messages, readAt, deliveredAt)?.let { Ticks(it, theme) }
                    // Thanks only for a real purchase; a tap may have closed the paywall, and the store
                    // never says whether a review was left.
                    val card = m.card
                    if (card != null && card.isShown && card.state == "purchased") CardStatus(theme)
                }
            }
            if (typing) item("typing") {
                Text(L10n.text("typing"), style = ChatType.footnote, color = theme.secondary, modifier = Modifier.padding(horizontal = 6.dp))
            }
            items(pending, key = { it.id }) { p ->
                RowFrame(mine = true) {
                    Column(
                        Modifier.clickable(enabled = p.failed) { model.retry(p) },
                        horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val quote = p.replyTo
                        val quoteName = quote?.let { nameFor(it.author, it.authorName) } ?: ""
                        if (quote != null && p.body.isEmpty()) StandaloneQuote(quote, quoteName, true, theme, showOriginal)
                        p.images.forEach { PendingPhoto(it, faded = !p.failed) }
                        if (p.body.isNotEmpty()) {
                            Bubble(
                                p.body, true, theme, sending = !p.failed, failed = p.failed,
                                quote = quote?.let { q -> { QuoteBlock(q, quoteName, true, theme, showOriginal) } },
                            )
                        } else if (p.failed) NotSent()
                    }
                }
            }
            item("ask") { NotifyAskRow(ask, theme, onAllowNotifications) { model.notifyAsk.finish() } }
            item("bottom") { Spacer(Modifier.height(1.dp)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            error?.let { Text(it, style = ChatType.footnote, color = theme.secondary, modifier = Modifier.padding(horizontal = 16.dp)) }
            Composer(
                appearance.placeholder ?: L10n.text("placeholder"), appearance.attachments != false, theme,
                onTyping = model::typing, onSend = { text, images -> model.send(text, images) },
                onFocus = {},
                replying = replying?.let { ReplyTo.of(it) }, replyName = replying?.let { nameFor(it.author, it.authorName) },
                onCancelReply = model::cancelReply,
            )
        }
    }
}

/** Like the web widget: the support mark and photo of who replies, the app's name, and who reads the messages. */
@Composable
private fun Header(model: ChatModel, theme: ChatTheme, onClose: () -> Unit) {
    val appearance by model.appearance.collectAsState()
    val team by model.team.collectAsState()
    val appName by model.appName.collectAsState()
    val bundledAllowed by model.bundledPhotoAllowed.collectAsState()
    val teamPhoto = model.teamPhoto(appearance, team)
    val bundled = model.bundledPhoto(bundledAllowed)
    val photoUrl = teamPhoto ?: appearance.avatarUrl?.let(model::resolve)
    val hasPhoto = bundled != null || photoUrl != null
    val title = appearance.title ?: L10n.text("supportTitle", mapOf("name" to appName)).trim()
    Column {
        Row(
            Modifier.fillMaxWidth().background(theme.surface).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The support mark, with the photo of who replies just overlapping it.
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                Box {
                    SupportMark(40.dp, theme)
                    if (!hasPhoto) PresenceDot(model, theme, Modifier.align(Alignment.BottomEnd))
                }
                if (hasPhoto) {
                    Box {
                        Avatar(bundled, photoUrl, 40.dp, theme, Modifier.background(theme.background, CircleShape).padding(2.dp))
                        PresenceDot(model, theme, Modifier.align(Alignment.BottomEnd))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = ChatType.headline, color = theme.primary, maxLines = 1)
                Text(appearance.subtitle ?: L10n.text("subtitle"), style = ChatType.footnote, color = theme.secondary, maxLines = 2)
            }
            Box(
                Modifier.size(32.dp).background(theme.incoming, CircleShape).clickable(onClick = onClose)
                    .semantics { contentDescription = L10n.text("close") },
                Alignment.Center,
            ) { Icon(Icons.Filled.Close, null, Modifier.size(18.dp), tint = theme.secondary) }
        }
        HorizontalDivider(thickness = 0.5.dp, color = theme.separator)
    }
}

/** Green while the team was active in the last 5 minutes, grey after. Rechecked every minute. */
@Composable
private fun PresenceDot(model: ChatModel, theme: ChatTheme, modifier: Modifier) {
    val activeAt by model.agentActiveAt.collectAsState()
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now.longValue = System.currentTimeMillis() } }
    if (activeAt == null) return
    val online = model.agentOnline(now.longValue) == true
    Box(
        modifier.size(12.dp).background(if (online) Color(0xFF34C759) else Color(0xFF8E8E93), CircleShape)
            .border(2.dp, theme.background, CircleShape)
            .semantics { contentDescription = L10n.text(if (online) "online" else "away") },
    )
}

/**
 * Only on an empty chat: what to write about, and the AI note customers must see before the AI
 * answers. AI replies are also labeled "AI" in the chat.
 */
@Composable
private fun Intro(model: ChatModel, theme: ChatTheme) {
    val appearance by model.appearance.collectAsState()
    val assistant by model.assistant.collectAsState()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(appearance.welcomeTitle ?: L10n.text("welcomeTitle"), style = ChatType.headline, color = theme.primary, textAlign = TextAlign.Center)
        appearance.welcomeText?.let { Text(it, style = ChatType.subheadline, color = theme.secondary, textAlign = TextAlign.Center) }
        if (assistant != null) Text(L10n.text("aiShort"), style = ChatType.footnote, color = theme.secondary, textAlign = TextAlign.Center)
    }
}

/** Names over the first of a run of replies, unless the dashboard hides them. AI replies always say so. */
private fun caption(model: ChatModel, m: Message, list: List<Message>, appearance: Appearance): String? {
    if (m.isMine || !model.startsRun(m, list)) return null
    if (m.author == "bot") return "${m.authorName ?: "Assistant"} · ${L10n.text("ai")}"
    return if (appearance.agentNames == false) null else m.authorName
}
