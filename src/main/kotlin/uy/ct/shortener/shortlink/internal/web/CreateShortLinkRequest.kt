package uy.ct.shortener.shortlink.internal.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Null
import org.hibernate.validator.constraints.URL

/**
 * Body of `POST /api/short-links`, which always gets a generated code.
 *
 * - [targetUrl]: a non-blank URL.
 * - [customCode]: must be absent. A code is chosen with `PUT /api/short-links/{shortCode}`, and a field the JSON mapper would
 *   drop unseen would answer a caller who still sends it with a link under another code than the one they asked for.
 */
data class CreateShortLinkRequest(
    @field:NotBlank
    @field:URL
    val targetUrl: String,

    @field:Null
    val customCode: String? = null,
)
