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

@Suppress("UNCHECKED_CAST")
fun parseJson(text: String): Map<String, Any?> = json.readValue(text, Map::class.java) as Map<String, Any?>
