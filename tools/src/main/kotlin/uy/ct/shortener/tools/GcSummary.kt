package uy.ct.shortener.tools

import uy.ct.shortener.tools.Report.d
import uy.ct.shortener.tools.Report.f
import java.nio.file.Files

/**
 * `Report gc`: how often a native image collects, how long it pauses and whether the live heap grows, from the lines that
 * `-XX:+PrintGC` writes to the application log.
 */
internal object GcSummary {

    private class Collection(val at: Double, val kind: String, val cause: String, val before: Double, val after: Double, val pauseMs: Double)

    private val LINE = Regex("\\[([\\d.]+)s\\] GC\\(\\d+\\) Pause (Incremental GC|Full GC) \\(([^)]*)\\) ([\\d.]+)M->([\\d.]+)M ([\\d.]+)ms")

    fun print(arguments: List<String>, context: Context) {
        if (arguments.size != 2) throw Usage("gc takes an application log")
        val all = Files.readAllLines(context.path(arguments[1])).mapNotNull { line ->
            LINE.find(line)?.destructured?.let { (at, kind, cause, before, after, pause) ->
                Collection(at.toDouble(), kind, cause, before.toDouble(), after.toDouble(), pause.toDouble())
            }
        }
        if (all.size < 2) throw Failure("${arguments[1]} has fewer than two GC lines: run the application with -XX:+PrintGC")
        val seconds = all.last().at - all.first().at
        if (seconds <= 0) throw Failure("${arguments[1]} has GC lines that span no time")

        val incremental = all.filter { it.kind == "Incremental GC" }
        val full = all.filter { it.kind == "Full GC" }
        val pauses = all.sumOf { it.pauseMs } / 1000
        context.out.println(
            "${all.size} collections in ${d(seconds, 0)}s: ${incremental.size} incremental (${d(incremental.size / seconds, 1)}/s), " +
                "${full.size} full (${d(full.size / seconds, 2)}/s)",
        )
        context.out.println("total pause ${d(pauses, 1)}s = ${d(pauses / seconds * 100, 1)}% of time; max pause ${d(all.maxOf { it.pauseMs }, 0)}ms")
        summarize("incremental", incremental, context)
        summarize("full", full, context)
        val causes = LinkedHashMap<String, Int>()
        all.forEach { causes.merge("${it.kind} (${it.cause})", 1, Int::plus) }
        context.out.println("causes: " + causes.entries.joinToString(", ") { "${it.key} ${it.value}" })
    }

    private fun summarize(name: String, kind: List<Collection>, context: Context) {
        if (kind.isEmpty()) return
        val quarter = maxOf(kind.size / 4, 1)
        val first = kind.subList(0, quarter).map { it.after }.average()
        val last = kind.subList(kind.size - quarter, kind.size).map { it.after }.average()
        context.out.println(
            f(
                "%s: live after GC first quarter avg %sMB, last quarter avg %sMB, max %sMB; avg pause %sms",
                name, d(first, 1), d(last, 1), d(kind.maxOf { it.after }, 1), d(kind.map { it.pauseMs }.average(), 1),
            ),
        )
    }
}
