package com.hailbell

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** How far a bubble must be dragged to pick it for a reply. */
private val REPLY_THRESHOLD = 60.dp
/** The furthest a bubble moves, however far the finger goes. */
private val REPLY_MAX = 96.dp

/**
 * Who a quoted message or the reply bar names, as the customer sees it: their own messages are "You",
 * a teammate's carry their name (the chat's title when names are hidden or missing), the AI's its name
 * with the AI label, and anything else the chat's title.
 */
internal fun replyName(author: String, authorName: String?, title: String, agentNames: Boolean?): String = when (author) {
    "user" -> L10n.text("you")
    "bot" -> "${authorName ?: "Assistant"} · ${L10n.text("ai")}"
    "agent" -> authorName?.takeIf { agentNames != false && it.isNotBlank() } ?: title
    else -> title
}

/**
 * Lets a message be dragged to the right to reply, like WhatsApp: the content follows the finger (with
 * resistance past the threshold), a reply arrow fades and grows in behind it, one light haptic marks the
 * threshold, and letting go past it picks the message. It always springs back. Only a clearly horizontal,
 * rightward drag starts it, so the list still scrolls.
 */
@Composable
internal fun SwipeToReply(enabled: Boolean, theme: ChatTheme, onReply: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val threshold = with(density) { REPLY_THRESHOLD.toPx() }
    val max = with(density) { REPLY_MAX.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val reply by rememberUpdatedState(onReply)

    val gesture = if (!enabled) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                val d = change.position - down.position
                // Leave it unconsumed otherwise, so the list (or nothing) takes it.
                if (d.x > 0 && d.x > abs(d.y) * 2) change.consume()
            } ?: return@awaitEachGesture
            var raw = (start.position.x - down.position.x - viewConfiguration.touchSlop).coerceAtLeast(0f)
            var armed = false
            fun follow() {
                val shown = if (raw <= threshold) raw else minOf(max, threshold + (raw - threshold) * 0.35f)
                scope.launch { offset.snapTo(shown) }
                val past = raw >= threshold
                if (past && !armed) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                armed = past
            }
            follow()
            val finished = horizontalDrag(start.id) { change ->
                raw = (raw + change.positionChange().x).coerceAtLeast(0f)
                change.consume()
                follow()
            }
            if (finished && armed) reply()
            scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) }
        }
    }

    Box(gesture) {
        val progress = (offset.value / threshold).coerceIn(0f, 1f)
        if (progress > 0f) {
            Box(
                Modifier.align(AbsoluteAlignment.CenterLeft).padding(start = 4.dp).size(30.dp)
                    .graphicsLayer { alpha = progress; scaleX = 0.5f + 0.5f * progress; scaleY = scaleX }
                    .background(theme.incoming, CircleShape),
                Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(18.dp), tint = theme.secondary) }
        }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

/** One line (or [maxLines]) of the quoted text, or a photo mark with "Photo" for an image-only message. */
@Composable
internal fun QuoteSnippet(body: String, image: Boolean, color: Color, maxLines: Int) {
    if (body.isBlank() && image) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Filled.Photo, null, Modifier.size(14.dp), tint = color)
            Text(L10n.text("photo"), style = ChatType.footnote, color = color, maxLines = 1)
        }
    } else {
        Text(body.replace('\n', ' '), style = ChatType.footnote, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The quote at the top of a reply: a thin bar, who wrote the quoted message, and up to two lines of it.
 * [onAccent] is true inside the customer's own (accent) bubble. Tapping it shows the original.
 */
@Composable
internal fun QuoteBlock(quote: ReplyTo, name: String, onAccent: Boolean, theme: ChatTheme, onTap: ((String) -> Unit)?) {
    val bar = if (onAccent) theme.onAccent else theme.accent
    val nameColor = if (onAccent) theme.onAccent else theme.accent
    val textColor = if (onAccent) theme.onAccent.copy(alpha = 0.8f) else theme.secondary
    val fill = if (onAccent) theme.onAccent.copy(alpha = 0.15f) else theme.primary.copy(alpha = 0.06f)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(8.dp)).background(fill)
            .then(if (onTap != null) Modifier.clickable { onTap(quote.id) } else Modifier),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(bar))
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, style = ChatType.caption.copy(fontWeight = FontWeight.SemiBold), color = nameColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            QuoteSnippet(quote.body, quote.image, textColor, 2)
        }
    }
}

/** A quote on a message with no text bubble (only images or a card): in a small bubble of its own. */
@Composable
internal fun StandaloneQuote(quote: ReplyTo, name: String, mine: Boolean, theme: ChatTheme, onTap: ((String) -> Unit)?) {
    Box(
        Modifier.width(IntrinsicSize.Max).background(if (mine) theme.accent else theme.incoming, RoundedCornerShape(14.dp)).padding(5.dp),
    ) { QuoteBlock(quote, name, mine, theme, onTap) }
}

/** Over the composer while replying: "Replying to {name}", the snippet, and × to cancel. */
@Composable
internal fun ReplyBar(quote: ReplyTo, name: String, theme: ChatTheme, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(theme.accent))
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                L10n.text("replyingTo", mapOf("name" to name)), style = ChatType.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = theme.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            QuoteSnippet(quote.body, quote.image, theme.secondary, 1)
        }
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(theme.incoming).clickable(onClick = onCancel)
                .semantics { contentDescription = L10n.text("cancelReply") },
            Alignment.Center,
        ) { Icon(Icons.Filled.Close, null, Modifier.size(16.dp), tint = theme.secondary) }
    }
}
