package uy.ct.shortener.tools

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * `Report` replaced three Python scripts, and the numbers they print have been quoted in the documentation. So the
 * expected output of the tests below is what the Python scripts printed for the same files: the results committed under
 * `perf/results`, and a small HPROF dump built to have every kind of record the reader skips or counts. The one line that
 * is new on purpose is the list of causes of the collections, which Python printed as a dictionary.
 */
class ReportTest {

    @TempDir
    lateinit var directory: Path

    private fun run(vararg arguments: String) = run(Report, *arguments)

    private fun expected(name: String) = Files.readString(repositoryRoot.resolve("tools/src/test/resources/report/$name"))

    @Test
    fun `the comparison of variants is what the Python report printed, for two sets of results`() {
        assertThat(run("summary", "perf/results/2026-10-03-run2").stdout).isEqualTo(expected("summary-run2.expected.md"))
        assertThat(run("summary", "perf/results/connections-run1").stdout).isEqualTo(expected("summary-connections.expected.md"))
    }

    @Test
    fun `a tie rounds to the even digit as the published tables did`() {
        // 2026-10-05-os has a peak memory of 138.5 MiB, which Python wrote as 138 and a round-half-up would write as 139.
        assertThat(run("summary", "perf/results/2026-10-05-os").stdout).contains("| Peak memory under load (MiB) | 138 | 131 | 127 | 170 |")
    }

    @Test
    fun `the garbage collection summary is what the Python one printed, and the causes are a list`() {
        val result = run("gc", "perf/results/connections-overload/maxconn-500/app.log")

        assertThat(result.status).describedAs(result.stderr).isEqualTo(0)
        assertThat(result.stdout.lines().take(4).joinToString("\n", postfix = "\n")).isEqualTo(expected("gc-maxconn500.expected.txt"))
        assertThat(result.stdout.lines()[4]).isEqualTo("causes: Full GC (Collect on allocation) 58, Incremental GC (Collect on allocation) 1749")
    }

    @Test
    fun `a log with no collections is said in words and not a stack trace`() {
        val result = run("gc", "perf/results/2026-10-05-os/o2-1/app.log")

        assertThat(result.status).isEqualTo(1)
        assertThat(result.stderr).contains("fewer than two GC lines", "-XX:+PrintGC")
    }

    private fun repeated(variant: String, rate: Int, achieved: List<Int>, failed: List<Double>, p99: List<Double>, acquire: List<Double>, timeouts: List<Int>) {
        val dir = Files.createDirectories(directory.resolve(variant))
        Files.writeString(dir.resolve("docker-stats.txt"), "1 10% 100MiB / 512MiB\n")
        val runs = achieved.indices.joinToString(",") { i ->
            Files.writeString(
                dir.resolve("r$rate-${i + 1}.k6.json"),
                """{"metrics":{"http_reqs":{"rate":${achieved[i]}},"http_req_failed":{"value":${failed[i]}},"redirect_latency":{"p(95)":${i + 1}.0}}}""",
            )
            """{"name":"r$rate-${i + 1}","rate":$rate,"duration":"60s","create_share":0.01,"repeat":${i + 1},"start":1,"end":2,""" +
                """"server":{"redirect_p50":0.0005,"redirect_p95":0.001,"redirect_p99":${p99[i]},"create_p50":0.002,"create_p99":0.006,""" +
                """"hikari_acquire_max":${acquire[i]},"hikari_timeouts":${timeouts[i]}}}"""
        }
        Files.writeString(dir.resolve("summary.json"), """{"variant":"$variant","ready_ms":1000,"idle_mem":"100MiB","started":"Started X in 1.5 seconds","runs":[$runs]}""")
    }

    @Test
    fun `runs of one rate that were repeated are one block with the median and the range`() {
        repeated("a", 1000, listOf(990, 1000, 995), listOf(0.0, 0.001, 0.0), listOf(0.001, 0.0049, 0.002), listOf(0.5, 0.9, 0.7), listOf(0, 0, 2))
        repeated("b", 1000, listOf(1000), listOf(0.0), listOf(0.003), listOf(0.1), listOf(0))

        val table = run("summary", directory.toString()).stdout

        assertThat(table).contains("| **1000 req/s for 60s, median of 3 runs** |  |  |")
        assertThat(table).contains("| achieved req/s | 995 (990-1000) | 1000 |")
        assertThat(table).contains("| failed requests | 0.00% (0.00-0.10%) | 0.00% |")
        assertThat(table).contains("| redirect p50 / p95 / p99 (ms, server) | 0.5 / 1.0 / 2.0 | 0.5 / 1.0 / 3.0 |")
        assertThat(table).contains("| redirect p99 over the runs, lowest to highest (ms) | 1.0-4.9 | 3.0-3.0 |")
        assertThat(table).contains("| redirect p95 (ms, as k6 saw it) | 2.0 | 1.0 |")
        assertThat(table).describedAs("the worst run of the pool figures").contains("| pool acquire max (ms) / timeouts | 900.0 / 2 | 100.0 / 0 |")
    }

    @Test
    fun `a variant without the runs of another is said in words`() {
        repeated("a", 1000, listOf(990), listOf(0.0), listOf(0.001), listOf(0.5), listOf(0))
        repeated("b", 2000, listOf(1990), listOf(0.0), listOf(0.001), listOf(0.5), listOf(0))

        val result = run("summary", directory.toString())

        assertThat(result.status).isEqualTo(1)
        assertThat(result.stderr).contains("b has no run r1000-1")
    }

