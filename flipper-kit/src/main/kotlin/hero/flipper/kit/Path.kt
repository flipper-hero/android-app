package hero.flipper.kit

object FlipperPath {
    val roots = listOf("/ext", "/int", "/any")
    const val maxLength = 255

    fun normalize(raw: String): String {
        if (raw.isEmpty()) throw FlipperError.InvalidPath("empty")
        if (raw.toByteArray(Charsets.UTF_8).size > maxLength) throw FlipperError.InvalidPath("too long")
        if (!raw.startsWith("/")) throw FlipperError.InvalidPath("must be absolute")
        for (ch in raw) {
            val value = ch.code
            if (value < 0x20 || value == 0x7F) throw FlipperError.InvalidPath("control character")
        }
        val parts = ArrayList<String>()
        for (part in raw.split('/')) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") throw FlipperError.InvalidPath("'..' is not allowed")
            parts.add(part)
        }
        val first = parts.firstOrNull() ?: throw FlipperError.InvalidPath("must be under /ext, /int or /any")
        if ("/$first" !in roots) throw FlipperError.InvalidPath("must be under /ext, /int or /any")
        return "/" + parts.joinToString("/")
    }

    fun lastComponent(path: String): String = path.split('/').lastOrNull { it.isNotEmpty() } ?: path

    fun parent(path: String): String {
        val comps = path.split('/').filter { it.isNotEmpty() }.dropLast(1)
        return "/" + comps.joinToString("/")
    }

    fun join(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"
}

/** Path policy shared by the device API and the agent. Blocked paths never leave the phone. */
object FlipperGuards {
    private val blockedRoots = listOf("/int")
    private val sensitiveExtensions = setOf("key", "pem", "priv", "secret", "p12")

    fun extension(path: String): String = path.substringAfterLast('.', "").lowercase()

    fun blockedRoot(path: String): Boolean {
        val normalized = try { FlipperPath.normalize(path) } catch (_: FlipperError) { return true }
        return blockedRoots.any { normalized == it || normalized.startsWith("$it/") }
    }

    /** Non-null when a read must not happen. */
    fun readBlock(path: String): String? {
        val normalized = try { FlipperPath.normalize(path) } catch (error: FlipperError) { return error.message }
        if (blockedRoots.any { normalized == it || normalized.startsWith("$it/") }) return "protected path (internal storage)"
        if (extension(normalized) in sensitiveExtensions) return "reading key or secret files is not allowed"
        return null
    }

    /** Non-null when a delete must not happen. */
    fun deleteBlock(path: String, recursive: Boolean): String? {
        val normalized = try { FlipperPath.normalize(path) } catch (error: FlipperError) { return error.message }
        if (blockedRoots.any { normalized == it || normalized.startsWith("$it/") }) return "protected path"
        val depth = normalized.split('/').count { it.isNotEmpty() }
        if (recursive && depth <= 2) return "refusing recursive delete of a top-level folder"
        return null
    }
}
