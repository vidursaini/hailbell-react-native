package com.hailbell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Common reasons to write, shown on an empty chat. Tapping one sends it as the customer's first
 * message, so they start faster and the team (or the AI) knows the topic before reading on. The
 * dashboard sets them under Chat design: none set means these defaults, an empty list turns them off.
 */
internal fun resolveTopics(custom: List<String>?): List<String> =
    (custom ?: L10n.text("topics").split("|")).map { it.trim() }.filter { it.isNotEmpty() }.take(4)

@Composable
internal fun TopicChips(topics: List<String>, theme: ChatTheme, onTap: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        topics.forEach { topic ->
            Text(
                topic, style = ChatType.subheadline.copy(fontWeight = FontWeight.Medium), color = theme.accent,
                modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, theme.accent.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .clickable { onTap(topic) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** The ask after the first message: a line and two buttons, styled like the chat's cards. */
@Composable
internal fun NotifyAskRow(state: NotifyAsk.State, theme: ChatTheme, onAllow: () -> Unit, onNotNow: () -> Unit) {
    if (state == NotifyAsk.State.HIDDEN) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp)
            .background(theme.incoming, RoundedCornerShape(22.dp)).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.NotificationsActive, null, Modifier.size(18.dp), tint = theme.accent)
            Text(L10n.text("notifyAsk"), style = ChatType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = theme.incomingText, textAlign = TextAlign.Center)
        }
        Text(
            L10n.text(if (state == NotifyAsk.State.SETTINGS) "notifySettings" else "notifyAllow"),
            style = ChatType.body.copy(fontWeight = FontWeight.SemiBold), color = theme.onAccent, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(theme.accent)
                .clickable(onClick = onAllow).padding(vertical = 11.dp),
        )
        Text(L10n.text("notNow"), style = ChatType.footnote, color = theme.secondary, modifier = Modifier.clickable(onClick = onNotNow))
    }
}

/** One tick once we have the message, two once it reached the team, blue once they read it. */
@Composable
internal fun Ticks(delivery: ChatModel.Delivery, theme: ChatTheme) {
    val color = if (delivery == ChatModel.Delivery.SEEN) Color(0.2f, 0.59f, 0.98f) else theme.secondary
    val label = L10n.text(when (delivery) { ChatModel.Delivery.SEEN -> "seen"; ChatModel.Delivery.DELIVERED -> "delivered"; else -> "sent" })
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.End) {
        Canvas(Modifier.size(16.dp, 11.dp).semantics { contentDescription = label }) {
            fun pt(x: Float, y: Float) = Offset(x / 22f * size.width, y / 12f * size.height)
            val p = Path()
            fun line(points: List<Offset>) { p.moveTo(points[0].x, points[0].y); points.drop(1).forEach { p.lineTo(it.x, it.y) } }
            if (delivery != ChatModel.Delivery.SENT) {
                line(listOf(pt(0.5f, 6.5f), pt(4.5f, 10.5f), pt(14f, 1f)))
                line(listOf(pt(9.5f, 9.5f), pt(10.5f, 10.5f), pt(20f, 1f)))
            } else {
                line(listOf(pt(5f, 6.5f), pt(9f, 10.5f), pt(18.5f, 1f)))
            }
            drawPath(p, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
