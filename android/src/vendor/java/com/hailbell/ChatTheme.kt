package com.hailbell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The chat's colors for the current light or dark mode, from the appearance set in the dashboard.
 * Anything unset falls back to the iOS system colors, so both apps' chats look the same.
 */
internal class ChatTheme(appearance: Appearance, val dark: Boolean) {
    private val p = if (dark) appearance.dark else appearance.light
    val accent = hex(p?.accent) ?: Color(0xFF007AFF)
    val onAccent = hex(p?.onAccent) ?: Color.White
    // iOS raises its dark system colors inside a sheet, which is where the chat always is.
    val background = hex(p?.background) ?: if (dark) Color(0xFF1C1C1E) else Color.White
    val incoming = hex(p?.incoming) ?: if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
    val incomingText = hex(p?.incomingText) ?: primary(dark)
    /** What the sheet and its bars are painted with. */
    val surface = background
    val primary = primary(dark)
    val secondary = if (dark) Color(0x99EBEBF5) else Color(0x993C3C43)
    val separator = if (dark) Color(0x2EFFFFFF) else Color(0x1F000000)
    /** The disabled send button's fill, iOS's tertiarySystemFill. */
    val tertiaryFill = if (dark) Color(0x3D767680) else Color(0x1F767680)

    companion object {
        private fun primary(dark: Boolean) = if (dark) Color.White else Color.Black

        /** A "#rrggbb" color from the dashboard. */
        fun hex(value: String?): Color? {
            if (value == null || value.length != 7 || !value.startsWith("#")) return null
            val v = value.drop(1).toLongOrNull(16) ?: return null
            return Color(0xFF000000 or v)
        }
    }
}

/** The iOS text styles at their default sizes, so lines wrap the same on both platforms. */
internal object ChatType {
    val headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val subheadline = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val caption2 = TextStyle(fontSize = 11.sp, lineHeight = 13.sp)
}
