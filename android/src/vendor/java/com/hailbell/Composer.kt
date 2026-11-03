package com.hailbell

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private const val MAX_IMAGES = 4
/** One line of text and the send button share this height, so both sit centred in the field. */
private val FIELD_HEIGHT = 36.dp
private val SEND_SIZE = 28.dp
private val FIELD = RoundedCornerShape(18.dp)

/**
 * The field at the bottom: a photo button, the text, and send, styled like the iOS 26 chat. Picked
 * photos wait above the field until sent, each with a button to remove it.
 */
@Composable
internal fun Composer(
    placeholder: String, allowsPhotos: Boolean, theme: ChatTheme,
    onTyping: () -> Unit, onSend: (String, List<Bitmap>) -> Unit, onFocus: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }
    val images = remember { mutableStateListOf<Bitmap>() }
    val canSend = draft.isNotBlank() || images.isNotEmpty()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
        uris.take(MAX_IMAGES - images.size).forEach { uri -> ImageEncoder.load(context, uri)?.let(images::add) }
    }

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (images.isNotEmpty()) Previews(images)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (allowsPhotos) {
                val enabled = images.size < MAX_IMAGES
                Box(
                    glass(Modifier.size(FIELD_HEIGHT), CircleShape, theme)
                        .clickable(enabled) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        .semantics { contentDescription = L10n.text("attach") },
                    Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(22.dp), tint = theme.primary.copy(alpha = if (enabled) 1f else 0.3f))
                }
            }
            Row(
                glass(Modifier.weight(1f).heightIn(min = FIELD_HEIGHT), FIELD, theme)
                    .padding(start = 12.dp, end = if (canSend) 4.dp else 12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 7.dp)) {
                    if (draft.isEmpty()) Text(placeholder, style = ChatType.body, color = theme.secondary.copy(alpha = 0.5f), maxLines = 1)
                    BasicTextField(
                        draft,
                        { draft = it; if (it.isNotEmpty()) onTyping() },
                        Modifier.fillMaxWidth().onFocusChanged { onFocus(it.isFocused) },
                        textStyle = ChatType.body.copy(color = theme.primary),
                        cursorBrush = SolidColor(theme.accent),
                        maxLines = 5,
                    )
                }
                // Like Messages, the send button only shows once there is something to send.
                AnimatedVisibility(canSend, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    Box(
                        Modifier.padding(start = 6.dp, bottom = (FIELD_HEIGHT - SEND_SIZE) / 2).size(SEND_SIZE)
                            .clip(CircleShape).background(theme.accent)
                            .clickable {
                                onSend(draft, images.toList())
                                draft = ""
                                images.clear()
                            }
                            .semantics { contentDescription = L10n.text("send") },
                        Alignment.Center,
                    ) {
                        Icon(Icons.Filled.ArrowUpward, null, Modifier.size(18.dp), tint = theme.onAccent)
                    }
                }
            }
        }
    }
}

/** The frosted look of iOS 26 controls: a light fill, a hairline edge and a soft shadow. */
private fun glass(modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, theme: ChatTheme): Modifier =
    modifier.shadow(if (theme.dark) 0.dp else 6.dp, shape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.12f))
        .clip(shape)
        .background(if (theme.dark) Color(0xFF2C2C2E) else Color(0xFFFBFBFD))
        .border(0.5.dp, theme.separator, shape)

@Composable
private fun Previews(images: MutableList<Bitmap>) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEachIndexed { i, image ->
            Box(Modifier.size(64.dp)) {
                Image(image.asImageBitmap(), null, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                Box(
                    Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)).clickable { images.removeAt(i) }
                        .semantics { contentDescription = L10n.text("removeImage") },
                    Alignment.Center,
                ) { Icon(Icons.Filled.Close, null, Modifier.size(13.dp), tint = Color.White) }
            }
        }
    }
}
