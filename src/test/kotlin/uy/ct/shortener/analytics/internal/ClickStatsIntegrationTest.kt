package uy.ct.shortener.analytics.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import uy.ct.shortener.RestTestClientSupport
import uy.ct.shortener.RestTestClientSupport.request
import uy.ct.shortener.RestTestClientSupport.text
import uy.ct.shortener.TestIdp
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.WithTestIdp
import java.util.UUID

/**
 * Who may read the clicks of a link, and what they read, end to end: the owner sees them, another client does not even learn the
 * link exists, an administrator sees any, and the read scope is needed. The clicks are made by following the link, and written by
 * the application's own flusher, called by hand so the test does not wait for the timer.
 */
@WithTestIdp
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
class ClickStatsIntegrationTest {

    @Autowired
    lateinit var rest: RestTestClient

    @Autowired
    lateinit var flusher: ClickFlusher

    @LocalServerPort
    var port: Int = 0

    private val noRedirects by lazy { RestTestClientSupport.withoutRedirects(port) }

    private val manage = "shortlinks:create shortlinks:read shortlinks:delete"

    private fun client() = "client-${UUID.randomUUID().toString().take(8)}"

    private fun call(method: HttpMethod, path: String, client: String?, scopes: String = manage, body: String? = null): RestTestClient.ResponseSpec {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            if (client != null) setBearerAuth(TestIdp.token(client = client, scope = scopes))
        }
        return rest.request(method, path, headers, body)
    }

    private fun createLink(owner: String): String {
        val created = call(HttpMethod.POST, "/api/short-links", owner, body = """{"targetUrl":"https://example.com/${UUID.randomUUID()}"}""")
        return Regex(""""shortCode":"([^"]+)"""").find(created.text())!!.groupValues[1]
    }

    private fun follow(code: String, userAgent: String, times: Int = 1, referer: String = "", languages: String = "") = repeat(times) {
        noRedirects.get().uri("/$code").header("User-Agent", userAgent).header("Referer", referer).header("Accept-Language", languages)
            .exchange().expectStatus().isFound()
    }

    private fun stats(code: String, client: String?, query: String = "", scopes: String = manage) =
        call(HttpMethod.GET, "/api/short-links/$code/stats$query", client, scopes)

    @Test
    fun `the owner reads the clicks its link had, told apart by the kind of client`() {
        val alice = client()
        val code = createLink(alice)
        follow(code, "Mozilla/5.0 (X11; Linux x86_64) Chrome/130.0", times = 3)
        follow(code, "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0) Mobile/15E148")
        follow(code, "Slackbot-LinkExpanding 1.0")
        flusher.flush()

        val day = stats(code, alice).expectStatus().isOk().text()
        val hour = stats(code, alice, "?granularity=hour").expectStatus().isOk().text()

        assertThat(day).contains(""""shortCode":"$code"""", """"granularity":"day"""", """"total":{"visitors":4,"desktop":3,"mobile":1,"bot":1}""")
        assertThat(hour).contains(""""granularity":"hour"""", """"total":{"visitors":4,"desktop":3,"mobile":1,"bot":1}""")
    }

    @Test
    fun `the owner reads where its visitors came from and which language they asked for`() {
        val alice = client()
        val code = createLink(alice)
        follow(code, "Mozilla/5.0 (X11; Linux x86_64) Chrome/130.0", times = 2, referer = "https://t.co/xyz", languages = "pt-BR,pt;q=0.9")
        follow(code, "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0) Mobile/15E148", languages = "en-US")
        follow(code, "Googlebot/2.1", referer = "https://www.google.com/", languages = "en")
        flusher.flush()

        val body = stats(code, alice, "?granularity=hour").expectStatus().isOk().text()

        assertThat(body).contains(
            """"referrers":{"direct":1,"hosts":[{"host":"t.co","visitors":2}],"other":0}""",
            """"languages":{"codes":[{"code":"pt","visitors":2},{"code":"en","visitors":1}],"unknown":0}""",
        )
    }

    @Test
    fun `a link nobody followed has zeros, and a series with a point for every bucket`() {
        val alice = client()
        val code = createLink(alice)

        val body = stats(code, alice, "?granularity=hour&from=2026-01-01T00:00:00Z&to=2026-01-01T03:00:00Z").expectStatus().isOk().text()

        assertThat(body).contains(
            """"total":{"visitors":0,"desktop":0,"mobile":0,"bot":0}""",
            """"breakdown":{"from":"2026-01-01T00:00:00Z","to":"2026-01-02T00:00:00Z","referrers":{"direct":0,"hosts":[],"other":0},""" +
                """"languages":{"codes":[],"unknown":0}}""",
        )
        assertThat(Regex(""""start":""").findAll(body).count()).isEqualTo(3)
    }

    @Test
    fun `another client's link looks like it does not exist`() {
        val alice = client()
        val bob = client()
        val code = createLink(alice)

        stats(code, bob).expectStatus().isNotFound().expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
        stats("never-created", alice).expectStatus().isNotFound()
    }

    @Test
    fun `an administrator reads any link's clicks`() {
        val alice = client()
        val admin = "admin-client"
        val code = createLink(alice)

        stats(code, admin, scopes = "shortlinks:admin").expectStatus().isOk()
    }

    @Test
    fun `needs the scope to read links, and a token`() {
        val alice = client()
        val code = createLink(alice)

        stats(code, alice, scopes = "shortlinks:create").expectStatus().isForbidden()
        stats(code, null).expectStatus().isUnauthorized()
    }

    @Test
    fun `refuses a window it cannot answer as a problem detail`() {
        val alice = client()
        val code = createLink(alice)

        stats(code, alice, "?granularity=week").expectStatus().isBadRequest().expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(stats(code, alice, "?granularity=hour&from=2020-01-01T00:00:00Z&to=2026-01-01T00:00:00Z").expectStatus().isBadRequest().text())
            .contains("at most 90 days")
        stats(code, alice, "?from=not-a-time").expectStatus().isBadRequest()
    }
}
