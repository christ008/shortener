package uy.ct.shortener

/**
 * Navigation of a parsed YAML or JSON tree (maps, lists and scalars) with checks instead of casts.
 *
 * - [asMap] and [asList] accept `null` as an empty one and fail with a message on any other type.
 * - [maps] keeps the objects of a list.
 * - [text] fails unless the member is a string.
 */
fun Any?.asMap(): Map<String, Any?> = when (this) {
    null -> emptyMap()
    is Map<*, *> -> entries.associate { (name, member) -> name.toString() to member }
    else -> error("expected a map, found ${this::class.simpleName}: $this")
}

fun Any?.asList(): List<Any?> = when (this) {
    null -> emptyList()
    is List<*> -> this
    else -> error("expected a list, found ${this::class.simpleName}: $this")
}

/** The objects of a list that mixes objects with scalars, such as the long and the short form of a Compose volume. */
fun List<Any?>.maps(): List<Map<String, Any?>> = filterIsInstance<Map<*, *>>().map { it.asMap() }

fun Map<String, Any?>.map(key: String): Map<String, Any?> = this[key].asMap()

fun Map<String, Any?>.list(key: String): List<Any?> = this[key].asList()

fun Map<String, Any?>.text(key: String): String = when (val member = this[key]) {
    is String -> member
    else -> error("expected a string at '$key', found $member")
}
