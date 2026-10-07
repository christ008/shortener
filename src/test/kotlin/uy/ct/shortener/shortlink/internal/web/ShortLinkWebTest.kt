package uy.ct.shortener.shortlink.internal.web

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import org.springframework.format.support.DefaultFormattingConversionService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortCodeGenerator
import uy.ct.shortener.shortlink.internal.AnyTarget
import uy.ct.shortener.shortlink.internal.CaffeineRedirectCache
import uy.ct.shortener.shortlink.internal.DefaultShortLinkService
import uy.ct.shortener.shortlink.internal.InMemoryShortLinkRepository
import uy.ct.shortener.shortlink.internal.NoAuditTrail
import uy.ct.shortener.shortlink.internal.RedirectCacheProperties
import uy.ct.shortener.shortlink.internal.authorization.ManageableLinks
import uy.ct.shortener.shortlink.internal.authorization.ShortLinkScopes

/**
 * The shape of the HTTP API: the controller in front of the real service and an in-memory repository, with no Spring context
 * and no database, so it runs on every change. What it leaves out is who may call what, which method security decides and
 * `AuthorizationIntegrationTest` covers against a running server.
 *
 * The addresses are the point. A link is a resource at `/api/short-links/{code}`, which is what a created link's `Location`
 * names, and its `shortUrl` is the public `/{code}` on the host the request was made to. A link is disabled with a `PATCH`, and
 * cannot be removed.
 */
class ShortLinkWebTest {

    private val codes = ArrayDeque(listOf("aaaaaaa", "bbbbbbb", "ccccccc"))

    private val generator = ShortCodeGenerator { ShortCode(codes.removeFirst()) }

    private val repository = InMemoryShortLinkRepository()

    private val service = DefaultShortLinkService(
        repository, generator, ManageableLinks(repository),
        CaffeineRedirectCache(RedirectCacheProperties(), SimpleMeterRegistry(), executor = Runnable::run),
        AnyTarget, ObservationRegistry.NOOP, NoAuditTrail,
    )

    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(ShortLinkController(service, ShortLinkScopes("shortlinks:create", "shortlinks:claim", "shortlinks:read", "shortlinks:delete", "shortlinks:admin")))
        .setConversionService(DefaultFormattingConversionService().apply { addConverter(StringToShortCodeConverter()) })
        .setCustomArgumentResolvers(PageableHandlerMethodArgumentResolver())
        .build()

    private val alice = TestingAuthenticationToken("alice", null, "shortlinks:create", "shortlinks:read", "shortlinks:delete")

    private fun MockHttpServletRequestBuilder.asAlice() = principal(alice)

    private fun MockHttpServletRequestBuilder.onHost(scheme: String, host: String, port: Int) =
        with { it.also { request -> request.scheme = scheme; request.serverName = host; request.serverPort = port } }

    private fun MockHttpServletRequestBuilder.json(body: String, type: String = MediaType.APPLICATION_JSON_VALUE) =
        contentType(type).content(body)

    private fun create(target: String = "https://example.com/target", host: String = "localhost"): MvcResult =
        mvc.perform(
            post("/api/short-links").asAlice().onHost("http", host, 80).json("""{"targetUrl":"$target"}"""),
        ).andReturn()

    private fun MvcResult.status() = response.status

    private fun MvcResult.body() = response.contentAsString

