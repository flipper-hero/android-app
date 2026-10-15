package hero.flipper.kit

import com.flipperdevices.protobuf.CommandStatus
import com.flipperdevices.protobuf.Main
import com.flipperdevices.protobuf.screen.InputKey
import com.flipperdevices.protobuf.screen.InputType
import com.flipperdevices.protobuf.screen.ScreenFrame
import com.flipperdevices.protobuf.storage.File
import com.flipperdevices.protobuf.storage.ListResponse
import com.flipperdevices.protobuf.storage.ReadResponse
import com.flipperdevices.protobuf.storage.StatResponse
import com.flipperdevices.protobuf.system.DeviceInfoResponse
import com.flipperdevices.protobuf.system.PingRequest
import com.flipperdevices.protobuf.system.PingResponse
import com.flipperdevices.protobuf.system.UpdateResponse
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private suspend fun makeClient(
    timeout: Duration = 5.seconds,
    responder: (Main) -> List<Main>,
): Pair<FlipperRpcClient, MockTransport> {
    val transport = MockTransport()
    transport.responder = responder
    val client = FlipperRpcClient(transport, timeout)
    client.start()
    return client to transport
}

class FramingTests {
    @Test
    fun varintRoundTrip() {
        for (value in listOf(0L, 1L, 127L, 128L, 300L, 16_383L, 16_384L, 1L shl 20)) {
            val bytes = Varint.encode(value.toULong())
            val decoded = Varint.decode(bytes) ?: error("missing $value")
            assertEquals(value.toULong(), decoded.first)
            assertEquals(bytes.size, decoded.second)
        }
    }

    @Test
    fun varintIncompleteReturnsNull() {
        assertNull(Varint.decode(byteArrayOf(0x80.toByte())))
    }

    @Test
    fun decoderReassemblesByteByByte() {
        val a = Main(command_id = 1, system_ping_request = PingRequest())
        val b = Main(command_id = 2, system_device_info_request = com.flipperdevices.protobuf.system.DeviceInfoRequest())
        val stream = FrameCodec.encode(a) + FrameCodec.encode(b)
        val decoder = FrameDecoder()
        val out = ArrayList<Main>()
        for (byte in stream) out += decoder.append(byteArrayOf(byte))
        assertEquals(listOf(1, 2), out.map { it.command_id })
    }

    @Test
    fun decoderRejectsOversizedFrame() {
        val decoder = FrameDecoder()
        val huge = Varint.encode((FrameCodec.maxFrameSize + 1).toULong())
        val error = assertFailsWith<FlipperError.FrameTooLarge> { decoder.append(huge) }
        assertEquals(FrameCodec.maxFrameSize + 1, error.size)
    }

    @Test
    fun decoderRejectsGarbageBody() {
        val decoder = FrameDecoder()
        val frame = Varint.encode(3u) + byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertFailsWith<FlipperError.MalformedFrame> { decoder.append(frame) }
    }

    @Test
    fun pathNormalization() {
        assertEquals("/ext/nfc/a.nfc", FlipperPath.normalize("/ext//nfc/./a.nfc"))
        assertEquals("/ext", FlipperPath.normalize("/ext/"))
        assertFailsWith<FlipperError.InvalidPath> { FlipperPath.normalize("/ext/../int/x") }
        assertFailsWith<FlipperError.InvalidPath> { FlipperPath.normalize("relative/path") }
        assertFailsWith<FlipperError.InvalidPath> { FlipperPath.normalize("/etc/passwd") }
        assertFailsWith<FlipperError.InvalidPath> { FlipperPath.normalize("/ext/a\nb") }
        assertFailsWith<FlipperError.InvalidPath> { FlipperPath.normalize("") }
        assertFailsWith<FlipperError.InvalidPath> { FlipperPath.normalize("/ext/" + "a".repeat(300)) }
    }
}

class FlowControlTests {
    @Test
    fun creditsAreBigEndian() {
        assertEquals(256, FlowControl.decodeCredits(byteArrayOf(0x00, 0x00, 0x01, 0x00)))
        assertEquals(65536, FlowControl.decodeCredits(byteArrayOf(0x00, 0x01, 0x00, 0x00)))
        assertNull(FlowControl.decodeCredits(byteArrayOf(0x00, 0x01)))
    }

