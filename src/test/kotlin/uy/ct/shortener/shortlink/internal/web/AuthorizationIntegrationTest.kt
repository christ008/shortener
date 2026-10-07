package uy.ct.shortener.shortlink.internal.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import uy.ct.shortener.RestTestClientSupport
import uy.ct.shortener.RestTestClientSupport.expectStatus
import uy.ct.shortener.RestTestClientSupport.request
import uy.ct.shortener.RestTestClientSupport.text
import uy.ct.shortener.TestIdp
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.WithTestIdp
import java.util.UUID

/**
 * Who may do what, end to end: the scope of each operation, ownership between clients, the
 * administrator's reach, and what a disabled link looks like. Every test uses clients of its own,
 * so tests cannot see each other's links.
 */
@WithTestIdp
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
class AuthorizationIntegrationTest {

    @Autowired
    lateinit var rest: RestTestClient

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

    private fun get(path: String, client: String?, scopes: String = manage) = call(HttpMethod.GET, path, client, scopes)

    private fun disable(code: String, client: String?, scopes: String = manage) =
        call(HttpMethod.PATCH, "/api/short-links/$code", client, scopes, """{"disabled":true}""")

    private fun create(client: String, scopes: String = manage) =
        call(HttpMethod.POST, "/api/short-links", client, scopes, """{"targetUrl":"https://example.com/${UUID.randomUUID()}"}""")

    private fun claim(code: String, client: String?, scopes: String = "$manage shortlinks:claim", target: String = "https://example.com/claimed") =
        call(HttpMethod.PUT, "/api/short-links/$code", client, scopes, """{"targetUrl":"$target"}""")

