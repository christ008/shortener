package uy.ct.shortener.tools

import java.io.BufferedOutputStream
import java.util.Arrays
import java.util.Locale
import java.util.stream.Gatherers
import java.util.stream.IntStream

/**
 * Generates distinct short codes of the shape the application generates (7 base62 characters), in parallel, for the benchmarks
 * that read links the redirect cache has not seen.
 *
 *     tools/run Dataset COUNT [--seed N] [--threads N] [--out FILE] [--verify]
 *
 * - COUNT is a number, with an optional `K`, `M` or `B` (thousand, million, billion): `10M`. At most 62^7, the whole space.
 * - The codes are the first COUNT values of a [CodePermutation], so they are distinct by construction, and a bigger dataset
 *   of the same `--seed` (default 1) starts with the smaller one. Custom codes are not generated.
 * - A stream of chunks of indexes goes through `Gatherers.mapConcurrent`: up to `--threads` (default: the processors) run at once,
 *   each in a virtual thread, and the results come out in order, so they reach `--out` one code a line in the order of the
 *   indexes, 8 bytes each (7 characters and a newline), so that the code at index `i` is at byte `8 * i` and a reader can seek to it without
 *   loading the file, as `perf/k6/mixed.js` does. It starts no more chunks than it can hold, which bounds the memory however slow the file is. Without `--out`
 *   nothing is kept: it measures the generator.
 * - `--verify` sorts every value and counts repeated ones, to confirm the construction. It holds 8 bytes a code in memory, so it
 *   takes up to 250M.
 * - It says its settings, the progress at every tenth of the work, the rate, and the first codes. Exit status: 0 when done, 1
 *   when `--verify` finds a repeat or the file cannot be written, 2 for arguments it does not understand.
 */
object Dataset : Tool("Dataset", "usage: tools/run Dataset COUNT [--seed N] [--threads N] [--out FILE] [--verify]   (COUNT as 10M, up to 3521614606208)") {

    private const val CHUNK = 65_536

    private const val MAX_VERIFIED = 250_000_000L

    private val COUNT = Regex("(\\d+)([KMB]?)", RegexOption.IGNORE_CASE)

    private class Request(val count: Long, val seed: Long, val threads: Int, val out: String?, val verify: Boolean)

    override fun run(arguments: List<String>, context: Context) {
        val request = parse(arguments)
        val permutation = CodePermutation(request.seed)
        val values = if (request.verify) LongArray(request.count.toInt()) else null
        val chunks = ((request.count + CHUNK - 1) / CHUNK).toInt()
        val writing = request.out != null
        val share = request.count * 100.0 / CodePermutation.GENERATED_SPACE

        context.out.println(
            "dataset: ${number(request.count)} codes of ${Base62.LENGTH} base62 characters, ${number(CodePermutation.GENERATED_SPACE)} possible " +
                "(${String.format(Locale.ROOT, "%.5f", share)}%), seed ${request.seed}, ${request.threads} threads, " +
                "${if (writing) "writing ${request.out}" else "nothing is written"}${if (request.verify) ", verifying" else ""}",
        )

        val started = System.nanoTime()
        val bytes = request.out?.let { BufferedOutputStream(java.nio.file.Files.newOutputStream(context.path(it)), 1 shl 16) }
        bytes.use { produce(context, request, permutation, values, chunks, started, it) }
        val generated = seconds(started)

        context.out.println(
            "generated ${number(request.count)} codes in ${String.format(Locale.ROOT, "%.2f", generated)} s: " +
                "${rate(request.count, generated)} codes/s",
        )
        if (request.out != null) {
            val size = java.nio.file.Files.size(context.path(request.out))
            context.out.println("wrote ${request.out}: ${number(size)} bytes, ${number(size / (Base62.LENGTH + 1))} lines")
        }
        context.out.println("first codes: " + (0L until minOf(5, request.count)).joinToString(" ") { Base62.encode(permutation.valueAt(it)) })
        if (values != null) verify(context, values)
    }

    private fun produce(context: Context, request: Request, permutation: CodePermutation, values: LongArray?, chunks: Int, started: Long, output: BufferedOutputStream?) {
        val step = maxOf(1, chunks / 10)
        var done = 0
        IntStream.range(0, chunks).boxed()
            .gather(
                Gatherers.mapConcurrent(request.threads) { chunk: Int ->
                    val first = chunk.toLong() * CHUNK
                    generate(permutation, first, minOf(first + CHUNK, request.count), output != null, values)
                },
            )
            .forEachOrdered { text ->
                output?.write(text)
                done++
                if (done % step == 0 || done == chunks) progress(context, done, chunks, request.count, started)
            }
    }

