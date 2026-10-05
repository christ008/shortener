package uy.ct.shortener.shortlink.internal.web

import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.SortDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLinkService
import uy.ct.shortener.shortlink.internal.authorization.ShortLinkScopes

/**
 * `POST /api/short-links` creates a link for the authenticated client, under a generated or a
 * custom code (201 with a `Location` header). `GET /api/short-links` lists the client's own links,
 * a page at a time (`page`, `size` and `sort`), `GET /api/short-links/{shortCode}` reads one, and `DELETE`
 * disables one (204). An administrator reaches every client's links. `GET /{shortCode}` is public
 * and redirects to the target with a 302, so clients don't cache the redirect, or answers 410 once
 * the link is disabled. Errors are returned as problem details.
 */
@RestController
class ShortLinkController(
    private val service: ShortLinkService,
    private val scopes: ShortLinkScopes,
) {

    private fun Authentication.isAdmin() = authorities.any { it.authority == scopes.admin }

    @PostMapping("/api/short-links")
    fun create(
        @Valid @RequestBody request: CreateShortLinkRequest,
        authentication: Authentication,
        uriBuilder: UriComponentsBuilder,
    ): ResponseEntity<ShortLinkResponse> {
        val shortLink = request.customCode
            ?.let { service.claim(ShortCode(it), request.targetUrl, authentication.name) }
            ?: service.shorten(request.targetUrl, authentication.name)
        val location = uriBuilder.replacePath("/{shortCode}").build(shortLink.shortCode.value)
        return ResponseEntity.created(location).body(ShortLinkResponse.from(shortLink))
    }

    @GetMapping("/api/short-links")
    fun list(
        authentication: Authentication,
        @RequestParam(required = false) createdBy: String?,
        @SortDefault(sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): ShortLinkPageResponse {
        val owner = createdBy ?: authentication.name.takeUnless { authentication.isAdmin() }
        return ShortLinkPageResponse.from(service.list(owner, pageable))
    }

    @GetMapping("/api/short-links/{shortCode}")
    fun get(@PathVariable shortCode: ShortCode, authentication: Authentication): ShortLinkResponse =
        ShortLinkResponse.from(service.get(shortCode))

    @DeleteMapping("/api/short-links/{shortCode}")
    fun disable(@PathVariable shortCode: ShortCode, authentication: Authentication): ResponseEntity<Void> {
        service.disable(shortCode, authentication.name)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{shortCode}")
    fun redirect(@PathVariable shortCode: ShortCode): ResponseEntity<Void> {
        val shortLink = service.resolve(shortCode)
        return ResponseEntity.status(HttpStatus.FOUND).location(shortLink.targetUrl).build()
    }
}