    @Test
    fun windowSlicesWritesByCreditsAndMtu() {
        val window = CreditWindow()
        window.onNotification(byteArrayOf(0x00, 0x00, 0x01, 0x00))
        assertEquals(20, window.reserve(1000, attMtu = 10))
        assertEquals(236, window.free())
        assertEquals(100, window.reserve(100, attMtu = 200))
        assertEquals(136, window.free())
        assertEquals(0, window.reserve(0, attMtu = 20))
    }

    @Test
    fun statusCharacteristicIsNeverAWriteTarget() {
        assertEquals(FlipperSerial.serialWrite, FlipperSerial.writeTarget())
        assertFalse(FlipperSerial.writeTarget().contains("228e64fe0000"))
        assertFailsWith<IllegalArgumentException> { FlipperSerial.refuseStatusWrite(FlipperSerial.rpcStatus) }
        assertFailsWith<IllegalArgumentException> {
            FlipperSerial.refuseStatusWrite("19ED82AE-ED21-4C9D-4145-228E64FE0000")
        }
    }
}

class GuardTests {
    @Test
    fun blocksInternalStorageSecretsAndTopLevelRecursiveDeletes() {
        assertTrue(FlipperGuards.readBlock("/int") != null)
        assertTrue(FlipperGuards.readBlock("/int/.bt.keys") != null)
        assertTrue(FlipperGuards.readBlock("/ext/stuff/my.key") != null)
        for (ext in listOf("pem", "priv", "secret", "p12")) {
            assertTrue(FlipperGuards.readBlock("/ext/vault/id.$ext") != null, ext)
        }
        assertTrue(FlipperGuards.readBlock("/ext/../int/secret") != null)
        assertNull(FlipperGuards.readBlock("/ext/nfc/a.nfc"))

        assertTrue(FlipperGuards.deleteBlock("/ext", recursive = true)!!.contains("top-level"))
        assertTrue(FlipperGuards.deleteBlock("/ext/nfc", recursive = true)!!.contains("top-level"))
        assertNull(FlipperGuards.deleteBlock("/ext/nfc/a.nfc", recursive = true))
        assertTrue(FlipperGuards.deleteBlock("/int/x", recursive = false) != null)
        assertTrue(FlipperGuards.blockedRoot("/int/secret"))
    }
}

class RpcClientTests {
    @Test
    fun ping() = runTest {
        val (client, _) = makeClient { request ->
            val ping = request.system_ping_request ?: return@makeClient emptyList()
            listOf(reply(request, body = Main(system_ping_response = PingResponse(data_ = ping.data_))))
        }
        client.ping(byteArrayOf(9, 8, 7))
    }

    @Test
    fun deviceInfoAggregatesParts() = runTest {
        val (client, _) = makeClient { request ->
            fun part(key: String, value: String, next: Boolean) = reply(
                request,
                hasNext = next,
                body = Main(system_device_info_response = DeviceInfoResponse(key = key, value_ = value)),
            )
            listOf(
                part("hardware_name", "Laisear", true),
                part("firmware_commit", "d3f89dfe", true),
                part("firmware_version", "mntm-dev", false),
            )
        }
        val info = client.deviceInfo()
        assertEquals("Laisear", info["hardware_name"])
        assertEquals("d3f89dfe", info["firmware_commit"])
        assertEquals(3, info.size)
    }

