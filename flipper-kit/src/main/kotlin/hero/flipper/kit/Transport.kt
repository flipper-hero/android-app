package hero.flipper.kit

import kotlinx.coroutines.flow.Flow

/** Byte pipe to a Flipper. Implementations own chunking and flow control. */
interface FlipperTransport {
    val incoming: Flow<ByteArray>
    suspend fun send(data: ByteArray)
    suspend fun close()
}

object FlipperLog {
    var sink: ((String) -> Unit)? = null
    fun log(message: () -> String) { sink?.invoke(message()) }
}
