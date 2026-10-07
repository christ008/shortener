package uy.ct.shortener.shortlink.internal.web

import jakarta.validation.constraints.NotBlank
import org.hibernate.validator.constraints.URL

/**
 * Body of `PUT /api/short-links/{shortCode}`: the target the caller wants under the code in the path.
 */
data class ClaimShortLinkRequest(
    @field:NotBlank
    @field:URL
    val targetUrl: String,
)
