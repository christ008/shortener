package uy.ct.shortener.shortlink.internal.web

import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.SortDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.ClaimResult
import uy.ct.shortener.shortlink.CreatedByFilter
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkService
import uy.ct.shortener.shortlink.internal.authorization.ShortLinkScopes
import java.net.URI

/**
 * The HTTP API of the short link service. Failures are problem details.
 *
 * - `POST /api/short-links` creates a link for the authenticated client under a generated code: `201` with the link and its
 *   `Location`, `/api/short-links/{shortCode}`. The link's `shortUrl` is the URL to share.
 * - `PUT /api/short-links/{shortCode}` creates it under the code the client chose: `201`. Repeating it, with the same target,
 *   is harmless and answers `200` with the link. Any other claim on a code that exists is `409`.
 * - `GET /api/short-links` lists the client's own links a page at a time (`page`, `size`, `sort`). An administrator may list
 *   any client's.
 * - `GET /api/short-links/{shortCode}` reads one, and `PATCH` with `{"disabled": true}` disables it: `200` with the link.
 * - `GET /{shortCode}` is public. It answers `302`, `404` for an unknown code, or `410` once the link is disabled.
 *
 * The `Location` and every `shortUrl` are built from the request's own scheme, host and port.
 */
@RestController
class ShortLinkController(
    private val service: ShortLinkService,
    private val scopes: ShortLinkScopes,
) {

    private fun Authentication.isAdmin() = authorities.any { it.authority == scopes.admin }

    private fun Authentication.asClient() = Actor.Client(name)

    /** The URL to share for [shortCode]: `/{shortCode}` on the host of this request. */
    private fun UriComponentsBuilder.shortUrl(shortCode: ShortCode): URI =
        cloneBuilder().replacePath("/{shortCode}").build(shortCode.value)

    @PostMapping("/api/short-links")
    fun create(
        @Valid @RequestBody request: CreateShortLinkRequest,
        authentication: Authentication,
        uriBuilder: UriComponentsBuilder,
    ): ResponseEntity<ShortLinkResponse> = created(service.shorten(request.targetUrl, authentication.asClient()), uriBuilder)

    @PutMapping("/api/short-links/{shortCode}")
    fun claim(
        @PathVariable shortCode: ShortCode,
        @Valid @RequestBody request: ClaimShortLinkRequest,
        authentication: Authentication,
        uriBuilder: UriComponentsBuilder,
    ): ResponseEntity<ShortLinkResponse> =
        when (val claimed = service.claim(shortCode, request.targetUrl, authentication.asClient())) {
            is ClaimResult.Created -> created(claimed.link, uriBuilder)
            is ClaimResult.Existing -> ResponseEntity.ok(ShortLinkResponse.from(claimed.link, uriBuilder.shortUrl(shortCode)))
        }

    private fun created(shortLink: ShortLink, uriBuilder: UriComponentsBuilder): ResponseEntity<ShortLinkResponse> {
        val location = uriBuilder.cloneBuilder().replacePath("/api/short-links/{shortCode}").build(shortLink.shortCode.value)
        return ResponseEntity.created(location).body(ShortLinkResponse.from(shortLink, uriBuilder.shortUrl(shortLink.shortCode)))
    }

    @GetMapping("/api/short-links")
    fun list(
        authentication: Authentication,
        @RequestParam(required = false) createdBy: String?,
        @SortDefault(sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
        uriBuilder: UriComponentsBuilder,
    ): ShortLinkPageResponse {
        val filter = CreatedByFilter.of(createdBy, authentication.name, authentication.isAdmin())
        return ShortLinkPageResponse.from(service.list(filter, pageable)) { uriBuilder.shortUrl(it) }
    }

    @GetMapping("/api/short-links/{shortCode}")
    fun get(@PathVariable shortCode: ShortCode, uriBuilder: UriComponentsBuilder): ShortLinkResponse =
        ShortLinkResponse.from(service.get(shortCode), uriBuilder.shortUrl(shortCode))

    /** [UpdateShortLinkRequest] accepts only `{"disabled": true}`, so disabling is the one change a link takes. */
    @PatchMapping("/api/short-links/{shortCode}")
    fun update(
        @PathVariable shortCode: ShortCode,
        @Valid @RequestBody request: UpdateShortLinkRequest,
        authentication: Authentication,
        uriBuilder: UriComponentsBuilder,
    ): ShortLinkResponse =
        ShortLinkResponse.from(service.disable(shortCode, authentication.asClient()), uriBuilder.shortUrl(shortCode))

    @GetMapping("/{shortCode}")
    fun redirect(@PathVariable shortCode: ShortCode): ResponseEntity<Void> {
        val shortLink = service.resolve(shortCode)
        return ResponseEntity.status(HttpStatus.FOUND).location(shortLink.targetUrl).build()
    }
}
