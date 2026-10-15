package hero.flipper.kit

/** One frame of the Flipper's 128x64 monochrome display, u8g2 page layout. */
class FlipperScreenFrame(
    val buffer: ByteArray,
    val orientation: Orientation = Orientation.Normal,
) {
    enum class Orientation { Normal, Flipped, Vertical, VerticalFlipped }

    init {
        require(buffer.size >= width * height / 8)
    }

    val displaySize: Pair<Int, Int>
        get() = when (orientation) {
            Orientation.Normal, Orientation.Flipped -> width to height
            Orientation.Vertical, Orientation.VerticalFlipped -> height to width
        }

    fun rawPixel(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        val index = (y / 8) * width + x
        return buffer[index].toInt() and (1 shl (y % 8)) != 0
    }

    fun pixel(x: Int, y: Int): Boolean = when (orientation) {
        Orientation.Normal -> rawPixel(x, y)
        Orientation.Flipped -> rawPixel(width - 1 - x, height - 1 - y)
        Orientation.Vertical -> rawPixel(width - 1 - y, x)
        Orientation.VerticalFlipped -> rawPixel(y, height - 1 - x)
    }

    fun pixels(): BooleanArray {
        val (w, h) = displaySize
        val out = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = pixel(x, y)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (other !is FlipperScreenFrame) return false
        return orientation == other.orientation && buffer.contentEquals(other.buffer)
    }

    override fun hashCode(): Int = 31 * buffer.contentHashCode() + orientation.hashCode()

    companion object {
        const val width = 128
        const val height = 64
    }
}

enum class FlipperKey { Up, Down, Left, Right, Ok, Back;
    val wire: String get() = name.lowercase()
}

enum class FlipperApp(val title: String, val fileExtension: String) {
    SubGhz("Sub-GHz", "sub"),
    Infrared("Infrared", "ir"),
    Rfid("125 kHz RFID", "rfid"),
    Nfc("NFC", "nfc"),
    IButton("iButton", "ibtn"),
    BadKeyboard("Bad KB", "txt"),
}

data class FlipperDirEntry(val name: String, val isDirectory: Boolean, val size: Long)

data class FlipperStorageInfo(val totalSpace: ULong, val freeSpace: ULong)

sealed class FlipperUpdateResult {
    data object Ok : FlipperUpdateResult()
    data class Rejected(val why: String) : FlipperUpdateResult()
}

object FlipperName {
    const val path = "/ext/dolphin/name.settings"
    const val maxLength = 8

    sealed class NameError(message: String) : Exception(message) {
        class TooLong(val n: Int) : NameError("The name is $n characters; the Flipper allows at most $maxLength.")
        class Empty : NameError("The name is empty.")
        class InvalidCharacters : NameError("Use only letters, digits, hyphen and underscore.")
    }

    fun validate(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw NameError.Empty()
        if (trimmed.length > maxLength) throw NameError.TooLong(trimmed.length)
        if (!trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }) throw NameError.InvalidCharacters()
        return trimmed
    }

    fun fileContents(name: String) = "Filetype: Flipper Name File\nVersion: 1\nName: $name\n"

    fun parse(contents: String): String? {
        for (line in contents.lineSequence()) {
            if (!line.startsWith("Name:")) continue
            val value = line.removePrefix("Name:").trim()
            return value.ifEmpty { null }
        }
        return null
    }
}

enum class FlipperGpioPin { Pc0, Pc1, Pc3, Pb2, Pb3, Pa4, Pa6, Pa7;
    val wire: String get() = name.lowercase()
    companion object {
        fun parse(raw: String): FlipperGpioPin? = entries.firstOrNull { it.wire == raw.lowercase() }
    }
}
