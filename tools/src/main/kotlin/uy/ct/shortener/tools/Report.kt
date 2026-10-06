package uy.ct.shortener.tools

import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale

/**
 * Reads what the load tests and the profiling runs leave behind: a Markdown table comparing variants, a summary of the garbage
 * collections in an application log, and the class histogram of a heap dump.
 *
 *     tools/run Report summary RESULTS_DIR       the output of perf/bench.sh for several variants, one subdirectory each, as a table
 *     tools/run Report gc APP_LOG                how often a native image collects, how long it pauses and whether the live heap
 *                                                grows (needs the log of a run with -XX:+PrintGC)
 *     tools/run Report hprof DUMP [TOP]          the classes of an HPROF heap dump by shallow size, with byte[] and char[] by size
 *                                                class and the count of a few classes that exist once per request
 *     tools/run Report profile OUT_DIR           one line for a run of perf/profile.sh: requests a second, failures, OutOfMemoryErrors
 *     tools/run Report server PROMETHEUS END DUR what Prometheus saw of the application over the DUR seconds that ended at END
 *                                                (epoch seconds), as the JSON object perf/bench.sh records under "server"
 *     tools/run Report json FILE                 says whether FILE is JSON, and exits 1 if it is not
 *
 * Numbers are written with a point whatever the language of the machine.
 */
