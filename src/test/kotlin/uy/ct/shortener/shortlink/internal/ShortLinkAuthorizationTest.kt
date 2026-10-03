package uy.ct.shortener.shortlink.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortCodeGenerator
import uy.ct.shortener.shortlink.ShortLinkNotFoundException
import uy.ct.shortener.shortlink.ShortLinkRepository
import uy.ct.shortener.shortlink.ShortLinkService
import uy.ct.shortener.shortlink.internal.authorization.ManageableLinks
import uy.ct.shortener.shortlink.internal.authorization.NotFoundWhenDenied
import uy.ct.shortener.shortlink.internal.authorization.ShortLinkMethodSecurityConfiguration
import uy.ct.shortener.shortlink.internal.authorization.ShortLinkScopes
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * Who may do what, with Spring's method security applied for real but no web layer and no
 * database: a small context holds the service, the ownership rule and an in-memory repository, and
 * each test acts as a client holding some scopes. Scope names are the defaults of `ShortLinkScopes`.
 */
@SpringJUnitConfig(ShortLinkAuthorizationTest.Context::class)
class ShortLinkAuthorizationTest {

    @Configuration
    @EnableMethodSecurity
    @Import(
        ShortLinkScopes::class,
        ShortLinkMethodSecurityConfiguration::class,
        ManageableLinks::class,
        NotFoundWhenDenied::class,
        DefaultShortLinkService::class,
    )
    class Context {
        @Bean
        fun repository(): ShortLinkRepository = InMemoryShortLinkRepository()

        @Bean
        fun generator(): ShortCodeGenerator {
            val next = AtomicInteger()
            return ShortCodeGenerator { ShortCode("gen%04d".format(next.incrementAndGet())) }
        }
    }

    @Autowired
    lateinit var service: ShortLinkService

    @AfterEach
    fun clearSecurityContext() = SecurityContextHolder.clearContext()

    private val manage = arrayOf("shortlinks:create", "shortlinks:read", "shortlinks:delete")
    private val everything = arrayOf("shortlinks:create", "shortlinks:claim", "shortlinks:read", "shortlinks:delete")
    private val admin = arrayOf("shortlinks:admin")

    private fun <T> actingAs(name: String, vararg scopes: String, action: () -> T): T {
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(name, null, *scopes)
        return action()
    }

    private fun create(client: String, vararg scopes: String = manage) =
        actingAs(client, *scopes) { service.shorten("https://example.com/$client", client) }

    private val firstPage = PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt"))

    @Test
    fun `creating needs the create scope and must be done in the caller's own name`() {
        assertFailsWith<AccessDeniedException> { actingAs("reader", "shortlinks:read") { service.shorten("https://example.com", "reader") } }
        assertFailsWith<AccessDeniedException> { actingAs("admin", *admin) { service.shorten("https://example.com", "admin") } }
        assertFailsWith<AccessDeniedException> { actingAs("mallory", *manage) { service.shorten("https://example.com", "victim") } }

        assertThat(create("alice").createdBy).isEqualTo("alice")
    }

    @Test
    fun `a custom code needs the claim scope as well as create`() {
        val code = ShortCode("my-promo")

        assertFailsWith<AccessDeniedException> { actingAs("alice", *manage) { service.claim(code, "https://example.com", "alice") } }
        assertFailsWith<AccessDeniedException> { actingAs("alice", "shortlinks:claim") { service.claim(code, "https://example.com", "alice") } }

        assertThat(actingAs("alice", *everything) { service.claim(code, "https://example.com", "alice") }.shortCode).isEqualTo(code)
    }

    @Test
    fun `a client reads its own link but another client's is not found`() {
        val link = create("alice")

        assertThat(actingAs("alice", *manage) { service.get(link.shortCode) }.createdBy).isEqualTo("alice")
        assertFailsWith<ShortLinkNotFoundException> { actingAs("bob", *manage) { service.get(link.shortCode) } }
        assertFailsWith<ShortLinkNotFoundException> { actingAs("alice", *manage) { service.get(ShortCode("nothere")) } }
    }

    @Test
    fun `reading needs the read or admin scope, and an administrator reads any link`() {
        val link = create("alice")

        assertFailsWith<AccessDeniedException> { actingAs("alice", "shortlinks:create") { service.get(link.shortCode) } }
        assertThat(actingAs("root", *admin) { service.get(link.shortCode) }.shortCode).isEqualTo(link.shortCode)
    }

    @Test
    fun `a client lists only its own links and may not ask for another client's`() {
        create("lister")
        create("other-lister")

        val own = actingAs("lister", *manage) { service.list("lister", firstPage) }

        assertThat(own.content.map { it.createdBy }).containsOnly("lister")
        assertFailsWith<AccessDeniedException> { actingAs("lister", *manage) { service.list("other-lister", firstPage) } }
        assertFailsWith<AccessDeniedException> { actingAs("lister", *manage) { service.list(null, firstPage) } }
        assertFailsWith<AccessDeniedException> { actingAs("lister", "shortlinks:create") { service.list("lister", firstPage) } }
    }

    @Test
    fun `an administrator lists every client's links, or those of one`() {
        create("lister")
        create("other-lister")

        assertThat(actingAs("root", *admin) { service.list(null, firstPage) }.content.map { it.createdBy }).contains("lister", "other-lister")
        assertThat(actingAs("root", *admin) { service.list("lister", firstPage) }.content.map { it.createdBy }).containsOnly("lister")
    }

    @Test
    fun `a client disables its own link, another client's is not found, and an administrator may disable any`() {
        val mine = create("alice")
        val theirs = create("bob")

        actingAs("alice", *manage) { service.disable(mine.shortCode, "alice") }
        assertFailsWith<ShortLinkNotFoundException> { actingAs("alice", *manage) { service.disable(theirs.shortCode, "alice") } }
        actingAs("root", *admin) { service.disable(theirs.shortCode, "root") }

        assertThat(actingAs("alice", *manage) { service.get(mine.shortCode) }.isDisabled).isTrue
        assertThat(actingAs("root", *admin) { service.get(theirs.shortCode) }.disabledBy).isEqualTo("root")
    }

    @Test
    fun `disabling needs the delete or admin scope and must be done in the caller's own name`() {
        val link = create("alice")

        assertFailsWith<AccessDeniedException> { actingAs("alice", "shortlinks:create", "shortlinks:read") { service.disable(link.shortCode, "alice") } }
        assertFailsWith<AccessDeniedException> { actingAs("alice", *manage) { service.disable(link.shortCode, "someone-else") } }
    }

    @Test
    fun `resolving a link is public and needs no caller at all`() {
        val link = create("alice")
        SecurityContextHolder.clearContext()

        assertThat(service.resolve(link.shortCode).shortCode).isEqualTo(link.shortCode)
    }
}