    private fun generate(permutation: CodePermutation, first: Long, last: Long, writing: Boolean, values: LongArray?): ByteArray {
        val size = (last - first).toInt()
        val text = if (writing) ByteArray(size * (Base62.LENGTH + 1)) else ByteArray(0)
        for (offset in 0 until size) {
            val value = permutation.valueAt(first + offset)
            if (values != null) values[(first + offset).toInt()] = value
            if (writing) {
                val position = offset * (Base62.LENGTH + 1)
                Base62.encode(value, text, position)
                text[position + Base62.LENGTH] = '\n'.code.toByte()
            }
        }
        return text
    }

    private fun verify(context: Context, values: LongArray) {
        val started = System.nanoTime()
        Arrays.parallelSort(values)
        var repeated = 0L
        for (index in 1 until values.size) if (values[index] == values[index - 1]) repeated++
        val smallest = values.first()
        val largest = values.last()
        context.out.println(
            "verify: sorted ${number(values.size.toLong())} values in ${String.format(Locale.ROOT, "%.2f", seconds(started))} s, " +
                "$repeated duplicates, range $smallest..$largest",
        )
        if (repeated > 0) throw Failure("$repeated codes appear more than once")
    }

    private fun progress(context: Context, done: Int, chunks: Int, count: Long, started: Long) {
        val finished = minOf(count, done.toLong() * CHUNK)
        context.out.println(
            String.format(Locale.ROOT, "  %3d%%  %,13d codes  %6.2f s  %s codes/s", done * 100 / chunks, finished, seconds(started), rate(finished, seconds(started))),
        )
    }

    private fun parse(arguments: List<String>): Request {
        var count: Long? = null
        var seed = 1L
        var threads = Runtime.getRuntime().availableProcessors()
        var out: String? = null
        var verify = false
        val remaining = arguments.iterator()
        fun valueOf(option: String) = if (remaining.hasNext()) remaining.next() else throw Usage("$option needs a value")
        fun whole(option: String, text: String) = text.toLongOrNull() ?: throw Usage("$option needs a whole number, was '$text'")
        while (remaining.hasNext()) {
            when (val argument = remaining.next()) {
                "--seed" -> seed = whole(argument, valueOf(argument))
                "--threads" -> threads = whole(argument, valueOf(argument)).also { if (it !in 1..1024) throw Usage("--threads must be 1 to 1024") }.toInt()
                "--out" -> out = valueOf(argument)
                "--verify" -> verify = true
                else -> {
                    if (argument.startsWith("--")) throw Usage("unknown option $argument")
                    if (count != null) throw Usage("one COUNT only, and '$argument' is a second")
                    count = parseCount(argument)
                }
            }
        }
        if (count == null) throw Usage("COUNT is missing")
        if (verify && count > MAX_VERIFIED) throw Usage("--verify holds every value in memory: at most ${number(MAX_VERIFIED)} codes, was ${number(count)}")
        return Request(count, seed, threads, out, verify)
    }

    private fun parseCount(text: String): Long {
        val match = COUNT.matchEntire(text) ?: throw Usage("COUNT is a number with an optional K, M or B, was '$text'")
        val unit = when (match.groupValues[2].uppercase()) {
            "K" -> 1_000L
            "M" -> 1_000_000L
            "B" -> 1_000_000_000L
            else -> 1L
        }
        val digits = match.groupValues[1].toLongOrNull() ?: throw Usage("COUNT is too large: '$text'")
        if (digits == 0L) throw Usage("COUNT must be at least 1")
        if (digits > CodePermutation.GENERATED_SPACE / unit) throw Usage("COUNT is more than the ${number(CodePermutation.GENERATED_SPACE)} codes there are")
        return digits * unit
    }

    private fun seconds(started: Long): Double = (System.nanoTime() - started) / 1e9

    private fun rate(count: Long, seconds: Double): String = String.format(Locale.ROOT, "%,.0f", count / maxOf(seconds, 1e-9))

    private fun number(value: Long): String = String.format(Locale.ROOT, "%,d", value)
}
