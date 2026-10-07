package uy.ct.shortener.tools

import java.io.Console
import java.nio.file.Path
import kotlin.system.exitProcess

/** The tools there are, by the name `tools/run` is given. */
private val TOOLS: List<Tool> = listOf(Realms, DevSetup, Smoke, Report, Dataset)

/**
 * Starts the tool named by the first argument, with the remaining arguments, in the current directory (the root of the repository).
 * The exit status is the tool's, or 2 when there is no such tool.
 */
fun main(arguments: Array<String>) {
    val name = arguments.firstOrNull()
    val tool = TOOLS.firstOrNull { it.name == name }
    if (tool == null) {
        System.err.println("tools: there is no tool ${name ?: "(none given)"}; the tools are ${TOOLS.joinToString(", ") { it.name }}")
        exitProcess(2)
    }
    val context = Context(Path.of("").toAbsolutePath(), System.getenv(), System.out, System.err, terminal())
    exitProcess(tool.execute(arguments.drop(1), context))
}

/** The person at the terminal, or null when the tool's input or output is a file or a pipe. */
private fun terminal(): Prompt? {
    val console = System.console() ?: return null
    // Console.isTerminal exists from JDK 22. Before it, there is a console only when there is a terminal.
    val isTerminal = try {
        Console::class.java.getMethod("isTerminal").invoke(console) as Boolean
    } catch (_: NoSuchMethodException) {
        true
    }
    if (!isTerminal) return null
    return object : Prompt {
        override fun line(question: String): String? = console.readLine("%s: ", question)

        override fun secret(question: String): String? = console.readPassword("%s: ", question)?.let { String(it) }
    }
}
