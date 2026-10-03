package uy.ct.shortener.shortlink.internal.web

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import java.security.Principal
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLinkService

/**
 * `POST /api/short-links` creates a link for the authenticated client, under a generated or a
 * custom code (201 with a `Location` header).
 * `GET /{shortCode}` redirects to its target with a 302, so clients don't cache the redirect.
 * Errors are returned as problem details.
 */
@RestController
class ShortLinkController(
    private val service: ShortLinkService,
) {

    @PostMapping("/api/short-links")
    fun create(
        @Valid @RequestBody request: CreateShortLinkRequest,
        principal: Principal,
        uriBuilder: UriComponentsBuilder,
    ): ResponseEntity<ShortLinkResponse> {
        val shortLink = request.customCode
            ?.let { service.claim(ShortCode(it), request.targetUrl, principal.name) }
            ?: service.shorten(request.targetUrl, principal.name)
        val location = uriBuilder.replacePath("/{shortCode}").build(shortLink.shortCode.value)
        return ResponseEntity.created(location).body(ShortLinkResponse.from(shortLink))
    }

    @GetMapping("/{shortCode}")
    fun redirect(@PathVariable shortCode: ShortCode): ResponseEntity<Void> {
        val shortLink = service.resolve(shortCode)
        return ResponseEntity.status(HttpStatus.FOUND).location(shortLink.targetUrl).build()
    }
}
