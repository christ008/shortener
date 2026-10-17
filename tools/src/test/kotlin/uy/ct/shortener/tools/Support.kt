package uy.ct.shortener.tools

import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

/** What a tool did: its status and what it said. */
class Output(val status: Int, val stdout: String, val stderr: String)

/** The root of the repository, whose templates and keys the tools read. Gradle runs the tests from `tools/`. */
val repositoryRoot: Path = Path.of(System.getProperty("repository.root")).toRealPath()

/** Runs [tool] as `tools/run` would, in process, against [root] and an environment that is only what the test gives it. */
fun run(
    tool: Tool,
    vararg arguments: String,
    environment: Map<String, String> = emptyMap(),
    root: Path = repositoryRoot,
    prompt: Prompt? = null,
): Output {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val status = PrintStream(stdout, true, Charsets.UTF_8).use { out ->
        PrintStream(stderr, true, Charsets.UTF_8).use { err ->
            tool.execute(arguments.toList(), Context(root, environment, out, err, prompt))
        }
    }
    return Output(status, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
}

private val json = JsonMapper.builder().build()

fun parseJson(text: String): Map<String, Any?> = json.readValue(text, Any::class.java).asMap()

/**
 * Navigation of a parsed JSON tree (maps, lists and scalars) with checks instead of casts.
 *
 * - [asMap] and [asList] accept `null` as an empty one and fail with a message on any other type.
 * - [text] fails unless the member is a string.
 */
fun Any?.asMap(): Map<String, Any?> = when (this) {
    null -> emptyMap()
    is Map<*, *> -> entries.associate { (name, member) -> name.toString() to member }
    else -> error("expected an object, found ${this::class.simpleName}: $this")
}

fun Any?.asList(): List<Any?> = when (this) {
    null -> emptyList()
    is List<*> -> this
    else -> error("expected an array, found ${this::class.simpleName}: $this")
}

fun Map<String, Any?>.map(key: String): Map<String, Any?> = this[key].asMap()

fun Map<String, Any?>.list(key: String): List<Any?> = this[key].asList()

fun Map<String, Any?>.text(key: String): String = when (val member = this[key]) {
    is String -> member
    else -> error("expected a string at '$key', found $member")
}

/** A list of objects by the string member [key] of each. */
fun List<Any?>.byText(key: String): Map<String, Map<String, Any?>> = map { it.asMap() }.associateBy { it.text(key) }
