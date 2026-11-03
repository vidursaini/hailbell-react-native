package com.hailbell

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

/** A round photo: the bundled one, else one from the server, else the chat bubbles. */
@Composable
internal fun Avatar(image: Bitmap?, url: String?, size: Dp, theme: ChatTheme, modifier: Modifier = Modifier) {
    val shape = Modifier.size(size).clip(CircleShape)
    when {
        image != null -> Image(image.asImageBitmap(), null, modifier.then(shape), contentScale = ContentScale.Crop)
        url != null -> AsyncImage(url, null, modifier.then(shape).background(theme.incoming), contentScale = ContentScale.Crop)
        else -> Box(modifier.then(shape).background(theme.accent.copy(alpha = 0.14f)), Alignment.Center) {
            Icon(Icons.Filled.Forum, null, Modifier.size(size * 0.42f), tint = theme.accent)
        }
    }
}

/** A headset in a circle of the accent colour: support, not a person or an AI. */
@Composable
internal fun SupportMark(size: Dp, theme: ChatTheme, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(theme.accent, CircleShape), Alignment.Center) {
        Icon(Icons.Filled.HeadsetMic, null, Modifier.size(size * 0.5f), tint = theme.onAccent)
    }
}

/** "Today", "Yesterday" or the date, between messages from different days. */
@Composable
internal fun DayChip(createdAt: Double, theme: ChatTheme) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), Alignment.Center) {
        Text(
            dayLabel(createdAt.toLong()),
            style = ChatType.caption.copy(fontWeight = FontWeight.Medium),
            color = theme.secondary,
            modifier = Modifier.background(theme.incoming, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun dayLabel(ms: Long): String {
    val now = System.currentTimeMillis()
    if (DateUtils.isToday(ms) || DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS)) {
        return DateUtils.getRelativeTimeSpanString(ms, now, DateUtils.DAY_IN_MILLIS).toString()
            .replaceFirstChar { it.titlecase() }
    }
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
}

internal fun sameDay(a: Double, b: Double): Boolean {
    val fmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
    return fmt.format(Date(a.toLong())) == fmt.format(Date(b.toLong()))
}
