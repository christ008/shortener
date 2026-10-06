package uy.ct.shortener.tools

import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** A tool could not do what it was asked. The message is the one line it says on stderr, and the status is 1. */
class Failure(message: String) : RuntimeException(message)

/** Arguments a tool does not understand. The message and the tool's usage go to stderr, and the status is 2. */
class Usage(message: String) : RuntimeException(message)

/** A tool is done with this status, and has said what it had to say. */
class Exit(val status: Int) : RuntimeException(null, null, false, false)

/** What asks a person a question: the terminal when there is one. */
interface Prompt {
    fun line(question: String): String?

    fun secret(question: String): String?
}

/**
 * What a tool runs against: the directory, environment and streams of the process, or those a test provides.
 *
 * - [root] is the root of the repository. Paths a tool is given are resolved against it, so an absolute one is itself.
 * - [prompt] is null when nobody is there to ask. The tool then takes its defaults.
 */
class Context(
    val root: Path,
    val environment: Map<String, String>,
    val out: PrintStream,
    val err: PrintStream,
    val prompt: Prompt? = null,
) {
    fun path(path: String): Path = root.resolve(path)

    /** A setting of the environment that has a value: an unset one and an empty one are the same. */
    fun setting(name: String): String? = environment[name]?.takeIf { it.isNotBlank() }

    fun read(file: Path): String {
        if (!Files.isReadable(file)) throw Failure("cannot read $file")
        return Files.readString(file)
    }

    /** Writes the whole of [content] to [output] or leaves what was there, and gives the file the permissions asked. */
    fun writeAtomically(output: Path, content: String, permissions: String) {
        val next = Files.createTempFile(output.toAbsolutePath().parent, output.fileName.toString(), ".next")
        try {
            Files.writeString(next, content)
            Files.setPosixFilePermissions(next, PosixFilePermissions.fromString(permissions))
            Files.move(next, output, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(next)
        }
    }
}

/**
 * A tool of this directory.
 *
 * - [name] is what `tools/run` is given. [label] is what the tool calls itself in its messages (the script people know it by,
 *   when that is not its name).
 * - [execute] runs it and returns its status: 0 when it did what was asked, 1 when it could not ([Failure], or a file it could
 *   not read or write; one line on stderr), 2 for arguments it does not understand ([Usage]; the message and [usage] on
 *   stderr). A stack trace is a bug in the tool.
 */
abstract class Tool(val name: String, val usage: String, private val label: String = name) {

    protected abstract fun run(arguments: List<String>, context: Context)

    fun execute(arguments: List<String>, context: Context): Int =
        try {
            run(arguments, context)
            0
        } catch (exit: Exit) {
            exit.status
        } catch (problem: Usage) {
            context.err.println("$label: ${problem.message}")
            context.err.println(usage)
            2
        } catch (problem: Failure) {
            context.err.println("$label: ${problem.message}")
            1
        } catch (problem: IOException) {
            context.err.println("$label: ${problem.message}")
            1
        }
}

/** [text] as the inside of a JSON string. */
fun jsonString(text: String): String = buildString(text.length + 8) {
    for (c in text) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c.code < 0x20 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}
