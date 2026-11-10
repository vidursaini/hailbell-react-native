package com.hailbell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.os.Bundle
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The report screen that [Hailbell.reportBug] and a shake open: the screenshot, which the user can
 * mark up or remove, a box for what went wrong, and Send. The report goes into the chat.
 */
class BugReportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        when (Hailbell.darkMode) {
            null -> enableEdgeToEdge()
            true -> enableEdgeToEdge(SystemBarStyle.dark(AndroidColor.TRANSPARENT), SystemBarStyle.dark(AndroidColor.TRANSPARENT))
            false -> enableEdgeToEdge(
                SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
                SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            )
        }
        super.onCreate(savedInstanceState)
        if (!Hailbell.isConfigured) { finish(); return }
        // Kept in a ViewModel so a rotation keeps the text, the screenshot and its markup.
        val state = ViewModelProvider(this)[ReportState::class.java]
        state.load(intent.getStringExtra(EXTRA_SCREENSHOT))
        setContent { ReportScreen(state, onClose = ::finish) }
    }

    internal companion object {
        const val EXTRA_SCREENSHOT = "com.hailbell.screenshot"
    }
}

internal class ReportState : ViewModel() {
    var screenshot by mutableStateOf<Bitmap?>(null)
    var text by mutableStateOf("")
    var sending by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var sent by mutableStateOf(false)
    private var loaded = false
    private var file: File? = null

    fun load(path: String?) {
        if (loaded) return
        loaded = true
        if (path == null) return
        file = File(path)
        screenshot = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    val canSend: Boolean get() = !sending && (text.isNotBlank() || screenshot != null)

    fun send() {
        if (!canSend) return
        sending = true
        failed = false
        val image = screenshot
        Hailbell.scope.launch {
            try {
                Hailbell.chat.sendReport(text, image, BugReports.fields(Hailbell.context))
                sent = true
            } catch (e: Exception) {
                android.util.Log.w("Hailbell", "Bug report failed: ${e.message}")
                failed = true
            } finally {
                sending = false
            }
        }
    }

    /** The screen is gone for good: the screenshot file is no longer needed. */
    override fun onCleared() {
        file?.delete()
    }
}

@Composable
private fun ReportScreen(state: ReportState, onClose: () -> Unit) {
    val appearance by Hailbell.chat.appearance.collectAsState()
    val theme = ChatTheme(appearance, Hailbell.darkMode ?: isSystemInDarkTheme())
    var marking by remember { mutableStateOf(false) }
    LaunchedEffect(state.sent) { if (state.sent) onClose() }

    Box(Modifier.fillMaxSize().background(theme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp)) {
                Text(
                    L10n.text("cancel"), style = ChatType.body, color = theme.accent,
                    modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onClose).padding(vertical = 8.dp),
                )
                Text(L10n.text("reportTitle"), style = ChatType.headline, color = theme.primary, modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd)) {
                    if (state.sending) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = theme.accent, strokeWidth = 2.dp)
                    } else {
                        Text(
                            L10n.text("send"), style = ChatType.body.copy(fontWeight = FontWeight.SemiBold), color = theme.accent,
                            modifier = Modifier.alpha(if (state.canSend) 1f else 0.4f)
                                .clickable(enabled = state.canSend, onClick = state::send).padding(vertical = 8.dp),
                        )
                    }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.screenshot?.let { image ->
                    val bitmap = remember(image) { image.asImageBitmap() }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(
                            bitmap, null,
                            Modifier.heightIn(max = 280.dp).clip(RoundedCornerShape(12.dp))
                                .border(1.dp, theme.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Fit,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill(L10n.text("markUp"), { Icon(Icons.Filled.Edit, null, Modifier.size(16.dp), tint = theme.primary) }, theme, !state.sending) { marking = true }
                            Pill(L10n.text("removeScreenshot"), { Icon(Icons.Filled.Delete, null, Modifier.size(16.dp), tint = theme.primary) }, theme, !state.sending) {
                                state.screenshot = null
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 140.dp).clip(RoundedCornerShape(12.dp)).background(theme.incoming).padding(12.dp)) {
                    if (state.text.isEmpty()) Text(L10n.text("reportPlaceholder"), style = ChatType.body, color = theme.secondary.copy(alpha = 0.6f))
                    BasicTextField(
                        state.text, { state.text = it }, Modifier.fillMaxWidth(),
                        enabled = !state.sending,
                        textStyle = ChatType.body.copy(color = theme.primary),
                        cursorBrush = SolidColor(theme.accent),
                    )
                }
                if (state.failed) Text(L10n.text("reportFailed"), style = ChatType.footnote, color = Color(0xFFFF3B30))
                Text(L10n.text("reportFooter"), style = ChatType.footnote, color = theme.secondary)
            }
        }
        val image = state.screenshot
        if (marking && image != null) {
            MarkupEditor(image, onDone = { state.screenshot = it; marking = false }, onCancel = { marking = false })
        }
    }
}

