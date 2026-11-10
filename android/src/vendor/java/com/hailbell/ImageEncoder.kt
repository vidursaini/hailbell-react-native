package com.hailbell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

internal object ImageEncoder {
    class Jpeg(val data: ByteArray, val width: Int, val height: Int)

    /**
     * A JPEG under the server's 1.5 MB limit: at most 1600 pixels on the long side, stepping the
     * quality down from the first of [qualities] until it fits.
     */
    fun chatJpeg(image: Bitmap, qualities: List<Int> = listOf(80, 60, 40)): Jpeg? {
        val (w, h) = fit(image.width, image.height, 1600)
        val resized = if (w != image.width || h != image.height) Bitmap.createScaledBitmap(image, w, h, true) else image
        for (quality in qualities) {
            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() < 1_400_000) return Jpeg(out.toByteArray(), w, h)
        }
        return null
    }

    /** The size [width] by [height] scaled down so neither side is over [limit]. Smaller sizes stay as they are. */
    fun fit(width: Int, height: Int, limit: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= limit) return width to height
        val scale = limit.toFloat() / longest
        return (width * scale).roundToInt().coerceIn(1, limit) to (height * scale).roundToInt().coerceIn(1, limit)
    }

    /** A picked photo, upright and at most 2400 pixels on the long side so a few fit in memory. */
    fun load(context: Context, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > 2400) {
                    val s = 2400f / longest
                    decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()
}
