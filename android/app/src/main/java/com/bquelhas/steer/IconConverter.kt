package com.bquelhas.steer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Bundle

/**
 * Extracts a navigation app's maneuver glyph from a notification and packs it into a
 * watch-ready 1-bit bitmap.
 *
 * Some apps (notably Google Maps) never spell the maneuver out in text — the turn lives
 * only as the notification's `largeIcon`. There is nothing to keyword-match, so instead
 * of guessing we forward the app's own glyph: scale it to [SIZE]x[SIZE] and threshold it
 * to 1 bit per pixel. The watch reconstructs a GBitmap from the bytes and blits it.
 *
 * Byte layout (must match the C side): [ROW_BYTES] bytes per row (32-bit aligned), [SIZE]
 * rows, LSB-first within each byte (bit `x % 8` of byte `y*ROW_BYTES + x/8`); a set bit is
 * a foreground (drawn) pixel.
 */
object IconConverter {
    const val SIZE = 48
    val ROW_BYTES = ((SIZE + 31) / 32) * 4   // 8 for 48px
    val BYTE_COUNT = ROW_BYTES * SIZE        // 384

    /** Returns the packed 1-bpp bytes for the notification's largeIcon, or null if absent. */
    fun extractManeuverBitmap(context: Context, extras: Bundle): ByteArray? {
        val bitmap = loadLargeIcon(context, extras) ?: return null
        return pack(bitmap)
    }

    /**
     * Packs an arbitrary [bitmap] into the watch-ready 1-bpp mask with the SAME
     * scaling + brightness threshold as a real notification glyph. Exposed so the
     * mock-nav debug tool can feed a captured Google Maps glyph through the exact
     * pipeline the classifier sees on-device.
     */
    fun pack(bitmap: Bitmap): ByteArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val out = ByteArray(BYTE_COUNT)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val px = scaled.getPixel(x, y)
                val opaque = Color.alpha(px) > 128
                val bright = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3 > 128
                if (opaque && bright) {
                    val idx = y * ROW_BYTES + (x / 8)
                    out[idx] = (out[idx].toInt() or (1 shl (x % 8))).toByte()
                }
            }
        }
        return out
    }

    private fun loadLargeIcon(context: Context, extras: Bundle): Bitmap? {
        return when (val obj = extras.get("android.largeIcon")) {
            is Bitmap -> obj
            is Icon -> {
                val d = obj.loadDrawable(context) ?: return null
                if (d is BitmapDrawable) return d.bitmap
                val w = d.intrinsicWidth.takeIf { it > 0 } ?: 64
                val h = d.intrinsicHeight.takeIf { it > 0 } ?: 64
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    val c = Canvas(it)
                    d.setBounds(0, 0, w, h)
                    d.draw(c)
                }
            }
            else -> null
        }
    }
}
