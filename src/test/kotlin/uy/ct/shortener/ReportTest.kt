package uy.ct.shortener

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
 * `tools/Report.java` replaced three Python scripts, and the numbers they print have been quoted in the documentation. So the
 * expected output of the tests below is what the Python scripts printed for the same files: the results committed under
 * `perf/results`, and a small HPROF dump built to have every kind of record the reader skips or counts. The one line that
 * is new on purpose is the list of causes of the collections, which Python printed as a dictionary.
 */
class ReportTest {

    @TempDir
    lateinit var directory: Path

    private class Output(val exitCode: Int, val stdout: String, val stderr: String)

    private fun run(vararg arguments: String): Output {
        val process = ProcessBuilder(System.getProperty("java.home") + "/bin/java", "tools/Report.java", *arguments)
            .directory(Path.of("").toAbsolutePath().toFile()).start()
        val stdout = process.inputStream.readAllBytes().decodeToString()
        val stderr = process.errorStream.readAllBytes().decodeToString()
        check(process.waitFor(120, TimeUnit.SECONDS)) { "Report.java did not finish" }
        return Output(process.exitValue(), stdout, stderr)
    }

    private fun expected(name: String) = Files.readString(Path.of("src/test/resources/report/$name"))

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

        assertThat(result.exitCode).describedAs(result.stderr).isEqualTo(0)
        assertThat(result.stdout.lines().take(4).joinToString("\n", postfix = "\n")).isEqualTo(expected("gc-maxconn500.expected.txt"))
        assertThat(result.stdout.lines()[4]).isEqualTo("causes: Full GC (Collect on allocation) 58, Incremental GC (Collect on allocation) 1749")
    }

    @Test
    fun `a log with no collections is said in words and not a stack trace`() {
        val result = run("gc", "perf/results/2026-10-05-os/o2-1/app.log")

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).contains("fewer than two GC lines", "-XX:+PrintGC")
    }

    @Test
    fun `the heap histogram is what the Python one printed for a dump with every kind of record`() {
        assertThat(run("hprof", "src/test/resources/report/sample.hprof", "8").stdout).isEqualTo(expected("sample.hprof.expected.txt"))
    }

    @Test
    fun `a file that is not a heap dump is refused`() {
        val notADump = directory.resolve("not.hprof").also { Files.write(it, ByteArray(64) { 7 }) }

        assertThat(run("hprof", notADump.toString()).exitCode).isEqualTo(1)
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

        assertThat(run("json", valid.toString()).exitCode).isEqualTo(0)
        val bad = run("json", invalid.toString())
        assertThat(bad.exitCode).isEqualTo(1)
        assertThat(bad.stderr).contains("is not valid JSON").doesNotContain("\tat ")
    }

    @Test
    fun `a directory without results, and a command it does not know, are said in words`() {
        assertThat(run("summary", directory.toString()).stderr).contains("has no subdirectory with a summary.json")
        val unknown = run("nonsense")
        assertThat(unknown.exitCode).isEqualTo(2)
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

        assertThat(result.exitCode).describedAs(result.stderr).isEqualTo(0)
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

        assertThat(result.exitCode).isNotEqualTo(0)
        assertThat(result.stderr).doesNotContain("\tat ")
    }
}
