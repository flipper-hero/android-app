package hero.flipper.kit

import java.util.zip.Inflater

/** Minimal gzip + ustar reader for firmware update packages. */
object TarGz {
    data class Entry(val path: String, val data: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Entry && path == other.path && data.contentEquals(other.data)
        override fun hashCode(): Int = 31 * path.hashCode() + data.contentHashCode()
    }

    sealed class ArchiveError(message: String) : Exception(message) {
        class NotGzip : ArchiveError("The package is not a gzip archive")
        class Corrupt(val why: String) : ArchiveError("The package is damaged: $why")
        class TooLarge : ArchiveError("The package is unexpectedly large")
    }

    const val maxUnpackedBytes = 64 * 1024 * 1024

    fun extract(gz: ByteArray): List<Entry> = untar(gunzip(gz))

    fun gunzip(data: ByteArray): ByteArray {
        if (data.size <= 18 || data[0] != 0x1F.toByte() || data[1] != 0x8B.toByte() || data[2] != 8.toByte()) {
            throw ArchiveError.NotGzip()
        }
        val flags = data[3].toInt() and 0xFF
        var offset = 10
        if (flags and 0x04 != 0) {
            if (offset + 2 > data.size) throw ArchiveError.Corrupt("extra field")
            offset += 2 + (data[offset].toInt() and 0xFF) + ((data[offset + 1].toInt() and 0xFF) shl 8)
        }
        if (flags and 0x08 != 0) {
            while (offset < data.size && data[offset] != 0.toByte()) offset++
            offset++
        }
        if (flags and 0x10 != 0) {
            while (offset < data.size && data[offset] != 0.toByte()) offset++
            offset++
        }
        if (flags and 0x02 != 0) offset += 2
        if (offset >= data.size - 8) throw ArchiveError.Corrupt("header")
        val deflated = data.copyOfRange(offset, data.size - 8)
        val inflated = inflate(deflated)
        if (inflated.size > maxUnpackedBytes) throw ArchiveError.TooLarge()
        return inflated
    }

    private fun inflate(deflated: ByteArray): ByteArray {
        val raw = Inflater(true)
        val wrapped = Inflater(false)
        try {
            raw.setInput(deflated)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!raw.finished()) {
                val n = raw.inflate(buf)
                if (n == 0 && raw.needsInput()) break
                if (n > 0) out.write(buf, 0, n) else break
            }
            if (out.size() > 0 && raw.finished()) return out.toByteArray()
        } catch (_: Exception) {
        } finally {
            raw.end()
        }
        try {
            wrapped.setInput(deflated)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!wrapped.finished()) {
                val n = wrapped.inflate(buf)
                if (n == 0 && wrapped.needsInput()) break
                if (n > 0) out.write(buf, 0, n) else break
            }
            if (out.size() == 0) throw ArchiveError.Corrupt("deflate stream")
            return out.toByteArray()
        } catch (error: ArchiveError) {
            throw error
        } catch (_: Exception) {
            throw ArchiveError.Corrupt("deflate stream")
        } finally {
            wrapped.end()
        }
    }

    fun untar(tar: ByteArray): List<Entry> {
        val entries = ArrayList<Entry>()
        var offset = 0
        while (offset + 512 <= tar.size) {
            val header = tar.copyOfRange(offset, offset + 512)
            if (header.all { it == 0.toByte() }) break
            val name = field(header, 0, 100)
            val prefix = field(header, 345, 155)
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            val sizeText = field(header, 124, 12).trim()
            val size = sizeText.toIntOrNull(8) ?: throw ArchiveError.Corrupt("size of $path")
            val type = header[156]
            val dataStart = offset + 512
            if (dataStart + size > tar.size) throw ArchiveError.Corrupt("truncated $path")
            if (type == '0'.code.toByte() || type == 0.toByte()) {
                entries.add(Entry(path, tar.copyOfRange(dataStart, dataStart + size)))
            }
            offset = dataStart + ((size + 511) / 512) * 512
        }
        return entries
    }

    private fun field(header: ByteArray, start: Int, length: Int): String {
        val slice = header.copyOfRange(start, start + length)
        val end = slice.indexOf(0).let { if (it < 0) slice.size else it }
        return slice.copyOfRange(0, end).toString(Charsets.UTF_8)
    }
}

class FirmwareUpdatePackage(
    val release: String,
    val folder: String,
    val files: List<TarGz.Entry>,
    val target: String,
) {
    val totalBytes: Int get() = files.sumOf { it.data.size }
    val manifestPath: String get() = "/ext/update/$folder/update.fuf"

    sealed class PackageError(message: String) : Exception(message) {
        class Layout(val why: String) : PackageError("The update package has an unexpected layout: $why")
        class MissingManifest : PackageError("The update package has no update.fuf manifest")
        class WrongTarget(val packageTarget: String, val device: String) :
            PackageError("The package is for hardware target $packageTarget, this Flipper is target $device")

        override fun equals(other: Any?): Boolean {
            if (other !is PackageError) return false
            return when {
                this is Layout && other is Layout -> why == other.why
                this is WrongTarget && other is WrongTarget -> packageTarget == other.packageTarget && device == other.device
                else -> this::class == other::class
            }
        }
        override fun hashCode(): Int = this::class.hashCode()
    }

    companion object {
        fun parse(release: String, entries: List<TarGz.Entry>): FirmwareUpdatePackage {
            var folder: String? = null
            val files = ArrayList<TarGz.Entry>()
            for (entry in entries) {
                val parts = entry.path.split('/').filter { it.isNotEmpty() }
                if (parts.size != 2) throw PackageError.Layout("${entry.path} is not directly inside one folder")
                val dir = parts[0]
                val name = parts[1]
                for (part in parts) {
                    if (part == ".." || part == "." || part.startsWith(".") || part.contains("\\")) {
                        throw PackageError.Layout("unsafe path ${entry.path}")
                    }
                }
                fun ok(part: String) = part.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
                if (!ok(name) || !ok(dir)) throw PackageError.Layout("unexpected characters in ${entry.path}")
                if (folder != null && folder != dir) throw PackageError.Layout("more than one top-level folder")
                folder = dir
                files.add(TarGz.Entry(name, entry.data))
            }
            val resolved = folder ?: throw PackageError.Layout("empty package")
            if (files.isEmpty()) throw PackageError.Layout("empty package")
            val manifest = files.firstOrNull { it.path == "update.fuf" } ?: throw PackageError.MissingManifest()
            val text = manifest.data.toString(Charsets.UTF_8)
            val target = text.lineSequence().firstOrNull { it.startsWith("Target:") }
                ?.removePrefix("Target:")?.trim().orEmpty()
            val ordered = files.sortedWith { lhs, rhs ->
                when {
                    lhs.path == "update.fuf" -> 1
                    rhs.path == "update.fuf" -> -1
                    else -> lhs.path.compareTo(rhs.path)
                }
            }
            return FirmwareUpdatePackage(release, resolved, ordered, target)
        }
    }
}
