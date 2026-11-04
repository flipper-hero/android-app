package hero.flipper.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalizationContractTest {
    @Test fun allLanguagesHaveTheSameKeysAndUsableAndroidPlaceholders() {
        val folders = File("src/main/res").listFiles()!!.filter { File(it, "catalog.xml").isFile }
        assertEquals(15, folders.size)
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        var reference: Set<String>? = null
        for (folder in folders) {
            val strings = parser.parse(File(folder, "catalog.xml")).getElementsByTagName("string")
            val keys = HashSet<String>()
            for (index in 0 until strings.length) {
                val node = strings.item(index)
                keys.add(node.attributes.getNamedItem("name").nodeValue)
                val text = node.textContent
                assertFalse(Regex("%(?:\\d+\\$)?(?:@|lld|llu)").containsMatchIn(text), text)
                if (node.attributes.getNamedItem("formatted")?.nodeValue != "false") {
                    val slots = Regex("%(\\d+)[${'$'}]s").findAll(text).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0
                    val formatted = String.format(text, *Array(slots) { "value" })
                    assertFalse(formatted.contains("%1\$s"), formatted)
                }
            }
            assertTrue(keys.size >= 350)
            if (reference == null) reference = keys else assertEquals(reference, keys, folder.name)
            val android = parser.parse(File(folder, "android.xml")).getElementsByTagName("string")
            assertEquals(10, android.length, folder.name)
        }
    }
}
