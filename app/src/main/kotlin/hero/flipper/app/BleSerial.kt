package hero.flipper.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import hero.flipper.kit.CreditWindow
import hero.flipper.kit.FlipperLog
import hero.flipper.kit.FlipperSerial
import hero.flipper.kit.FlipperTransport
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class FoundFlipper(val name: String, val rssi: Int, val device: BluetoothDevice)

/** Scan results that look like a Flipper, by advertised service or name. */
class FlipperScanCallback(private val onFound: (FoundFlipper) -> Unit) : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
        val name = result.device.name ?: result.scanRecord?.deviceName ?: return
        val uuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
        if (!FlipperSerial.isFlipperAdvertisement(uuids, name)) return
        onFound(FoundFlipper(name, result.rssi, result.device))
    }
}

/**
 * Flipper serial-service byte pipe. Writes only the serial write characteristic,
 * in credit-sized chunks, and refuses the RPC status characteristic.
 */
@SuppressLint("MissingPermission")
class BleSerialTransport(
    context: Context,
    device: BluetoothDevice,
) : FlipperTransport {
    private val incomingFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: Flow<ByteArray> = incomingFlow
    private val credits = CreditWindow()
    private val writeMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private var mtu = 20
    private var closed = false
    private var serialWrite: BluetoothGattCharacteristic? = null
    private var pendingWrite: CompletableDeferred<Unit>? = null
    private var space: CompletableDeferred<Unit>? = null
    private var gatt: BluetoothGatt? = null
    private var pendingRead: BluetoothGattCharacteristic? = null
    private var readNotificationsEnabled = false
    private var flowCreditsSeen = false
    var onDisconnected: (() -> Unit)? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(IllegalStateException("connection failed $status"))
                onDisconnected?.invoke()
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!gatt.requestMtu(247) && !gatt.discoverServices()) {
                    fail(IllegalStateException("service discovery could not start"))
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail(IllegalStateException("disconnected"))
                onDisconnected?.invoke()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleSerialTransport.mtu = mtu - 3
            if (!gatt.discoverServices()) fail(IllegalStateException("service discovery could not start"))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(IllegalStateException("service discovery failed $status"))
                return
            }
            val service = gatt.getService(UUID.fromString(FlipperSerial.service))
            val read = service?.getCharacteristic(UUID.fromString(FlipperSerial.serialRead))
            val write = service?.getCharacteristic(UUID.fromString(FlipperSerial.serialWrite))
            val flow = service?.getCharacteristic(UUID.fromString(FlipperSerial.flowControl))
            if (read == null || write == null || flow == null) {
                fail(IllegalStateException("serial service missing"))
                return
            }
            FlipperSerial.refuseStatusWrite(write.uuid.toString())
            serialWrite = write
            pendingRead = read
            enableNotify(gatt, flow)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(IllegalStateException("notification setup failed $status"))
                return
            }
            when (descriptor.characteristic.uuid.toString().lowercase()) {
                FlipperSerial.flowControl -> {
                    val read = pendingRead ?: return
                    pendingRead = null
                    enableNotify(gatt, read)
                }
                FlipperSerial.serialRead -> {
                    readNotificationsEnabled = true
                    val flow = gatt.getService(UUID.fromString(FlipperSerial.service))
                        ?.getCharacteristic(UUID.fromString(FlipperSerial.flowControl))
                    if (flow == null || !gatt.readCharacteristic(flow)) {
                        fail(IllegalStateException("could not read flow control"))
                    } else if (flowCreditsSeen && !ready.isCompleted) {
                        ready.complete(Unit)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onValue(characteristic, value)
        }

        @Deprecated("legacy callback")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onValue(characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) onValue(characteristic, value)
            else fail(IllegalStateException("flow control read failed $status"))
        }

        @Deprecated("legacy callback")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) onValue(characteristic, characteristic.value ?: return)
            else fail(IllegalStateException("flow control read failed $status"))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val pending = pendingWrite
            pendingWrite = null
            if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(Unit)
            else pending?.completeExceptionally(IllegalStateException("write failed $status"))
        }
    }

    init {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    suspend fun awaitReady() {
        withTimeout(20_000) { ready.await() }
    }

    override suspend fun send(data: ByteArray) {
        val target = serialWrite ?: throw IllegalStateException("not ready")
        FlipperSerial.refuseStatusWrite(target.uuid.toString())
        if (!target.uuid.toString().equals(FlipperSerial.writeTarget(), ignoreCase = true)) {
            throw IllegalStateException("refusing write to a characteristic other than serial write")
        }
        writeMutex.withLock {
            var offset = 0
            if (data.isEmpty()) {
                writeChunk(target, ByteArray(0))
                return@withLock
            }
            while (offset < data.size) {
                val granted = reserve(data.size - offset)
                writeChunk(target, data.copyOfRange(offset, offset + granted))
                offset += granted
            }
        }
    }

    override suspend fun close() {
        closed = true
        ready.completeExceptionally(IllegalStateException("closed"))
        pendingWrite?.completeExceptionally(IllegalStateException("closed"))
        space?.complete(Unit)
        gatt?.close()
        gatt = null
    }

    private fun onValue(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        when (characteristic.uuid.toString().lowercase()) {
            FlipperSerial.serialRead -> incomingFlow.tryEmit(value)
            FlipperSerial.flowControl -> {
                synchronized(credits) { credits.onNotification(value) }
                flowCreditsSeen = true
                space?.complete(Unit)
                space = null
                if (readNotificationsEnabled && !ready.isCompleted) ready.complete(Unit)
                FlipperLog.log { "flow control free space = ${credits.free()}" }
            }
        }
    }

    private suspend fun reserve(want: Int): Int {
        while (true) {
            if (closed) throw IllegalStateException("closed")
            val granted = synchronized(credits) { credits.reserve(want, mtu) }
            if (granted > 0) return granted
            val waiter = CompletableDeferred<Unit>()
            space = waiter
            waiter.await()
        }
    }

    private suspend fun writeChunk(characteristic: BluetoothGattCharacteristic, chunk: ByteArray) {
        val active = gatt ?: throw IllegalStateException("closed")
        FlipperSerial.refuseStatusWrite(characteristic.uuid.toString())
        val done = CompletableDeferred<Unit>()
        pendingWrite = done
        val started = if (Build.VERSION.SDK_INT >= 33) {
            active.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = chunk
            active.writeCharacteristic(characteristic)
        }
        if (!started) {
            pendingWrite = null
            throw IllegalStateException("write rejected")
        }
        withTimeout(10_000) { done.await() }
    }

    private fun enableNotify(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            fail(IllegalStateException("notifications unavailable"))
            return
        }
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG)
            ?: run { fail(IllegalStateException("notification descriptor missing")); return }
        if (Build.VERSION.SDK_INT >= 33) {
            if (gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothGatt.GATT_SUCCESS) {
                fail(IllegalStateException("notification request failed"))
            }
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!gatt.writeDescriptor(descriptor)) fail(IllegalStateException("notification request failed"))
        }
    }

    private fun fail(error: Exception) {
        if (!ready.isCompleted) ready.completeExceptionally(error)
        pendingWrite?.completeExceptionally(error)
        space?.complete(Unit)
    }

    companion object {
        private val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
