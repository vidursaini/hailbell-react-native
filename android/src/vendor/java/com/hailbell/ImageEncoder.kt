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
     * quality down until it fits.
     */
    fun chatJpeg(image: Bitmap): Jpeg? {
        val longest = max(image.width, image.height)
        val scale = if (longest > 1600) 1600f / longest else 1f
        val w = (image.width * scale).roundToInt()
        val h = (image.height * scale).roundToInt()
        val resized = if (scale < 1f) Bitmap.createScaledBitmap(image, w, h, true) else image
        for (quality in listOf(80, 60, 40)) {
            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() < 1_400_000) return Jpeg(out.toByteArray(), w, h)
        }
        return null
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