    @Test
    fun `a created link answers with the link as its Location and the URL to share in its body`() {
        val created = create(host = "short.test")

        assertThat(created.status()).isEqualTo(201)
        assertThat(created.response.getHeader(HttpHeaders.LOCATION)).isEqualTo("http://short.test/api/short-links/aaaaaaa")
        assertThat(created.body()).contains(""""shortCode":"aaaaaaa"""", """"shortUrl":"http://short.test/aaaaaaa"""")
    }

    @Test
    fun `the short URL follows the scheme, host and port of the request`() {
        val created = mvc.perform(
            post("/api/short-links").asAlice().onHost("https", "go.example.org", 8443).json("""{"targetUrl":"https://example.com/x"}"""),
        ).andReturn()

        assertThat(created.body()).contains(""""shortUrl":"https://go.example.org:8443/aaaaaaa"""")
        assertThat(created.response.getHeader(HttpHeaders.LOCATION)).isEqualTo("https://go.example.org:8443/api/short-links/aaaaaaa")
    }

    @Test
    fun `the Location of a created link reads what was created`() {
        val created = create()

        val read = mvc.perform(get("/api/short-links/aaaaaaa").asAlice()).andReturn()

        assertThat(read.status()).isEqualTo(200)
        assertThat(read.body()).isEqualTo(created.body())
    }

    @Test
    fun `the URL to share redirects, and so does a HEAD of it`() {
        create(target = "https://example.com/where")

        listOf(get("/aaaaaaa"), head("/aaaaaaa")).forEach {
            val redirect = mvc.perform(it).andReturn()

            assertThat(redirect.status()).isEqualTo(302)
            assertThat(redirect.response.getHeader(HttpHeaders.LOCATION)).isEqualTo("https://example.com/where")
        }
    }

    @Test
    fun `every link of a listing carries its own short URL`() {
        create()
        create()

        val page = mvc.perform(get("/api/short-links").asAlice().onHost("http", "short.test", 80)).andReturn()

        assertThat(page.body()).contains(""""shortUrl":"http://short.test/aaaaaaa"""", """"shortUrl":"http://short.test/bbbbbbb"""")
    }

    @Test
    fun `a link is disabled with a PATCH that answers the link, disabled`() {
        create()

        val disabled = mvc.perform(patch("/api/short-links/aaaaaaa").asAlice().json("""{"disabled":true}""")).andReturn()

        assertThat(disabled.status()).isEqualTo(200)
        assertThat(disabled.body()).contains(""""shortCode":"aaaaaaa"""", """"shortUrl":"http://localhost/aaaaaaa"""", """"disabledAt":"2""")
        assertThat(mvc.perform(get("/aaaaaaa")).andReturn().status()).isEqualTo(410)
    }

    @Test
    fun `a PATCH may be sent as a JSON merge patch`() {
        create()

        val disabled = mvc.perform(patch("/api/short-links/aaaaaaa").asAlice().json("""{"disabled":true}""", "application/merge-patch+json")).andReturn()

        assertThat(disabled.status()).isEqualTo(200)
        assertThat(disabled.body()).contains(""""disabledAt":"2""")
    }

    @Test
    fun `disabling twice answers the same link, with the time of the first`() {
        create()

        val first = mvc.perform(patch("/api/short-links/aaaaaaa").asAlice().json("""{"disabled":true}""")).andReturn()
        val second = mvc.perform(patch("/api/short-links/aaaaaaa").asAlice().json("""{"disabled":true}""")).andReturn()

        assertThat(second.status()).isEqualTo(200)
        assertThat(second.body()).isEqualTo(first.body())
    }

    @Test
    fun `a PATCH takes only disabled true, so it cannot enable and it cannot be empty`() {
        create()

        listOf("""{"disabled":false}""", """{"disabled":null}""", "{}", """{"disabled":"yes"}""", "not json").forEach { body ->
            val refused = mvc.perform(patch("/api/short-links/aaaaaaa").asAlice().json(body)).andReturn()

            assertThat(refused.status()).describedAs(body).isEqualTo(400)
        }
        assertThat(mvc.perform(get("/aaaaaaa")).andReturn().status()).describedAs("nothing was disabled").isEqualTo(302)
    }

    @Test
    fun `a link that does not exist is not found by a PATCH`() {
        val missing = mvc.perform(patch("/api/short-links/zzzzzzz").asAlice().json("""{"disabled":true}""")).andReturn()

        assertThat(missing.status()).isEqualTo(404)
    }

    @Test
    fun `a link cannot be removed, DELETE is not offered and the answer says what is`() {
        create()

        val refused = mvc.perform(delete("/api/short-links/aaaaaaa").asAlice()).andReturn()

        assertThat(refused.status()).isEqualTo(405)
        assertThat(refused.response.getHeader(HttpHeaders.ALLOW)).contains("GET").contains("PATCH").doesNotContain("DELETE")
        assertThat(mvc.perform(get("/aaaaaaa")).andReturn().status()).describedAs("nothing was removed").isEqualTo(302)
    }

    @Test
    fun `a code that could not have been issued is a 400 wherever it is used`() {
        listOf(get("/ab"), get("/api/short-links/ab").asAlice(), patch("/api/short-links/ab").asAlice().json("""{"disabled":true}""")).forEach {
            assertThat(mvc.perform(it).andReturn().status()).isEqualTo(400)
        }
    }

    @Test
    fun `the codes the application keeps for itself cannot be chosen`() {
        listOf("api", "actuator", "error", "app").forEach { reserved ->
            val refused = mvc.perform(
                post("/api/short-links").principal(TestingAuthenticationToken("alice", null, "shortlinks:create", "shortlinks:claim"))
                    .json("""{"targetUrl":"https://example.com/x","customCode":"$reserved"}"""),
            ).andReturn()

            assertThat(refused.status()).describedAs(reserved).isEqualTo(409)
        }
    }
}