    private fun codeOf(response: RestTestClient.ResponseSpec) = Regex(""""shortCode":"([^"]+)"""").find(response.text())!!.groupValues[1]

    private fun codesIn(response: RestTestClient.ResponseSpec) = Regex(""""shortCode":"([^"]+)"""").findAll(response.text()).map { it.groupValues[1] }.toList()

    private fun redirect(code: String) = noRedirects.get().uri("/$code").exchange()

    private fun RestTestClient.ResponseSpec.insufficientScope() = expectStatus().isForbidden()
        .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE) { assertThat(it).contains("""error="insufficient_scope"""") }

    @Test
    fun `a client reads and lists only its own links`() {
        val alice = client()
        val bob = client()
        val a1 = codeOf(create(alice))
        val a2 = codeOf(create(alice))
        val b1 = codeOf(create(bob))

        get("/api/short-links/$a1", alice).expectStatus().isOk()
        assertThat(codesIn(get("/api/short-links", alice))).containsExactly(a2, a1)
        assertThat(codesIn(get("/api/short-links", bob))).containsExactly(b1)
    }

    @Test
    fun `another client's link looks like it does not exist, for reading and for disabling`() {
        val alice = client()
        val bob = client()
        val code = codeOf(create(alice))

        get("/api/short-links/$code", bob)
            .expectStatus().isNotFound()
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
        disable(code, bob).expectStatus().isNotFound()
        redirect(code).expectStatus().isFound()
    }

    @Test
    fun `each operation needs its own scope, and says which one is missing`() {
        val alice = client()
        val code = codeOf(create(alice))

        get("/api/short-links/$code", alice, scopes = "shortlinks:create").insufficientScope()
        get("/api/short-links", alice, scopes = "shortlinks:create").insufficientScope()
        disable(code, alice, scopes = "shortlinks:create shortlinks:read").insufficientScope()
        create(alice, scopes = "shortlinks:read").insufficientScope()
    }

    @Test
    fun `a custom code needs the claim scope in addition to create`() {
        val alice = client()
        val code = "claim-${UUID.randomUUID().toString().take(8)}"

        claim(code, alice, scopes = "shortlinks:create").insufficientScope()
        claim(code, alice, scopes = "shortlinks:claim").insufficientScope()
        claim(code, alice, scopes = "shortlinks:create shortlinks:claim").expectStatus().isCreated()
    }

    @Test
    fun `a POST does not take a code, so the claim scope is decided by the route and not by the body`() {
        val alice = client()

        call(
            HttpMethod.POST, "/api/short-links", alice, "shortlinks:create shortlinks:claim",
            """{"targetUrl":"https://example.com/x","customCode":"not-here-${UUID.randomUUID().toString().take(8)}"}""",
        )
            .expectStatus().isBadRequest()
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `claiming a code again is harmless for its owner, and a 409 for anyone else, who can tell it from a link that is not theirs`() {
        val alice = client()
        val bob = client()
        val code = "mine-${UUID.randomUUID().toString().take(8)}"

        val first = claim(code, alice).expectStatus().isCreated()
            .expectHeader().value(HttpHeaders.LOCATION) { assertThat(it).endsWith("/api/short-links/$code") }
        claim(code, alice).expectStatus().isOk()
            .expectBody(String::class.java).isEqualTo(first.text())
        claim(code, alice, target = "https://example.com/another").expectStatus().isEqualTo(HttpStatus.CONFLICT)
        claim(code, bob).expectStatus().isEqualTo(HttpStatus.CONFLICT)
        get("/api/short-links/$code", alice).expectStatus().isOk()
        get("/api/short-links/$code", bob).expectStatus("what a GET tells the loser", HttpStatus.NOT_FOUND)
        assertThat(codesIn(get("/api/short-links", alice))).containsOnlyOnce(code)
    }

    @Test
    fun `disabling a link answers it disabled, makes it answer 410, keeps its code taken and is idempotent`() {
        val alice = client()
        val code = "gone-${UUID.randomUUID().toString().take(8)}"
        claim(code, alice)

        val first = disable(code, alice).expectStatus().isOk().text()
        val second = disable(code, alice).expectStatus().isOk().text()

        assertThat(first).contains(""""shortCode":"$code"""").contains(""""disabledAt":"2""")
        assertThat(second).describedAs("the second answer keeps the time of the first").isEqualTo(first)

        redirect(code)
            .expectStatus().isEqualTo(HttpStatus.GONE)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(get("/api/short-links/$code", alice).text()).contains(""""disabledAt":"2""")
        claim(code, alice, target = "https://example.com/another").expectStatus().isEqualTo(HttpStatus.CONFLICT)
        claim(code, alice)
            .expectStatus("the same claim again finds the link, disabled", HttpStatus.OK)
            .expectBody(String::class.java).value { assertThat(it).contains(""""disabledAt":"2""") }
    }

    @Test
    fun `an administrator reads, lists and disables any client's links`() {
        val alice = client()
        val admin = client()
        val code = codeOf(create(alice))
        val adminScope = "shortlinks:admin"

        get("/api/short-links/$code", admin, adminScope).expectStatus().isOk()
        assertThat(codesIn(get("/api/short-links?createdBy=$alice", admin, adminScope))).containsExactly(code)
        disable(code, admin, adminScope).expectStatus().isOk()
        redirect(code).expectStatus().isEqualTo(HttpStatus.GONE)
        assertThat(get("/api/short-links/$code", alice).text()).contains(""""disabledAt"""")
    }

    @Test
    fun `an administrator needs no other scope but cannot create links with it alone`() {
        val admin = client()

        create(admin, scopes = "shortlinks:admin").expectStatus().isForbidden()
    }

    @Test
    fun `a client may not list another client's links`() {
        val alice = client()
        val bob = client()
        create(alice)

        get("/api/short-links?createdBy=$alice", bob).expectStatus().isForbidden()
        get("/api/short-links?createdBy=$bob", bob).expectStatus().isOk()
    }

    @Test
    fun `lists in pages, as Spring's page and size parameters ask, to the end without repeats`() {
        val alice = client()
        val created = (1..5).map { codeOf(create(alice)) }

        val seen = mutableListOf<String>()
        var page = 0
        do {
            val response = get("/api/short-links?page=${page++}&size=2", alice)
            seen += codesIn(response)
            val hasNext = response.text().contains(""""hasNext":true""")
        } while (hasNext)

        assertThat(seen).containsExactlyInAnyOrderElementsOf(created).doesNotHaveDuplicates()
        assertThat(page).isEqualTo(3)
    }

    @Test
    fun `says how many links and pages there are, so a client can jump to the last page`() {
        val alice = client()
        val created = (1..5).map { codeOf(create(alice)) }

        val last = get("/api/short-links?page=2&size=2", alice)
        last.expectStatus().isOk()
            .expectBody(String::class.java).value { assertThat(it).contains(""""totalItems":5""", """"totalPages":3""", """"page":2""", """"hasNext":false""") }

        assertThat(codesIn(last)).hasSize(1).isSubsetOf(created)
    }

    @Test
    fun `sorts when asked and refuses to sort by a property links cannot be listed by`() {
        val alice = client()
        val codes = (1..3).map { codeOf(create(alice)) }.sorted()

        val ascending = codesIn(get("/api/short-links?sort=shortCode,asc", alice))

        assertThat(ascending).containsExactlyElementsOf(codes)
        get("/api/short-links?sort=targetUrl,asc", alice)
            .expectStatus().isBadRequest()
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `caps the page size at the configured maximum and falls back to the default for nonsense`() {
        val alice = client()
        create(alice)

        get("/api/short-links?size=100000", alice).expectBody(String::class.java).value { assertThat(it).contains(""""size":200""") }
        get("/api/short-links?size=abc&page=-3", alice)
            .expectStatus().isOk()
            .expectBody(String::class.java).value { assertThat(it).contains(""""size":50""") }
    }

    @Test
    fun `ignores scopes outside the permission model`() {
        val alice = client()

        create(alice, scopes = "shortlinks:create profile email offline_access").expectStatus().isCreated()
    }

    @Test
    fun `every management operation rejects a request without a token`() {
        val code = codeOf(create(client()))

        get("/api/short-links", null).expectStatus().isUnauthorized()
        get("/api/short-links/$code", null).expectStatus().isUnauthorized()
        disable(code, null).expectStatus().isUnauthorized()
    }

    @Test
    fun `a link cannot be removed, there is no DELETE, and a PATCH takes nothing but disabled true`() {
        val alice = client()
        val code = codeOf(create(alice))

        call(HttpMethod.DELETE, "/api/short-links/$code", alice)
            .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
            .expectHeader().value(HttpHeaders.ALLOW) { assertThat(it).contains("PATCH") }
        listOf("""{"disabled":false}""", "{}").forEach { body ->
            call(HttpMethod.PATCH, "/api/short-links/$code", alice, body = body)
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
        }
        redirect(code).expectStatus().isFound()
    }
}
