package uy.ct.shortener.shortlink.internal.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.hibernate.validator.constraints.URL
import uy.ct.shortener.shortlink.ShortCode

/**
 * Body of `POST /api/short-links`.
 *
 * - [targetUrl]: a non-blank URL.
 * - [customCode]: optional, requests a specific short code instead of a generated one. Absent in
 *   the JSON means generated.
 */
data class CreateShortLinkRequest(
    @field:NotBlank
    @field:URL
    val targetUrl: String,

    @field:Pattern(regexp = ShortCode.PATTERN)
    val customCode: String? = null,
)
