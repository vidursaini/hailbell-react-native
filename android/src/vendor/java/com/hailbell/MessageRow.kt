package com.hailbell

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Keeps lines readable on wide screens (foldables, tablets, landscape). */
internal val MAX_ROW_WIDTH = 520.dp
private val BUBBLE = RoundedCornerShape(18.dp)
private val PHOTO = RoundedCornerShape(14.dp)

/**
 * One message: the sender's name over the first of a run, then its images and text. Replies from a
 * person carry their photo beside the last message of a run, when the dashboard shows team photos.
 */
@Composable
internal fun MessageRow(
    message: Message, caption: String?, photo: String?, bundledPhoto: Bitmap?, showsFace: Boolean,
    resolve: (String) -> String?, theme: ChatTheme, onCard: (String) -> Unit,
    quoteName: String? = null, onQuote: ((String) -> Unit)? = null, onReply: (() -> Unit)? = null, highlighted: Boolean = false,
) {
    val mine = message.isMine
    // AI replies carry the sparkles mark; yours carry your photo when there is one to show.
    val ai = message.author == "bot"
    val hasFace = ai || (!mine && (bundledPhoto != null || photo != null))
    // Briefly tinted after its quote was tapped, so the eye finds it.
    val tint by animateColorAsState(if (highlighted) theme.accent.copy(alpha = 0.15f) else Color.Transparent, label = "highlight")
    Box(Modifier.fillMaxWidth().background(tint, RoundedCornerShape(12.dp))) {
        RowFrame(mine) {
            SwipeToReply(onReply != null, theme, { onReply?.invoke() }) {
                Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (caption != null) {
                        Text(caption, style = ChatType.caption2, color = theme.secondary, modifier = Modifier.padding(start = if (hasFace) 42.dp else 6.dp))
                    }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val face = Modifier.alpha(if (showsFace) 1f else 0f)
                        if (ai) AIAvatar(28.dp, theme, face) else if (hasFace) Avatar(bundledPhoto, photo, 28.dp, theme, face)
                        MessageMenu(message, onReply) {
                            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (mine && message.report != null) ReportLabel(theme)
                                val card = message.card
                                val cardShown = card != null && card.isShown
                                val quote = message.replyTo
                                val name = quoteName ?: ""
                                // No text bubble to sit in: the quote goes above the images or card.
                                if (quote != null && (cardShown || message.body.isEmpty())) StandaloneQuote(quote, name, mine, theme, onQuote)
                                message.attachments.forEach { a -> resolve(a.url)?.let { Photo(it, theme) } }
                                // Long-press opens the message's menu (Reply, Copy), so text selection is off where it can reply.
                                val selectable = onReply == null
                                if (card != null && cardShown) CardBubble(message.body, card, theme, onCard, selectable)
                                else if (message.body.isNotEmpty()) {
                                    Bubble(
                                        message.body, mine, theme, selectable = selectable,
                                        quote = quote?.let { q -> { QuoteBlock(q, name, mine, theme, onQuote) } },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Long-press on a sent message: a menu with Reply and Copy. TalkBack gets Reply as a custom action.
 * Without [onReply] (nothing to reply to) the content is left as it is.
 */
@Composable
private fun MessageMenu(message: Message, onReply: (() -> Unit)?, content: @Composable () -> Unit) {
    if (onReply == null) { content(); return }
    var open by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val replyLabel = L10n.text("reply")
    Box(
        Modifier
            .pointerInput(Unit) { detectTapGestures(onLongPress = { open = true }) }
            .semantics { customActions = listOf(CustomAccessibilityAction(replyLabel) { onReply(); true }) },
    ) {
        content()
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                text = { Text(replyLabel) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, null) },
                onClick = { open = false; onReply() },
            )
            if (message.body.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(android.R.string.copy)) },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                    onClick = { open = false; clipboard.setText(AnnotatedString(message.body)) },
                )
            }
        }
    }
}

/** Over a bug report the customer sent: a small, quiet "Bug report" tag. */
@Composable
private fun ReportLabel(theme: ChatTheme) {
    Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.BugReport, null, Modifier.size(12.dp), tint = theme.secondary)
        Text(L10n.text("bugReport"), style = ChatType.caption2, color = theme.secondary)
    }
}

/** Lines a row up on its side, at most [MAX_ROW_WIDTH] wide, with 48dp clear on the other side. */
@Composable
internal fun RowFrame(mine: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(start = if (mine) 48.dp else 0.dp, end = if (mine) 0.dp else 48.dp),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.widthIn(max = MAX_ROW_WIDTH)) { content() }
    }
}

