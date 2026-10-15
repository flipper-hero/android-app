package hero.flipper.kit

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** PNG of a Flipper frame: orange backlight, dark ink, scaled for previews. */
object ScreenPng {
    private val backlight = byteArrayOf(255.toByte(), 130.toByte(), 0)
    private val ink = byteArrayOf(22, 14, 6)

    fun png(frame: FlipperScreenFrame, scale: Int = 4): ByteArray {
        val (srcW, srcH) = frame.displaySize
        val width = srcW * scale
        val height = srcH * scale
        val pixels = frame.pixels()
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0)
            val srcY = y / scale
            for (x in 0 until width) {
                val on = pixels[srcY * srcW + (x / scale)]
                raw.write(if (on) ink else backlight)
            }
        }
        val deflater = Deflater()
        val compressed = try {
            deflater.setInput(raw.toByteArray())
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
        val png = ByteArrayOutputStream()
        png.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        png.write(chunk("IHDR", ihdr(width, height)))
        png.write(chunk("IDAT", compressed))
        png.write(chunk("IEND", ByteArray(0)))
        return png.toByteArray()
    }

    private fun ihdr(width: Int, height: Int): ByteArray {
        val data = ByteArrayOutputStream()
        data.write(int4(width))
        data.write(int4(height))
        data.write(byteArrayOf(8, 2, 0, 0, 0))
        return data.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(int4(data.size))
        val typeBytes = type.encodeToByteArray()
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.write(int4(crc.value.toInt()))
        return out.toByteArray()
    }

    private fun int4(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
