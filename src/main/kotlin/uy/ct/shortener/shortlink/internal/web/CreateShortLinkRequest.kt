package uy.ct.shortener.shortlink.internal.web

import jakarta.validation.constraints.NotBlank
import org.hibernate.validator.constraints.URL

data class CreateShortLinkRequest(
    @field:NotBlank
    @field:URL
    val targetUrl: String,
)
