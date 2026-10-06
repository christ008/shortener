package uy.ct.shortener.tools

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

/**
 * Exercises every endpoint of a running application with DPoP-bound tokens, as the demo, other and admin clients, and checks
 * the status each answers.
 *
 *     ./gradlew smoke [-PbaseUrl=http://localhost:8080] [-Pmgmt=http://localhost:8081]
 *     tools/run Smoke [BASE_URL]            with MGMT in the environment for the management port
 *
 * The clients are the dev ones that `deploy/keycloak/dev-setup` makes, with their keys in `deploy/keycloak/dev-keys`
 * (`KEYS_DIR` says another place). The token endpoint is the one the DPoP client uses (`TOKEN_URL` says another). A server
 * certificate of your own is trusted as for any Java program, for example with
 * `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=ts.p12`. Redirects are not followed.
 *
 * The calls are made by `DpopClient`, compiled with the tools. Exit status: 0 when every check passed, 1 when one did not,
 * 2 for arguments it does not understand.
 */
object Smoke : Tool("Smoke", "usage: tools/run Smoke [BASE_URL]   (MGMT in the environment: the management port, default http://localhost:8081)") {

    private val SHORT_CODE = Regex("\"shortCode\":\"([^\"]*)\"")

    private val HTTP: HttpClient = HttpClient.newHttpClient()

    /** What a request answered, or the reason it did not: a refused connection is a failed check, not the end of the run. */
    private class Reply(val status: Int, val body: String) {
        companion object {
            fun unreachable(failure: Exception) = Reply(-1, failure.toString())
        }
    }

    override fun run(arguments: List<String>, context: Context) {
        if (arguments.size > 1) throw Usage("at most one argument, the base URL")
        val base = arguments.firstOrNull() ?: "http://localhost:8080"
        val management = context.setting("MGMT") ?: "http://localhost:8081"
        val keys = context.path(context.setting("KEYS_DIR") ?: "deploy/keycloak/dev-keys")
        val run = Run(keys, context)
        run.checks(base, management)
        if (run.failed == 0) {
            context.out.println("all checks passed")
        } else {
            context.out.println("${run.failed} checks failed")
            throw Exit(1)
        }
    }

    private class Run(private val keys: Path, private val context: Context) {
        var failed = 0
            private set

        fun checks(base: String, management: String) {
            val api = "$base/api/short-links"

            val generated = call("demo-client", "POST", api, """{"targetUrl":"https://example.com/smoke"}""")
            check("create with a generated code", 201, generated)
            val code = SHORT_CODE.find(generated.body)?.groupValues?.get(1) ?: ""
            val custom = "smoke-" + ThreadLocalRandom.current().nextInt(32768)
            val customBody = """{"targetUrl":"https://example.com/custom","customCode":"$custom"}"""
            check("create with a custom code", 201, call("demo-client", "POST", api, customBody))
            check("custom code taken", 409, call("demo-client", "POST", api, customBody))
            check("invalid url", 400, call("demo-client", "POST", api, """{"targetUrl":"not a url"}"""))
            check("missing url", 400, call("demo-client", "POST", api, "{}"))
            check("get own link", 200, call("demo-client", "GET", "$api/$code", null))
            check("another client's link looks not found", 404, call("other-client", "GET", "$api/$code", null))
            check("admin reads any link", 200, call("admin-client", "GET", "$api/$code", null))
            check("list own links", 200, call("demo-client", "GET", "$api?size=1&sort=shortCode,asc", null))
            check("list with a sort that is not allowed", 400, call("demo-client", "GET", "$api?sort=targetUrl", null))
            check("no-scope client cannot create", 403, call("no-scope-client", "POST", api, """{"targetUrl":"https://example.com/x"}"""))
            check("redirect is public", 302, plain("$base/$code", emptyMap()))
            check("unknown code", 404, plain("$base/zzzzzzz", emptyMap()))
            check("another client cannot disable", 404, call("other-client", "DELETE", "$api/$code", null))
            check("owner disables", 204, call("demo-client", "DELETE", "$api/$code", null))
            check("disabled link answers gone", 410, plain("$base/$code", emptyMap()))
            check("bearer scheme is refused", 401, plain(api, mapOf("Authorization" to "Bearer " + token("demo-client"))))
            check("no credentials", 401, plain(api, emptyMap()))
            check("protected resource metadata", 200, plain("$base/.well-known/oauth-protected-resource", emptyMap()))
            check("readiness", 200, plain("$management/actuator/health/readiness", emptyMap()))
            val metrics = plain("$management/actuator/prometheus", emptyMap())
            check(
                "request metrics are exported",
                metrics.status == 200 && metrics.body.contains("\nhttp_server_requests_seconds_bucket"),
                if (metrics.status == 200) "no http_server_requests_seconds_bucket" else "status ${metrics.status}",
            )
        }

        private fun call(client: String, method: String, url: String, body: String?): Reply =
            try {
                val answer = DpopClient.call(keys.resolve("$client.jwk.json"), client, method, url, body)
                Reply(answer.status(), answer.body())
            } catch (failure: Exception) {
                Reply.unreachable(failure)
            }

        private fun token(client: String): String =
            try {
                DpopClient.token(keys.resolve("$client.jwk.json"), client)
            } catch (failure: Exception) {
                "no-token: ${failure.message}"
            }

        private fun plain(url: String, headers: Map<String, String>): Reply =
            try {
                val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET()
                headers.forEach { (name, value) -> request.header(name, value) }
                val response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString())
                Reply(response.statusCode(), response.body())
            } catch (failure: IOException) {
                Reply.unreachable(failure)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                Reply.unreachable(interrupted)
            }

        private fun check(name: String, expected: Int, reply: Reply) =
            check(name, expected == reply.status, "expected $expected, got ${if (reply.status < 0) "no answer: ${reply.body}" else reply.status}")

        private fun check(name: String, passed: Boolean, otherwise: String) {
            if (passed) {
                context.out.println("ok    $name")
            } else {
                context.out.println("FAIL  $name ($otherwise)")
                failed++
            }
        }
    }
}