    @Test
    fun `the heap histogram is what the Python one printed for a dump with every kind of record`() {
        assertThat(run("hprof", "tools/src/test/resources/report/sample.hprof", "8").stdout).isEqualTo(expected("sample.hprof.expected.txt"))
    }

    @Test
    fun `a file that is not a heap dump is refused`() {
        val notADump = directory.resolve("not.hprof").also { Files.write(it, ByteArray(64) { 7 }) }

        assertThat(run("hprof", notADump.toString()).status).isEqualTo(1)
    }

    @Test
    fun `a heap dump that ends in the middle of a record is said in words and not a stack trace`() {
        val whole = Files.readAllBytes(repositoryRoot.resolve("tools/src/test/resources/report/sample.hprof"))
        val cut = directory.resolve("cut.hprof").also { Files.write(it, whole.copyOf(whole.size / 3)) }

        val result = run("hprof", cut.toString())

        assertThat(result.status).isEqualTo(1)
        assertThat(result.stderr).contains("does not look like a complete HPROF dump").doesNotContain("\tat ", "Exception")
    }

    @Test
    fun `a number that is not a number is a usage error and not a stack trace`() {
        val sample = repositoryRoot.resolve("tools/src/test/resources/report/sample.hprof").toString()

        val top = run("hprof", sample, "many")
        val end = run("server", "http://localhost:1", "soon", "60")

        assertThat(top.status).isEqualTo(2)
        assertThat(top.stderr).contains("how many classes must be a whole number", "usage:")
        assertThat(end.status).isEqualTo(2)
        assertThat(end.stderr).contains("whole number of epoch seconds").doesNotContain("\tat ")
    }

    @Test
    fun `a profile run is one line`() {
        assertThat(run("profile", "perf/results/profiles/native-steady-1500").stdout.trim()).isEqualTo("native-steady-1500: achieved 1497 rps, failed 0.00%, OOM lines 0")
        assertThat(run("profile", "perf/results/profiles/native-5000-overload").stdout.trim()).isEqualTo("native-5000-overload: achieved 552 rps, failed 34.82%, OOM lines 3")
    }

    @Test
    fun `json says yes to JSON and no, in words, to anything else`() {
        val valid = directory.resolve("valid.json").also { Files.writeString(it, """{"a":[1,2.5,null,"x"]}""") }
        val invalid = directory.resolve("invalid.json").also { Files.writeString(it, """{"a":""") }

        assertThat(run("json", valid.toString()).status).isEqualTo(0)
        val bad = run("json", invalid.toString())
        assertThat(bad.status).isEqualTo(1)
        assertThat(bad.stderr).contains("is not valid JSON").doesNotContain("\tat ")
    }

    @Test
    fun `a directory without results, and a command it does not know, are said in words`() {
        assertThat(run("summary", directory.toString()).stderr).contains("has no subdirectory with a summary.json")
        val unknown = run("nonsense")
        assertThat(unknown.status).isEqualTo(2)
        assertThat(unknown.stderr).contains("unknown command nonsense", "usage:")
    }

    private val queries = CopyOnWriteArrayList<Map<String, String>>()

    private var prometheus: HttpServer? = null

    @AfterEach
    fun stop() {
        prometheus?.stop(0)
    }

    private fun standInPrometheus(answer: (String) -> String): String {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/api/v1/query") { exchange ->
            val form = String(exchange.requestBody.readAllBytes()).split("&").associate {
                URLDecoder.decode(it.substringBefore("="), StandardCharsets.UTF_8) to URLDecoder.decode(it.substringAfter("="), StandardCharsets.UTF_8)
            }
            queries += form + ("method" to exchange.requestMethod)
            val body = answer(form.getValue("query")).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        prometheus = server
        return "http://localhost:${server.address.port}"
    }

    private fun vector(value: String) = """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1760000000,"$value"]}]}}"""

    private val none = """{"status":"success","data":{"resultType":"vector","result":[]}}"""

    @Test
    fun `server asks Prometheus for the window that ended at END and answers the object bench records`() {
        val url = standInPrometheus { query ->
            when {
                "quantile(0.5" in query && "shortCode" in query -> vector("0.00093")
                "quantile(0.99" in query && "POST" in query -> vector("NaN")
                "hikaricp_connections_timeout_total" in query -> vector("12")
                "jvm_gc_pause_seconds_sum" in query -> none
                else -> vector("0.5")
            }
        }

        val result = run("server", url, "1000", "67")

        assertThat(result.status).describedAs(result.stderr).isEqualTo(0)
        assertThat(result.stdout.trim()).isEqualTo(
            """{"redirect_p50":9.3E-4,"redirect_p95":0.5,"redirect_p99":0.5,"create_p50":0.5,"create_p99":null,"hikari_acquire_max":0.5,"hikari_timeouts":12.0,"gc_pause_seconds":null,"process_cpu_avg":0.5}""",
        )
        assertThat(queries).hasSize(9).allSatisfy {
            assertThat(it["method"]).isEqualTo("POST")
            assertThat(it["time"]).describedAs("the instant is END plus the 7 seconds the scrape needs").isEqualTo("1007")
            assertThat(it["query"]).contains("[67s]")
        }
        assertThat(queries.map { it.getValue("query") }).anyMatch { """uri="/api/short-links",method="POST"""" in it && it.startsWith("histogram_quantile(0.99") }
    }

    @Test
    fun `server says when Prometheus is not there`() {
        val result = run("server", "http://localhost:1", "1000", "60")

        assertThat(result.status).isNotEqualTo(0)
        assertThat(result.stderr).doesNotContain("\tat ")
    }
}
