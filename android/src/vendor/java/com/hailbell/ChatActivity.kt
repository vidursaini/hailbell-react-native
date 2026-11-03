package com.hailbell

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The chat as a full-height sheet over your app, like the iOS page sheet: rounded top, a grabber,
 * drag down or press back to close. Notification taps open it directly with the action
 * `HAILBELL_OPEN_CHAT`.
 */
class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // With darkMode set, the bar icons follow the chat's look, not the phone's: light icons on the
        // dark chat even when the phone is in light mode.
        when (Hailbell.darkMode) {
            null -> enableEdgeToEdge()
            true -> enableEdgeToEdge(SystemBarStyle.dark(AndroidColor.TRANSPARENT), SystemBarStyle.dark(AndroidColor.TRANSPARENT))
            false -> enableEdgeToEdge(
                SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
                SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            )
        }
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        setContent { Sheet(onClosed = ::close) }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        Hailbell.chat.appeared()
    }

    override fun onStop() {
        super.onStop()
        visible = false
        Hailbell.chat.disappeared()
    }

    private fun close() {
        // Opened from a notification while the app was closed: land in the app, not on the home screen.
        if (isTaskRoot) packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
        finish()
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    companion object {
        /** True while the chat is on screen, so replies show in it rather than as notifications. */
        @JvmStatic
        var visible = false
            internal set
    }
}

@Composable
private fun Sheet(onClosed: () -> Unit) {
    val model = Hailbell.chat
    val appearance by model.appearance.collectAsState()
    val theme = ChatTheme(appearance, Hailbell.darkMode ?: isSystemInDarkTheme())
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = with(LocalDensity.current) { maxHeight.toPx() }
        val offset = remember { Animatable(height) }
        val dismiss: () -> Unit = {
            scope.launch { offset.animateTo(height, tween(250)); onClosed() }
        }
        LaunchedEffect(Unit) { offset.animateTo(0f, tween(320)) }
        BackHandler(onBack = dismiss)

        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f * (1 - offset.value / height).coerceIn(0f, 1f))))
        Box(
            Modifier.fillMaxSize().statusBarsPadding().padding(top = 10.dp)
                .graphicsLayer { translationY = offset.value }
                .clip(RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp))
                .background(theme.surface)
                .draggable(
                    rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) } },
                    Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (offset.value > height * 0.25f || velocity > 1500f) dismiss()
                        else scope.launch { offset.animateTo(0f, tween(200)) }
                    },
                ),
        ) {
            Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                ChatScreen(model, theme, onClose = dismiss, onAllowNotifications = {
                    val context = Hailbell.context
                    if (model.notifyAsk.allow(context) && Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(NotifyAsk.PERMISSION) != PackageManager.PERMISSION_GRANTED
                    ) permission.launch(NotifyAsk.PERMISSION)
                })
            }
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 5.dp).size(36.dp, 5.dp)
                    .clip(RoundedCornerShape(50)).background(theme.secondary.copy(alpha = 0.4f)),
            )
        }
    }
}
