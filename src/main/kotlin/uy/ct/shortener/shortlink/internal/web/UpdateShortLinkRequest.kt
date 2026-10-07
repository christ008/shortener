package uy.ct.shortener.shortlink.internal.web

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotNull

/**
 * Body of `PATCH /api/short-links/{shortCode}`, a JSON merge patch (RFC 7396).
 *
 * - [disabled] is the only field a link takes, and it must be there and be `true`: a disabled link cannot be enabled
 *   again, which needs a rule on who may undo a takedown. A schema that allows `false` later breaks no caller.
 */
data class UpdateShortLinkRequest(
    @field:NotNull
    @field:AssertTrue
    val disabled: Boolean?,
)