object Report : Tool(
    "Report",
    """
    usage: tools/run Report summary RESULTS_DIR
           tools/run Report gc APP_LOG
           tools/run Report hprof DUMP [TOP]
           tools/run Report profile OUT_DIR
           tools/run Report server PROMETHEUS_URL END_EPOCH_SECONDS DURATION_SECONDS
           tools/run Report json FILE
    """.trimIndent(),
) {

    private val JSON: JsonMapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

    override fun run(arguments: List<String>, context: Context) {
        if (arguments.isEmpty()) throw Usage("a command is needed")
        when (arguments[0]) {
            "summary" -> summary(arguments, context)
            "gc" -> GcSummary.print(arguments, context)
            "hprof" -> HeapHistogram.print(arguments, context)
            "profile" -> profile(arguments, context)
            "server" -> server(arguments, context)
            "json" -> json(arguments, context)
            else -> throw Usage("unknown command ${arguments[0]}")
        }
    }

    /** [format] with numbers written with a point. */
    fun f(format: String, vararg values: Any?): String = String.format(Locale.ROOT, format, *values)

    /** A number with [decimals] places: the exact value of the double, with a tie going to the even digit (`%.1f` rounds a tie up). */
    fun d(value: Double, decimals: Int): String = BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()

    // ---- summary ------------------------------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun array(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

    private fun number(value: Any?): Double = (value as? Number)?.toDouble() ?: 0.0

    /** The JSON object in [file], or a failure that names the file. */
    @Suppress("UNCHECKED_CAST")
    private fun parse(file: Path, context: Context): Map<String, Any?> {
        val text = context.read(file)
        val value = try {
            JSON.readValue(text, Any::class.java)
        } catch (notJson: JacksonException) {
            throw Failure("$file is not valid JSON: ${notJson.originalMessage}")
        }
        return value as? Map<String, Any?> ?: throw Failure("$file is not valid JSON: expected a JSON object")
    }

    private fun ms(seconds: Any?): String = if (seconds is Number) d(seconds.toDouble() * 1000, 1) else "n/a"

    private fun peakMemory(file: Path): Double {
        val pattern = Regex("(\\d+(?:\\.\\d+)?)(MiB|GiB)\\s*/")
        var peak = 0.0
        for (line in Files.readAllLines(file)) {
            val match = pattern.find(line) ?: continue
            peak = maxOf(peak, match.groupValues[1].toDouble() * if (match.groupValues[2] == "GiB") 1024 else 1)
        }
        return peak
    }

    private fun k6(file: Path, metric: String, field: String, context: Context): Double =
        number(obj(obj(parse(file, context)["metrics"])[metric])[field])

    private fun summary(arguments: List<String>, context: Context) {
        if (arguments.size != 2) throw Usage("summary takes a results directory")
        val directory = context.path(arguments[1])
        val variants = Files.list(directory).use { children -> children.filter { Files.exists(it.resolve("summary.json")) }.sorted().toList() }
        if (variants.isEmpty()) throw Failure("${arguments[1]} has no subdirectory with a summary.json")
        val summaries = variants.associateWith { parse(it.resolve("summary.json"), context) }
        val names = variants.map { it.fileName.toString() }
        val rows = mutableListOf<List<String>>()
        val started = Regex("Started \\S+ in ")

        context.out.println("| | ${names.joinToString(" | ")} |")
        context.out.println("|---|" + "---|".repeat(variants.size))
        rows += row("Time until ready (ms, from `docker run`)", summaries.values.map { number(it["ready_ms"]).toLong().toString() })
        rows += row("Spring startup", summaries.values.map { started.replace(it["started"].toString(), "") })
        rows += row("Memory when idle", summaries.values.map { it["idle_mem"].toString() })
        rows += row("Peak memory under load (MiB)", variants.map { d(peakMemory(it.resolve("docker-stats.txt")), 0) })

        for (firstRun in array(summaries.getValue(variants.first())["runs"])) {
            val run = obj(firstRun)
            val name = run["name"].toString()
            rows += row("**${trimmed(run["rate"])} req/s for ${run["duration"]}**", List(variants.size) { "" })
            val achieved = mutableListOf<String>()
            val failed = mutableListOf<String>()
            val redirect = mutableListOf<String>()
            val create = mutableListOf<String>()
            val seen = mutableListOf<String>()
            val pool = mutableListOf<String>()
            for (variant in variants) {
                val own = array(summaries.getValue(variant)["runs"]).map { obj(it) }.firstOrNull { name == it["name"].toString() }
                    ?: throw Failure("$variant has no run $name")
                val server = obj(own["server"])
                val file = variant.resolve("$name.k6.json")
                achieved += d(k6(file, "http_reqs", "rate", context), 0)
                failed += d(k6(file, "http_req_failed", "value", context) * 100, 2) + "%"
                redirect += "${ms(server["redirect_p50"])} / ${ms(server["redirect_p95"])} / ${ms(server["redirect_p99"])}"
                create += "${ms(server["create_p50"])} / ${ms(server["create_p99"])}"
                seen += d(k6(file, "redirect_latency", "p(95)", context), 1)
                pool += "${ms(server["hikari_acquire_max"])} / ${d(number(server["hikari_timeouts"]), 0)}"
            }
            rows += row("achieved req/s", achieved)
            rows += row("failed requests", failed)
            rows += row("redirect p50 / p95 / p99 (ms, server)", redirect)
            rows += row("create p50 / p99 (ms, server)", create)
            rows += row("redirect p95 (ms, as k6 saw it)", seen)
            rows += row("pool acquire max (ms) / timeouts", pool)
        }
        for (row in rows) context.out.println("| ${row.first()} | ${row.drop(1).joinToString(" | ")} |")
    }

    private fun row(label: String, cells: List<String>): List<String> = listOf(label) + cells

    /** A number as a person writes it: 1500 and not 1500.0. */
    private fun trimmed(value: Any?): String =
        if (value is Number && value.toDouble() == Math.rint(value.toDouble())) value.toDouble().toLong().toString() else value.toString()

    // ---- profile, server, json ----------------------------------------------------------------------------------------

    private fun profile(arguments: List<String>, context: Context) {
        if (arguments.size != 2) throw Usage("profile takes the directory of a run")
        val directory = context.path(arguments[1]).toAbsolutePath().normalize()
        val metrics = obj(parse(directory.resolve("k6.json"), context)["metrics"])
        val outOfMemory = Regex("OutOfMemoryError").findAll(context.read(directory.resolve("app.log"))).count()
        context.out.println(
            "${directory.fileName}: achieved ${d(number(obj(metrics["http_reqs"])["rate"]), 0)} rps, " +
                "failed ${d(number(obj(metrics["http_req_failed"])["value"]) * 100, 2)}%, OOM lines $outOfMemory",
        )
    }

    private fun json(arguments: List<String>, context: Context) {
        if (arguments.size != 2) throw Usage("json takes a file")
        parse(context.path(arguments[1]), context)
    }

    private fun server(arguments: List<String>, context: Context) {
        if (arguments.size != 4) throw Usage("server takes the address of Prometheus, an end and a duration")
        val base = arguments[1].replace(Regex("/+$"), "")
        val at = (arguments[2].toLongOrNull() ?: throw Usage("the end must be a whole number of epoch seconds, found ${arguments[2]}")) + 7
        val window = "${arguments[3]}s"
        val redirect = "uri=\"/{shortCode}\""
        val create = "uri=\"/api/short-links\",method=\"POST\""
        val queries = linkedMapOf(
            "redirect_p50" to quantile(0.5, redirect, window),
            "redirect_p95" to quantile(0.95, redirect, window),
            "redirect_p99" to quantile(0.99, redirect, window),
            "create_p50" to quantile(0.5, create, window),
            "create_p99" to quantile(0.99, create, window),
            "hikari_acquire_max" to "max_over_time(hikaricp_connections_acquire_seconds_max[$window])",
            "hikari_timeouts" to "increase(hikaricp_connections_timeout_total[$window])",
            "gc_pause_seconds" to "sum(increase(jvm_gc_pause_seconds_sum[$window]))",
            "process_cpu_avg" to "avg_over_time(process_cpu_usage[$window])",
        )
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val answer = LinkedHashMap<String, Double?>()
        for ((name, query) in queries) answer[name] = instant(http, base, query, at)
        context.out.println(JSON.writeValueAsString(answer))
    }

    private fun quantile(q: Double, matcher: String, window: String) =
        "histogram_quantile($q, sum by (le) (increase(http_server_requests_seconds_bucket{$matcher}[$window])))"

    /** The value of an instant query, or null when Prometheus has no series for it or the value is not a number. */
    private fun instant(http: HttpClient, base: String, query: String, at: Long): Double? {
        val form = "query=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&time=" + at
        val request = HttpRequest.newBuilder(URI.create("$base/api/v1/query")).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build()
        try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) throw Failure("Prometheus answered ${response.statusCode()} to $query")
            val body = try {
                JSON.readValue(response.body(), Any::class.java)
            } catch (notJson: JacksonException) {
                throw Failure("Prometheus did not answer JSON to $query: ${notJson.originalMessage}")
            }
            val results = array(obj(obj(body)["data"])["result"])
            if (results.isEmpty()) return null
            val value = array(obj(results.first())["value"])
            val parsed = if (value.size == 2) value[1].toString().toDoubleOrNull() ?: Double.NaN else Double.NaN
            return parsed.takeIf { it.isFinite() }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw Failure("interrupted while asking Prometheus")
        } catch (unreachable: IOException) {
            throw Failure("cannot reach Prometheus at $base: ${unreachable.message}")
        }
    }
}
