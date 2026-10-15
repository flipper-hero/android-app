package hero.flipper.kit

import com.flipperdevices.protobuf.Main
import okio.Buffer

object Varint {
    fun encode(value: ULong): ByteArray {
        var v = value
        val out = ArrayList<Byte>(10)
        do {
            var byte = (v and 0x7Fu).toInt()
            v = v shr 7
            if (v != 0uL) byte = byte or 0x80
            out.add(byte.toByte())
        } while (v != 0uL)
        return out.toByteArray()
    }

    /** Returns value and bytes consumed, or null if the buffer ends mid-varint. */
    fun decode(bytes: ByteArray, start: Int = 0): Pair<ULong, Int>? {
        var result = 0uL
        var shift = 0
        var index = start
        while (index < bytes.size) {
            val byte = bytes[index].toInt() and 0xFF
            result = result or ((byte and 0x7F).toULong() shl shift)
            index += 1
            if (byte and 0x80 == 0) return result to (index - start)
            shift += 7
            if (shift > 28) throw FlipperError.MalformedFrame()
        }
        return null
    }
}

object FrameCodec {
    const val maxFrameSize = 1 shl 20

    fun encode(message: Main): ByteArray {
        val body = message.encode()
        val prefix = Varint.encode(body.size.toULong())
        return prefix + body
    }
}

class FrameDecoder {
    private var buffer = ByteArray(0)

    fun append(chunk: ByteArray): List<Main> {
        buffer += chunk
        val messages = ArrayList<Main>()
        while (true) {
            val decoded = try {
                Varint.decode(buffer, 0)
            } catch (error: FlipperError) {
                buffer = ByteArray(0)
                throw error
            } ?: break
            val length = decoded.first
            val prefix = decoded.second
            if (length > FrameCodec.maxFrameSize.toULong()) {
                buffer = ByteArray(0)
                throw FlipperError.FrameTooLarge(length.toInt())
            }
            val total = prefix + length.toInt()
            if (buffer.size < total) break
            val body = buffer.copyOfRange(prefix, total)
            buffer = buffer.copyOfRange(total, buffer.size)
            try {
                messages.add(Main.ADAPTER.decode(body))
            } catch (_: Exception) {
                buffer = ByteArray(0)
                throw FlipperError.MalformedFrame()
            }
        }
        return messages
    }
}

fun Main.encode(): ByteArray {
    val buffer = Buffer()
    Main.ADAPTER.encode(buffer, this)
    return buffer.readByteArray()
}