    @Test
    fun listSortsDirectoriesFirst() = runTest {
        val (client, _) = makeClient { request ->
            fun file(name: String, dir: Boolean, size: Int = 0) =
                File(name = name, type = if (dir) File.FileType.DIR else File.FileType.FILE, size = size)
            listOf(
                reply(
                    request,
                    body = Main(
                        storage_list_response = ListResponse(
                            file_ = listOf(
                                file("b.sub", false, 10),
                                file("nfc", true),
                                file("a.sub", false, 5),
                            ),
                        ),
                    ),
                ),
            )
        }
        val entries = client.list("/ext")
        assertEquals(listOf("nfc", "a.sub", "b.sub"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
    }

    @Test
    fun readChecksSizeBeforeTransfer() = runTest {
        val (client, transport) = makeClient { request ->
            if (request.storage_stat_request == null) return@makeClient emptyList()
            val file = File(size = 10_000_000, type = File.FileType.FILE)
            listOf(reply(request, body = Main(storage_stat_response = StatResponse(file_ = file))))
        }
        val error = assertFailsWith<FlipperError.Rpc> { client.read("/ext/big.bin", maxBytes = 1024) }
        assertTrue(error.status.contains("10000000"))
        assertFalse(transport.requests.any { it.storage_read_request != null })
    }

    @Test
    fun readConcatenatesParts() = runTest {
        val (client, _) = makeClient { request ->
            when {
                request.storage_stat_request != null -> {
                    val file = File(size = 6, type = File.FileType.FILE)
                    listOf(reply(request, body = Main(storage_stat_response = StatResponse(file_ = file))))
                }
                request.storage_read_request != null -> {
                    fun chunk(text: String, next: Boolean) = reply(
                        request,
                        hasNext = next,
                        body = Main(
                            storage_read_response = ReadResponse(
                                file_ = File(data_ = text.encodeToByteArray().toByteString()),
                            ),
                        ),
                    )
                    listOf(chunk("abc", true), chunk("def", false))
                }
                else -> emptyList()
            }
        }
        assertEquals("abcdef", client.read("/ext/a.txt").decodeToString())
    }

    @Test
    fun rpcErrorStatusBecomesError() = runTest {
        val (client, _) = makeClient { request ->
            listOf(reply(request, status = CommandStatus.ERROR_STORAGE_NOT_EXIST))
        }
        val error = assertFailsWith<FlipperError.Rpc> { client.list("/ext/missing") }
        assertTrue(error.status.lowercase().contains("notexist"), error.status)
        assertFalse(error.status.contains("_"))
    }

    @Test
    fun timeout() = runTest {
        val (client, _) = makeClient(timeout = 150.milliseconds) { emptyList() }
        assertFailsWith<FlipperError.Timeout> { client.ping() }
    }

    @Test
    fun writeChunksShareCommandIdAndSetHasNext() = runTest {
        val (client, transport) = makeClient { request ->
            if (request.has_next) emptyList() else listOf(reply(request))
        }
        val payload = ByteArray(FlipperLimits.writeChunkSize * 2 + 100) { 0x41 }
        client.write("/ext/test.txt", payload)
        val writes = transport.requests.filter { it.storage_write_request != null }
        assertEquals(3, writes.size)
        assertEquals(1, writes.map { it.command_id }.toSet().size)
        assertEquals(listOf(true, true, false), writes.map { it.has_next })
        val total = writes.sumOf { it.storage_write_request?.file_?.data_?.size ?: 0 }
        assertEquals(payload.size, total)
    }

    @Test
    fun invalidPathNeverReachesTransport() = runTest {
        val (client, transport) = makeClient { emptyList() }
        assertFailsWith<FlipperError.InvalidPath> { client.delete("/ext/../int/secret", recursive = true) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun callsAreSerialized() = runTest {
        val (client, _) = makeClient { request ->
            val ping = request.system_ping_request ?: return@makeClient emptyList()
            listOf(reply(request, body = Main(system_ping_response = PingResponse(data_ = ping.data_))))
        }
        coroutineScope {
            repeat(10) { index ->
                launch { client.ping(byteArrayOf(index.toByte())) }
            }
        }
    }
}

class DeviceTests {
    @Test
    fun transmitOpensLoadsPressesReleasesAndCloses() = runTest {
        val flipper = SimulatedFlipper(mapOf("/ext/subghz/Garage.sub" to "Filetype: Flipper SubGhz Key File"))
        val client = flipper.client()
        client.transmitOnce(FlipperApp.SubGhz, "/ext/subghz/Garage.sub", hold = 1.milliseconds)
        assertEquals(
            listOf(
                "stat /ext/subghz/Garage.sub",
                "appStart Sub-GHz RPC",
                "appLoad /ext/subghz/Garage.sub",
                "appPress ",
                "appRelease",
                "appExit",
            ),
            flipper.log,
        )
    }

    @Test
    fun infraredPassesTheButtonName() = runTest {
        val flipper = SimulatedFlipper(mapOf("/ext/infrared/TV.ir" to "Filetype: IR signals file"))
        flipper.client().transmitOnce(FlipperApp.Infrared, "/ext/infrared/TV.ir", button = "Power", hold = 1.milliseconds)
        assertTrue(flipper.log.contains("appPress Power"))
    }

    @Test
    fun wrongFileTypeIsRejectedBeforeTouchingTheDevice() = runTest {
        val flipper = SimulatedFlipper(mapOf("/ext/nfc/Card.nfc" to "x"))
        assertFailsWith<FlipperError.InvalidPath> {
            flipper.client().transmitOnce(FlipperApp.SubGhz, "/ext/nfc/Card.nfc", hold = 1.milliseconds)
        }
        assertTrue(flipper.log.isEmpty())
    }

    @Test
    fun missingFileStopsBeforeStartingTheApp() = runTest {
        val flipper = SimulatedFlipper()
        assertFailsWith<FlipperError.Rpc> { flipper.client().emulate(FlipperApp.Nfc, "/ext/nfc/Missing.nfc") }
        assertEquals(listOf("stat /ext/nfc/Missing.nfc"), flipper.log)
    }

    @Test
    fun failedLoadClosesTheApp() = runTest {
        val flipper = SimulatedFlipper(mapOf("/ext/lfrfid/Tag.rfid" to "x"))
        flipper.failures["appLoad"] = CommandStatus.ERROR_INVALID_PARAMETERS
        assertFailsWith<FlipperError.Rpc> { flipper.client().emulate(FlipperApp.Rfid, "/ext/lfrfid/Tag.rfid") }
        assertEquals("appExit", flipper.log.last())
    }

    @Test
    fun failedPressStillClosesTheApp() = runTest {
        val flipper = SimulatedFlipper(mapOf("/ext/subghz/Gate.sub" to "x"))
        flipper.failures["appPress"] = CommandStatus.ERROR_INVALID_PARAMETERS
        assertFailsWith<FlipperError.Rpc> {
            flipper.client().transmitOnce(FlipperApp.SubGhz, "/ext/subghz/Gate.sub", hold = 1.milliseconds)
        }
        assertEquals("appExit", flipper.log.last())
        assertFalse(flipper.log.contains("appRelease"))
    }

    @Test
    fun emulationKeepsTheAppOpen() = runTest {
        val flipper = SimulatedFlipper(mapOf("/ext/ibutton/Door.ibtn" to "x"))
        val client = flipper.client()
        client.emulate(FlipperApp.IButton, "/ext/ibutton/Door.ibtn")
        assertEquals(
            listOf("stat /ext/ibutton/Door.ibtn", "appStart iButton RPC", "appLoad /ext/ibutton/Door.ibtn"),
            flipper.log,
        )
        client.exitApp()
        assertEquals("appExit", flipper.log.last())
    }

    @Test
    fun badKbIsOpenedWithTheScriptButNeverStarted() = runTest {
        val flipper = SimulatedFlipper(mapOf("/ext/badusb/Hello.txt" to "STRING hi"))
        flipper.client().loadBadKeyboardScript("/ext/badusb/Hello.txt")
        assertEquals(
            listOf("stat /ext/badusb/Hello.txt", "appStart Bad KB /ext/badusb/Hello.txt"),
            flipper.log,
        )
    }

    @Test
    fun appErrorIsReported() = runTest {
        val flipper = SimulatedFlipper()
        val client = flipper.client()
        assertNull(client.appError())
        flipper.appErrorText = "Invalid file"
        assertEquals("Invalid file", client.appError())
    }

    @Test
    fun rebootModesAndAlert() = runTest {
        val flipper = SimulatedFlipper()
        val client = flipper.client()
        client.reboot()
        client.rebootIntoUpdater()
        client.playAlert()
        assertEquals(listOf("reboot os", "reboot update", "alert"), flipper.log)
    }

    @Test
    fun rebootToleratesTheLinkDropping() = runTest {
        val flipper = SimulatedFlipper()
        flipper.transport.responder = { emptyList() }
        val client = flipper.client()
        flipper.transport.close()
        client.reboot()
    }

    @Test
    fun updateRequestResult() = runTest {
        val flipper = SimulatedFlipper()
        val client = flipper.client()
        val ok = client.requestUpdate("/ext/update/f7-update-mntm-012/update.fuf")
        assertTrue(ok is FlipperUpdateResult.Ok)
        flipper.updateResult = UpdateResponse.UpdateResultCode.TargetMismatch
        val rejected = client.requestUpdate("/ext/update/f7-update-mntm-012/update.fuf")
        assertEquals("targetMismatch", (rejected as FlipperUpdateResult.Rejected).why)
        assertEquals("update /ext/update/f7-update-mntm-012/update.fuf", flipper.log.first())
    }

    @Test
    fun writeSplitsIntoChunksAndRoundTrips() = runTest {
        val flipper = SimulatedFlipper()
        val client = flipper.client()
        val data = ByteArray(1300) { (it % 251).toByte() }
        var lastSent = -1
        client.write("/ext/test.bin", data) { sent, _ -> lastSent = sent }
        assertEquals(3, flipper.log.count { it == "write /ext/test.bin" })
        assertTrue(flipper.files["/ext/test.bin"]!!.contentEquals(data))
        assertEquals(1300, lastSent)
        assertTrue(client.read("/ext/test.bin").contentEquals(data))
    }

    @Test
    fun emptyFileIsWrittenAsOneRequest() = runTest {
        val flipper = SimulatedFlipper()
        flipper.client().write("/ext/empty.txt", ByteArray(0))
        assertTrue(flipper.files["/ext/empty.txt"]!!.isEmpty())
        assertEquals(1, flipper.log.count { it == "write /ext/empty.txt" })
    }

    @Test
    fun readRefusesDirectoriesAndLargeFiles() = runTest {
        val flipper = SimulatedFlipper(mapOf("/ext/big.bin" to "x".repeat(2000)))
        val client = flipper.client()
        assertFailsWith<FlipperError.InvalidPath> { client.read("/ext", maxBytes = 100) }
        assertFailsWith<FlipperError.Rpc> { client.read("/ext/big.bin", maxBytes = 100) }
        assertFalse(flipper.log.contains("read /ext/big.bin"))
    }

    @Test
    fun makeDirectoriesCreatesMissingParentsAndToleratesExisting() = runTest {
        val flipper = SimulatedFlipper(mapOf("/ext/update/old.txt" to "x"))
        flipper.client().makeDirectories("/ext/update/f7-update-mntm-012")
        assertTrue(flipper.dirs.contains("/ext/update/f7-update-mntm-012"))
        assertEquals(
            listOf("mkdir /ext/update", "mkdir /ext/update/f7-update-mntm-012"),
            flipper.log,
        )
    }

    @Test
    fun deleteRenameAndInfo() = runTest {
        val flipper = SimulatedFlipper(
            mapOf("/ext/a/one.txt" to "1", "/ext/a/two.txt" to "2", "/ext/b.txt" to "b"),
        )
        val client = flipper.client()
        client.rename("/ext/b.txt", "/ext/c.txt")
        client.delete("/ext/a", recursive = true)
        val info = client.storageInfo()
        assertEquals(setOf("/ext/c.txt"), flipper.files.keys)
        assertEquals(FlipperStorageInfo(1000u, 400u), info)
        assertTrue(flipper.log.contains("delete /ext/a recursive"))
    }

    @Test
    fun pathsOutsideTheSdCardNeverLeaveThePhone() = runTest {
        val flipper = SimulatedFlipper()
        val client = flipper.client()
        for (path in listOf("/ext/../int/secret", "relative.txt", "/etc/passwd")) {
            assertFailsWith<FlipperError.InvalidPath> { client.read(path) }
        }
        assertTrue(flipper.log.isEmpty())
    }

    @Test
    fun deviceNameIsWrittenAndReadBack() = runTest {
        val flipper = SimulatedFlipper()
        val client = flipper.client()
        assertNull(client.customDeviceName())
        client.setDeviceName("Hero")
        assertEquals("Hero", client.customDeviceName())
        assertEquals(
            "Filetype: Flipper Name File\nVersion: 1\nName: Hero\n",
            flipper.files[FlipperName.path]!!.decodeToString(),
        )
    }

    @Test
    fun deviceNameValidation() = runTest {
        assertEquals("Hero_1", FlipperName.validate("  Hero_1 "))
        val tooLong = assertFailsWith<FlipperName.NameError.TooLong> { FlipperName.validate("NineChars") }
        assertEquals(9, tooLong.n)
        assertFailsWith<FlipperName.NameError.Empty> { FlipperName.validate("") }
        assertFailsWith<FlipperName.NameError.InvalidCharacters> { FlipperName.validate("a b") }
        assertNull(FlipperName.parse("Filetype: Flipper Name File\nName: \n"))

        val flipper = SimulatedFlipper()
        assertFailsWith<FlipperName.NameError.TooLong> { flipper.client().setDeviceName("WayTooLongName") }
        assertNull(flipper.files[FlipperName.path])
    }
}

class ScreenTests {
    private fun buffer(points: List<Pair<Int, Int>>): ByteArray {
        val bytes = ByteArray(1024)
        for ((x, y) in points) {
            bytes[(y / 8) * 128 + x] = (bytes[(y / 8) * 128 + x].toInt() or (1 shl (y % 8))).toByte()
        }
        return bytes
    }

    @Test
    fun pageLayoutDecoding() {
        val frame = FlipperScreenFrame(buffer(listOf(0 to 0, 127 to 63, 5 to 9)))
        assertTrue(frame.pixel(0, 0))
        assertTrue(frame.pixel(127, 63))
        assertTrue(frame.pixel(5, 9))
        assertFalse(frame.pixel(5, 8))
        assertFalse(frame.pixel(6, 9))
        assertEquals(3, frame.pixels().count { it })
    }

    @Test
    fun flippedOrientationRotates180() {
        val frame = FlipperScreenFrame(buffer(listOf(0 to 0)), FlipperScreenFrame.Orientation.Flipped)
        assertTrue(frame.pixel(127, 63))
        assertFalse(frame.pixel(0, 0))
    }

    @Test
    fun verticalOrientationSwapsSize() {
        val frame = FlipperScreenFrame(buffer(emptyList()), FlipperScreenFrame.Orientation.Vertical)
        assertEquals(64 to 128, frame.displaySize)
    }

    @Test
    fun captureReceivesTheUnsolicitedFrame() = runTest {
        val transport = MockTransport()
        val pixelBuffer = buffer(listOf(10 to 10))
        transport.responder = { request ->
            if (request.gui_start_screen_stream_request != null) {
                val unsolicited = Main(
                    command_id = 0,
                    gui_screen_frame = ScreenFrame(data_ = pixelBuffer.toByteString()),
                )
                listOf(reply(request), unsolicited)
            } else {
                listOf(reply(request))
            }
        }
        val client = FlipperRpcClient(transport, 2.seconds)
        client.start()
        val frame = client.captureScreen(2.seconds)
        assertTrue(frame.pixel(10, 10))
        assertEquals(1, transport.requests.count { it.gui_stop_screen_stream_request != null })
    }

    @Test
    fun sharedStreamIsStartedOnceAndStoppedLast() = runTest {
        val transport = MockTransport()
        transport.responder = { listOf(reply(it)) }
        val client = FlipperRpcClient(transport, 2.seconds)
        client.start()
        client.acquireScreenStream()
        client.acquireScreenStream()
        client.releaseScreenStream()
        assertEquals(1, transport.requests.count { it.gui_start_screen_stream_request != null })
        assertEquals(0, transport.requests.count { it.gui_stop_screen_stream_request != null })
        client.releaseScreenStream()
        assertEquals(1, transport.requests.count { it.gui_stop_screen_stream_request != null })
    }

    @Test
    fun pressSendsPressShortRelease() = runTest {
        val transport = MockTransport()
        transport.responder = { listOf(reply(it)) }
        val client = FlipperRpcClient(transport, 2.seconds)
        client.start()
        client.press(FlipperKey.Ok)
        client.press(FlipperKey.Back, long = true)
        val events = transport.requests.mapNotNull { it.gui_send_input_event_request }
        assertEquals(
            listOf(InputType.PRESS, InputType.SHORT, InputType.RELEASE, InputType.PRESS, InputType.LONG, InputType.RELEASE),
            events.map { it.type },
        )
        assertEquals(
            listOf(InputKey.OK, InputKey.OK, InputKey.OK, InputKey.BACK, InputKey.BACK, InputKey.BACK),
            events.map { it.key },
        )
    }

    @Test
    fun pressSendsAllEventsBeforeWaitingForAnswers() = runTest {
        val transport = MockTransport()
        val held = ArrayList<Main>()
        transport.responder = { request ->
            held.add(request)
            if (held.size < 3) {
                emptyList()
            } else {
                val answers = held.map { reply(it) }
                held.clear()
                answers
            }
        }
        val client = FlipperRpcClient(transport, 1.seconds)
        client.start()
        client.press(FlipperKey.Down)
        client.press(FlipperKey.Up, long = true)
        assertEquals(6, transport.requests.size)
    }

    @Test
    fun pressReportsARejectedEvent() = runTest {
        val transport = MockTransport()
        transport.responder = { request ->
            val event = request.gui_send_input_event_request
            val status = if (event?.type == InputType.SHORT) CommandStatus.ERROR_INVALID_PARAMETERS else CommandStatus.OK
            listOf(reply(request, status = status))
        }
        val client = FlipperRpcClient(transport, 1.seconds)
        client.start()
        assertFailsWith<FlipperError.Rpc> { client.press(FlipperKey.Ok) }
        client.press(FlipperKey.Ok, long = true)
    }
}

class PackageTests {
    private fun packageBytes(target: String = "7", extra: List<Pair<String, ByteArray?>> = emptyList()): ByteArray {
        val manifest = "Filetype: Flipper firmware upgrade configuration\nVersion: 2\nInfo: test-1\nTarget: $target\n"
            .encodeToByteArray()
        val entries = listOf(
            "f7-update-test-1/" to null,
            "f7-update-test-1/firmware.dfu" to ByteArray(3000) { 1 },
            "f7-update-test-1/update.fuf" to manifest,
            "f7-update-test-1/updater.bin" to ByteArray(700) { 2 },
        ) + extra
        return gzipRaw(tar(entries))
    }

    @Test
    fun extractsAnArchive() {
        val entries = TarGz.extract(packageBytes())
        assertEquals(
            listOf(
                "f7-update-test-1/firmware.dfu",
                "f7-update-test-1/update.fuf",
                "f7-update-test-1/updater.bin",
            ),
            entries.map { it.path }.sorted(),
        )
        assertEquals(3000, entries.first { it.path.endsWith("firmware.dfu") }.data.size)
    }

    @Test
    fun rejectsNonGzip() {
        assertFailsWith<TarGz.ArchiveError.NotGzip> {
            TarGz.extract("not an archive at all, just text".encodeToByteArray())
        }
    }

    @Test
    fun packageValidation() {
        val parsed = FirmwareUpdatePackage.parse("test-1", TarGz.extract(packageBytes()))
        assertEquals("f7-update-test-1", parsed.folder)
        assertEquals("7", parsed.target)
        assertEquals("update.fuf", parsed.files.last().path)
        assertEquals("/ext/update/f7-update-test-1/update.fuf", parsed.manifestPath)

        assertFailsWith<FirmwareUpdatePackage.PackageError> {
            FirmwareUpdatePackage.parse("x", TarGz.extract(packageBytes(extra = listOf("f7-update-test-1/../../int/evil" to byteArrayOf(1)))))
        }
        assertFailsWith<FirmwareUpdatePackage.PackageError> {
            FirmwareUpdatePackage.parse("x", TarGz.extract(packageBytes(extra = listOf("other/file.bin" to byteArrayOf(1)))))
        }
        val noManifest = gzipRaw(tar(listOf("f7-x/firmware.dfu" to byteArrayOf(1))))
        assertFailsWith<FirmwareUpdatePackage.PackageError.MissingManifest> {
            FirmwareUpdatePackage.parse("x", TarGz.extract(noManifest))
        }
    }
}
