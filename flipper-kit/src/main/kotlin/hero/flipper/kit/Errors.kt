package hero.flipper.kit

sealed class FlipperError(message: String) : Exception(message) {
    class NotConnected : FlipperError("Flipper is not connected")
    class Timeout : FlipperError("Flipper did not answer in time")
    class FrameTooLarge(val size: Int) : FlipperError("Frame of $size bytes exceeds the limit")
    class MalformedFrame : FlipperError("Malformed RPC frame")
    class Rpc(val status: String) : FlipperError("Flipper RPC error: $status")
    class InvalidPath(val why: String) : FlipperError("Invalid path: $why")
    class UnexpectedResponse : FlipperError("Unexpected response from Flipper")

    override fun equals(other: Any?): Boolean {
        if (other !is FlipperError) return false
        return when {
            this is FrameTooLarge && other is FrameTooLarge -> size == other.size
            this is Rpc && other is Rpc -> status == other.status
            this is InvalidPath && other is InvalidPath -> why == other.why
            else -> this::class == other::class
        }
    }

    override fun hashCode(): Int = when (this) {
        is FrameTooLarge -> size
        is Rpc -> status.hashCode()
        is InvalidPath -> why.hashCode()
        else -> this::class.hashCode()
    }
}

object FlipperLimits {
    const val writeChunkSize = 512
    const val defaultMaxReadBytes = 64 * 1024
}

/**
 * SwiftProtobuf case name for a proto enum constant.
 * `ERROR_STORAGE_NOT_EXIST` → `errorStorageNotExist`, `OS` → `os`, `TargetMismatch` → `targetMismatch`.
 */
fun wireEnumLabel(name: String): String {
    if ('_' in name) {
        return name.split('_').filter { it.isNotEmpty() }.mapIndexed { index, part ->
            val lower = part.lowercase()
            if (index == 0) lower else lower.replaceFirstChar { it.uppercase() }
        }.joinToString("")
    }
    if (name.all { !it.isLetter() || it.isUpperCase() }) return name.lowercase()
    return name.replaceFirstChar { it.lowercase() }
}
