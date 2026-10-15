package hero.flipper.kit

/**
 * Flipper serial-service identifiers and the flow-control credit window.
 * The RPC status characteristic is never a write target.
 */
object FlipperSerial {
    const val service = "8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000"
    const val serialRead = "19ed82ae-ed21-4c9d-4145-228e61fe0000"
    const val serialWrite = "19ed82ae-ed21-4c9d-4145-228e62fe0000"
    const val flowControl = "19ed82ae-ed21-4c9d-4145-228e63fe0000"
    /** Writing this restarts the Flipper Bluetooth profile. Never write it. */
    const val rpcStatus = "19ed82ae-ed21-4c9d-4145-228e64fe0000"
    val advertised = listOf("3080", "3081", "3082", "3083")

    fun isFlipperAdvertisement(serviceUuids: List<String>, name: String): Boolean {
        val ids = serviceUuids.map { it.lowercase() }
        if (ids.any { short -> advertised.any { short.endsWith(it) || short == it } }) return true
        return name.startsWith("Flipper")
    }

    /** The only characteristic the transport may write. */
    fun writeTarget(): String {
        check(!serialWrite.contains("228e64fe0000", ignoreCase = true))
        check(!serialWrite.equals(rpcStatus, ignoreCase = true))
        return serialWrite
    }

    fun refuseStatusWrite(uuid: String) {
        val normalized = uuid.lowercase()
        if (normalized == rpcStatus || normalized.contains("228e64fe0000")) {
            throw IllegalArgumentException("refusing write to RPC status characteristic")
        }
    }
}

object FlowControl {
    /** Big-endian UInt32 of free buffer bytes on the Flipper. */
    fun decodeCredits(bytes: ByteArray): Int? {
        if (bytes.size < 4) return null
        return ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
    }
}

/** Tracks free buffer credits and slices outgoing writes to the credit and the ATT MTU. */
class CreditWindow(private var freeSpace: Int = 0) {
    fun onNotification(bytes: ByteArray) {
        FlowControl.decodeCredits(bytes)?.let { freeSpace = it }
    }

    fun free(): Int = freeSpace

    /** Returns bytes that may be written now, or 0 when the window is empty. */
    fun reserve(want: Int, attMtu: Int): Int {
        if (freeSpace <= 0 || want <= 0) return 0
        val mtu = maxOf(20, attMtu)
        val n = minOf(want, freeSpace, mtu)
        freeSpace -= n
        return n
    }
}