@Composable
private fun Pill(label: String, icon: @Composable () -> Unit, theme: ChatTheme, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(theme.incoming).clickable(enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon()
        Text(label, style = ChatType.subheadline, color = theme.primary)
    }
}

private val MARK_RED = Color(0xFFFF3B30)

/**
 * Full screen over the report: the finger draws red lines on the screenshot. Undo takes the last line
 * away, Done draws the lines into the image, back leaves without changing it.
 */
@Composable
private fun MarkupEditor(image: Bitmap, onDone: (Bitmap) -> Unit, onCancel: () -> Unit) {
    // In the screenshot's own pixels, so they land in the same place whatever size it is shown at.
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    val bitmap = remember(image) { image.asImageBitmap() }
    val density = LocalDensity.current
    // The line width in screenshot pixels, set where the screenshot's on-screen size is known. Not
    // state: it only changes with the layout, and Done reads it once.
    val lineWidth = remember { FloatArray(1) { 1f } }
    BackHandler(onBack = onCancel)

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp)) {
            Text(
                L10n.text("undo"), style = ChatType.body, color = Color.White,
                modifier = Modifier.align(Alignment.CenterStart).alpha(if (strokes.isEmpty()) 0.4f else 1f)
                    .clickable(enabled = strokes.isNotEmpty()) { strokes.removeAt(strokes.lastIndex) }.padding(vertical = 8.dp),
            )
            Text(
                L10n.text("done"), style = ChatType.body.copy(fontWeight = FontWeight.SemiBold), color = Color.White,
                modifier = Modifier.align(Alignment.CenterEnd)
                    .clickable { onDone(if (strokes.isEmpty()) image else flatten(image, strokes, lineWidth[0])) }.padding(vertical = 8.dp),
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val scale = min(boxW / image.width, boxH / image.height)
            val left = (boxW - image.width * scale) / 2
            val top = (boxH - image.height * scale) / 2
            // 4dp on screen, whatever the screenshot's size.
            val width = with(density) { 4.dp.toPx() } / scale
            lineWidth[0] = width
            fun toImage(p: Offset) = Offset((p.x - left) / scale, (p.y - top) / scale)
            fun toScreen(p: Offset) = Offset(p.x * scale + left, p.y * scale + top)

            Canvas(
                Modifier.fillMaxSize().pointerInput(scale, left, top) {
                    detectDragGestures(
                        onDragStart = { current.clear(); current.add(toImage(it)) },
                        onDrag = { change, _ -> change.consume(); current.add(toImage(change.position)) },
                        onDragEnd = { if (current.isNotEmpty()) strokes.add(current.toList()); current.clear() },
                        onDragCancel = { current.clear() },
                    )
                },
            ) {
                drawImage(
                    bitmap,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt()),
                )
                val style = Stroke(width = width * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
                (strokes + listOf(current.toList())).forEach { points ->
                    if (points.isEmpty()) return@forEach
                    val path = Path()
                    points.forEachIndexed { i, p -> toScreen(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
                    drawPath(path, MARK_RED, style = style)
                }
            }
        }
    }
}

/** A copy of [image] with the red [strokes] drawn into it. */
private fun flatten(image: Bitmap, strokes: List<List<Offset>>, width: Float): Bitmap {
    val out = image.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt()
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { points ->
        val path = android.graphics.Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        canvas.drawPath(path, paint)
    }
    return out
}
