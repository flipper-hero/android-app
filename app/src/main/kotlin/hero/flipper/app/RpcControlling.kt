package hero.flipper.app

import hero.flipper.agent.FlipperControlling
import hero.flipper.kit.FlipperApp
import hero.flipper.kit.FlipperDirEntry
import hero.flipper.kit.FlipperError
import hero.flipper.kit.FlipperGpioPin
import hero.flipper.kit.FlipperKey
import hero.flipper.kit.FlipperRpcClient
import hero.flipper.kit.FlipperScreenFrame
import hero.flipper.kit.FlipperStorageInfo
import kotlin.time.Duration

/** Forwards the agent device API to the connected RPC client. */
class RpcControlling(private val rpc: FlipperRpcClient) : FlipperControlling {
    override suspend fun deviceInfo(): Map<String, String> = rpc.deviceInfo()
    override suspend fun powerInfo(): Map<String, String> = rpc.powerInfo()
    override suspend fun storageInfo(path: String): FlipperStorageInfo = rpc.storageInfo(path)
    override suspend fun list(path: String): List<FlipperDirEntry> = rpc.list(path)
    override suspend fun stat(path: String): FlipperDirEntry = rpc.stat(path)
    override suspend fun read(path: String, maxBytes: Int): ByteArray = rpc.read(path, maxBytes)
    override suspend fun write(path: String, data: ByteArray) = rpc.write(path, data)
    override suspend fun makeDirectory(path: String) = rpc.makeDirectory(path)
    override suspend fun delete(path: String, recursive: Boolean) = rpc.delete(path, recursive)
    override suspend fun rename(from: String, to: String) = rpc.rename(from, to)
    override suspend fun startApp(name: String, args: String) = rpc.startApp(name, args)
    override suspend fun transmitOnce(app: FlipperApp, path: String, button: String, hold: Duration) =
        rpc.transmitOnce(app, path, button, hold)
    override suspend fun emulate(app: FlipperApp, path: String) = rpc.emulate(app, path)
    override suspend fun loadBadKeyboardScript(path: String) = rpc.loadBadKeyboardScript(path)
    override suspend fun exitApp() = rpc.exitApp()
    override suspend fun playAlert() = rpc.playAlert()
    override suspend fun setDeviceName(name: String) = rpc.setDeviceName(name)
    override suspend fun customDeviceName(): String? = rpc.customDeviceName()
    override suspend fun captureScreen(timeout: Duration): FlipperScreenFrame = rpc.captureScreen(timeout)
    override suspend fun press(key: FlipperKey, long: Boolean) = rpc.press(key, long)

    override suspend fun headerSetMode(pin: String, output: Boolean, pullUp: Boolean?) {
        rpc.headerSetMode(pinOf(pin), output, pullUp)
    }

    override suspend fun headerRead(pin: String): Boolean = rpc.headerRead(pinOf(pin))

    override suspend fun headerWrite(pin: String, level: Boolean) {
        rpc.headerWrite(pinOf(pin), level)
    }

    private fun pinOf(pin: String): FlipperGpioPin =
        FlipperGpioPin.parse(pin) ?: throw FlipperError.InvalidPath(pin)
}

/** The device the agent talks to. Swapped when Bluetooth connects or drops. */
class ActiveFlipper : FlipperControlling {
    var inner: FlipperControlling? = null

    private fun live(): FlipperControlling = inner ?: throw FlipperError.NotConnected()

    override suspend fun deviceInfo() = live().deviceInfo()
    override suspend fun powerInfo() = live().powerInfo()
    override suspend fun storageInfo(path: String) = live().storageInfo(path)
    override suspend fun list(path: String) = live().list(path)
    override suspend fun stat(path: String) = live().stat(path)
    override suspend fun read(path: String, maxBytes: Int) = live().read(path, maxBytes)
    override suspend fun write(path: String, data: ByteArray) = live().write(path, data)
    override suspend fun makeDirectory(path: String) = live().makeDirectory(path)
    override suspend fun delete(path: String, recursive: Boolean) = live().delete(path, recursive)
    override suspend fun rename(from: String, to: String) = live().rename(from, to)
    override suspend fun startApp(name: String, args: String) = live().startApp(name, args)
    override suspend fun transmitOnce(app: FlipperApp, path: String, button: String, hold: Duration) =
        live().transmitOnce(app, path, button, hold)
    override suspend fun emulate(app: FlipperApp, path: String) = live().emulate(app, path)
    override suspend fun loadBadKeyboardScript(path: String) = live().loadBadKeyboardScript(path)
    override suspend fun exitApp() = live().exitApp()
    override suspend fun playAlert() = live().playAlert()
    override suspend fun setDeviceName(name: String) = live().setDeviceName(name)
    override suspend fun customDeviceName() = live().customDeviceName()
    override suspend fun captureScreen(timeout: Duration) = live().captureScreen(timeout)
    override suspend fun press(key: FlipperKey, long: Boolean) = live().press(key, long)
    override suspend fun headerSetMode(pin: String, output: Boolean, pullUp: Boolean?) =
        live().headerSetMode(pin, output, pullUp)
    override suspend fun headerRead(pin: String) = live().headerRead(pin)
    override suspend fun headerWrite(pin: String, level: Boolean) = live().headerWrite(pin, level)
    override suspend fun sendRaw(request: String) = live().sendRaw(request)
    override suspend fun marauder(action: String, mode: String, seconds: Int, index: Int?) =
        live().marauder(action, mode, seconds, index)
}
