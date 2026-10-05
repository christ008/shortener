package uy.ct.shortener.shortlink.internal.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.http.client.HttpRedirects
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class)
class AuthorizationIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    private val manage = "shortlinks:create shortlinks:read shortlinks:delete"

    private fun client() = "client-${UUID.randomUUID().toString().take(8)}"

    private fun call(method: HttpMethod, path: String, client: String?, scopes: String = manage, body: String? = null): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            if (client != null) setBearerAuth(TestIdp.token(client = client, scope = scopes))
        }
        return restTemplate.exchange(path, method, HttpEntity(body, headers), String::class.java)
    }

    private fun create(client: String, scopes: String = manage, customCode: String? = null): ResponseEntity<String> {
        val custom = customCode?.let { ""","customCode":"$it"""" } ?: ""
        return call(HttpMethod.POST, "/api/short-links", client, scopes, """{"targetUrl":"https://example.com/${UUID.randomUUID()}"$custom}""")
    }

    private fun codeOf(response: ResponseEntity<String>) = Regex(""""shortCode":"([^"]+)"""").find(response.body!!)!!.groupValues[1]

    private fun redirectStatus(code: String) =
        restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW).getForEntity("/$code", String::class.java)

    private fun codesIn(response: ResponseEntity<String>) = Regex(""""shortCode":"([^"]+)"""").findAll(response.body!!).map { it.groupValues[1] }.toList()

    @Test
    fun `a client reads and lists only its own links`() {
        val alice = client()
        val bob = client()
        val a1 = codeOf(create(alice))
        val a2 = codeOf(create(alice))
        val b1 = codeOf(create(bob))

        assertThat(call(HttpMethod.GET, "/api/short-links/$a1", alice).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(codesIn(call(HttpMethod.GET, "/api/short-links", alice))).containsExactly(a2, a1)
        assertThat(codesIn(call(HttpMethod.GET, "/api/short-links", bob))).containsExactly(b1)
    }

    @Test
    fun `another client's link looks like it does not exist, for reading and for disabling`() {
        val alice = client()
        val bob = client()
        val code = codeOf(create(alice))

        val read = call(HttpMethod.GET, "/api/short-links/$code", bob)
        val delete = call(HttpMethod.DELETE, "/api/short-links/$code", bob)

        assertThat(read.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(delete.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(read.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(redirectStatus(code).statusCode).isEqualTo(HttpStatus.FOUND)
    }

    @Test
    fun `each operation needs its own scope, and says which one is missing`() {
        val alice = client()
        val code = codeOf(create(alice))

        val cannotRead = call(HttpMethod.GET, "/api/short-links/$code", alice, scopes = "shortlinks:create")
        val cannotList = call(HttpMethod.GET, "/api/short-links", alice, scopes = "shortlinks:create")
        val cannotDelete = call(HttpMethod.DELETE, "/api/short-links/$code", alice, scopes = "shortlinks:create shortlinks:read")
        val cannotCreate = create(alice, scopes = "shortlinks:read")

        listOf(cannotRead, cannotList, cannotDelete, cannotCreate).forEach {
            assertThat(it.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
            assertThat(it.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("""error="insufficient_scope"""")
        }
    }

    @Test
    fun `a custom code needs the claim scope in addition to create`() {
        val alice = client()
        val code = "claim-${UUID.randomUUID().toString().take(8)}"

        val without = create(alice, scopes = "shortlinks:create", customCode = code)
        val with = create(alice, scopes = "shortlinks:create shortlinks:claim", customCode = code)

        assertThat(without.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(without.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("""error="insufficient_scope"""")
        assertThat(with.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `disabling a link makes it answer 410, keeps its code taken and is idempotent`() {
        val alice = client()
        val code = "gone-${UUID.randomUUID().toString().take(8)}"
        create(alice, scopes = "$manage shortlinks:claim", customCode = code)

        assertThat(call(HttpMethod.DELETE, "/api/short-links/$code", alice).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(call(HttpMethod.DELETE, "/api/short-links/$code", alice).statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val redirect = redirectStatus(code)
        assertThat(redirect.statusCode).isEqualTo(HttpStatus.GONE)
        assertThat(redirect.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(call(HttpMethod.GET, "/api/short-links/$code", alice).body).contains(""""disabledAt":"2""")
        assertThat(create(alice, scopes = "$manage shortlinks:claim", customCode = code).statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `an administrator reads, lists and disables any client's links`() {
        val alice = client()
        val admin = client()
        val code = codeOf(create(alice))
        val adminScope = "shortlinks:admin"

        assertThat(call(HttpMethod.GET, "/api/short-links/$code", admin, adminScope).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(codesIn(call(HttpMethod.GET, "/api/short-links?createdBy=$alice", admin, adminScope))).containsExactly(code)
        assertThat(call(HttpMethod.DELETE, "/api/short-links/$code", admin, adminScope).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(redirectStatus(code).statusCode).isEqualTo(HttpStatus.GONE)
        assertThat(call(HttpMethod.GET, "/api/short-links/$code", alice).body).contains(""""disabledAt"""")
    }

    @Test
    fun `an administrator needs no other scope but cannot create links with it alone`() {
        val admin = client()

        assertThat(create(admin, scopes = "shortlinks:admin").statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `a client may not list another client's links`() {
        val alice = client()
        val bob = client()
        create(alice)

        val others = call(HttpMethod.GET, "/api/short-links?createdBy=$alice", bob)
        val own = call(HttpMethod.GET, "/api/short-links?createdBy=$bob", bob)

        assertThat(others.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(own.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `lists in pages, as Spring's page and size parameters ask, to the end without repeats`() {
        val alice = client()
        val created = (1..5).map { codeOf(create(alice)) }

        val seen = mutableListOf<String>()
        var page = 0
        do {
            val response = call(HttpMethod.GET, "/api/short-links?page=${page++}&size=2", alice)
            seen += codesIn(response)
            val hasNext = response.body!!.contains(""""hasNext":true""")
        } while (hasNext)

        assertThat(seen).containsExactlyInAnyOrderElementsOf(created).doesNotHaveDuplicates()
        assertThat(page).isEqualTo(3)
    }

    @Test
    fun `says how many links and pages there are, so a client can jump to the last page`() {
        val alice = client()
        val created = (1..5).map { codeOf(create(alice)) }

        val last = call(HttpMethod.GET, "/api/short-links?page=2&size=2", alice)

        assertThat(last.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(last.body).contains(""""totalItems":5""", """"totalPages":3""", """"page":2""", """"hasNext":false""")
        assertThat(codesIn(last)).hasSize(1).isSubsetOf(created)
    }

    @Test
    fun `sorts when asked and refuses to sort by a property links cannot be listed by`() {
        val alice = client()
        val codes = (1..3).map { codeOf(create(alice)) }.sorted()

        val ascending = codesIn(call(HttpMethod.GET, "/api/short-links?sort=shortCode,asc", alice))
        val rejected = call(HttpMethod.GET, "/api/short-links?sort=targetUrl,asc", alice)

        assertThat(ascending).containsExactlyElementsOf(codes)
        assertThat(rejected.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(rejected.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `caps the page size at the configured maximum and falls back to the default for nonsense`() {
        val alice = client()
        create(alice)

        val huge = call(HttpMethod.GET, "/api/short-links?size=100000", alice)
        val nonsense = call(HttpMethod.GET, "/api/short-links?size=abc&page=-3", alice)

        assertThat(huge.body).contains(""""size":200""")
        assertThat(nonsense.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(nonsense.body).contains(""""size":50""")
    }

    @Test
    fun `ignores scopes outside the permission model`() {
        val alice = client()

        val response = create(alice, scopes = "shortlinks:create profile email offline_access")

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `every management operation rejects a request without a token`() {
        val code = codeOf(create(client()))

        assertThat(call(HttpMethod.GET, "/api/short-links", null).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(call(HttpMethod.GET, "/api/short-links/$code", null).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(call(HttpMethod.DELETE, "/api/short-links/$code", null).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