/** The AI's mark beside its replies: sparkles in an accent circle, so they never look like a person's. */
@Composable
internal fun AIAvatar(size: androidx.compose.ui.unit.Dp, theme: ChatTheme, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(theme.accent), Alignment.Center) {
        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(size * 0.5f), tint = theme.onAccent)
    }
}

@Composable
private fun Photo(url: String, theme: ChatTheme) {
    AsyncImage(
        url, null,
        Modifier.sizeIn(maxWidth = 220.dp, maxHeight = 280.dp).clip(PHOTO).background(theme.incoming)
            // A faint edge so white images don't melt into a light sheet.
            .border(1.dp, theme.primary.copy(alpha = 0.1f), PHOTO),
        contentScale = ContentScale.Fit,
    )
}

@Composable
internal fun PendingPhoto(image: Bitmap, faded: Boolean) {
    Image(
        image.asImageBitmap(), null,
        Modifier.sizeIn(maxWidth = 220.dp, maxHeight = 280.dp).clip(PHOTO).alpha(if (faded) 0.6f else 1f),
        contentScale = ContentScale.Fit,
    )
}

@Composable
internal fun Bubble(
    text: String, mine: Boolean, theme: ChatTheme, sending: Boolean = false, failed: Boolean = false,
    selectable: Boolean = true, quote: (@Composable () -> Unit)? = null,
) {
    Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Column(
            Modifier.alpha(if (sending) 0.6f else 1f)
                .background(if (mine) theme.accent else theme.incoming, BUBBLE)
                // With a quote, the quote and the text share the wider of the two widths.
                .then(if (quote != null) Modifier.width(IntrinsicSize.Max) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (quote != null) Box(Modifier.padding(top = 2.dp)) { quote() }
            val body = @Composable { Text(text, style = ChatType.body, color = if (mine) theme.onAccent else theme.incomingText) }
            if (selectable) SelectionContainer { body() } else body()
        }
        if (failed) NotSent()
    }
}

@Composable
internal fun NotSent() {
    Text(L10n.text("notSent"), style = ChatType.caption2, color = Color(0xFFFF3B30), modifier = Modifier.padding(horizontal = 6.dp))
}

/**
 * Your message with its button inside the bubble: the text, then a full-width pill with a gift for an
 * offer or a star for a review, and a chevron for where it goes. "Not now" sits under the button until
 * they choose.
 */
@Composable
private fun CardBubble(text: String, card: Card, theme: ChatTheme, act: (String) -> Unit, selectable: Boolean = true) {
    val context = LocalContext.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    Column(
        Modifier.widthIn(min = 250.dp).background(theme.incoming, RoundedCornerShape(22.dp))
            .padding(10.dp).padding(top = if (text.isEmpty()) 0.dp else 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (text.isNotEmpty()) {
            val body = @Composable { Text(text, style = ChatType.body, color = theme.incomingText, modifier = Modifier.padding(horizontal = 4.dp)) }
            if (selectable) SelectionContainer { body() } else body()
        }
        Row(
            Modifier.scale(scale).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(50)).background(theme.accent)
                .clickable(press, null) {
                    act("tapped")
                    val placement = card.placement
                    val activity = context.activity()
                    if (placement != null) Hailbell.onPaywall?.invoke(placement) { act("purchased") }
                    else if (card.type == "review" && activity != null) Review.open(activity, card.url)
                    else card.url?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
                }
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(if (card.type == "offer") Icons.Filled.CardGiftcard else Icons.Filled.Star, null, Modifier.size(20.dp), tint = theme.onAccent)
            Text(
                card.button, style = ChatType.body.copy(fontWeight = FontWeight.SemiBold), color = theme.onAccent,
                maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Icon(
                if (card.placement != null) Icons.AutoMirrored.Filled.ArrowForwardIos else Icons.Filled.NorthEast, null,
                Modifier.size(14.dp), tint = theme.onAccent,
            )
        }
        if (card.state == "sent") {
            Text(
                L10n.text("notNow"), style = ChatType.footnote, color = theme.secondary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { act("dismissed") }.padding(vertical = 2.dp),
            )
        }
    }
}

/** Under an offer they bought: a short thank-you line. */
@Composable
internal fun CardStatus(theme: ChatTheme) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CardGiftcard, null, Modifier.size(14.dp), tint = theme.secondary)
        Text(" " + L10n.text("thanks"), style = ChatType.caption, color = theme.secondary)
    }
}
