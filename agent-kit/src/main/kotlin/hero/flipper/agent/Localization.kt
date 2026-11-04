package hero.flipper.agent

/** The Android application supplies resources; JVM clients use the English source text. */
object AgentLocalization {
    @Volatile var resolve: (String, List<Any>) -> String = ::englishText
}

fun L(key: String, vararg arguments: Any): String = AgentLocalization.resolve(key, arguments.toList())

fun englishText(key: String, arguments: List<Any>): String {
    var index = 0
    return Regex("%(?:(\\d+)\\$)?(?:@|llu|lld|lu|ld|u|d|s|%)").replace(key) { match ->
        if (match.value == "%%") "%"
        else {
            val position = match.groupValues[1].toIntOrNull()?.minus(1) ?: index++
            arguments.getOrNull(position)?.toString() ?: match.value
        }
    }
}
