package uy.ct.shortener.tools

import java.nio.file.Path
import kotlin.system.exitProcess

/** The tools there are, by the name `tools/run` is given. */
private val TOOLS: List<Tool> = listOf(Realms)

/**
 * Starts the tool named by the first argument, from the root of the repository, with the rest. `tools/run` is how a person, a
 * script or a workflow gets here: it builds this when a source has changed.
 */
fun main(arguments: Array<String>) {
    val name = arguments.firstOrNull()
    val tool = TOOLS.firstOrNull { it.name == name }
    if (tool == null) {
        System.err.println("tools: there is no tool ${name ?: "(none given)"}; the tools are ${TOOLS.joinToString(", ") { it.name }}")
        exitProcess(2)
    }
    val context = Context(Path.of("").toAbsolutePath(), System.getenv(), System.out, System.err)
    exitProcess(tool.execute(arguments.drop(1), context))
}
