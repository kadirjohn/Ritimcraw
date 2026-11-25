package com.ritim.ritimcraw.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** Android JPEG implementation of [ScrubCodec]; not touched by JVM tests. */
class AndroidScrubCodec : ScrubCodec {
    override fun encode(argb: IntArray, width: Int, height: Int, quality: Int): ByteArray {
        val bmp = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        } finally { bmp.recycle() }
    }

    override fun decode(bytes: ByteArray): ScrubPixels? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val r = ScrubPixels(px, bmp.width, bmp.height)
        bmp.recycle()
        return r
    }
}
