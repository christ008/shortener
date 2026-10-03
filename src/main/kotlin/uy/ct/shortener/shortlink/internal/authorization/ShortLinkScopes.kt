package uy.ct.shortener.shortlink.internal.authorization

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

/**
 * The OAuth scope that grants each operation on short links, bound from
 * `shortener.shortlink.scopes.*` so the names are configuration, not code. It is registered as the
 * bean `scopes`, which the method-security expressions refer to (`@scopes.read`). Tokens carry
 * these scopes as plain authorities, with no prefix. A blank name would make an operation
 * unreachable or open to the wrong tokens, so the application refuses to start with one.
 *
 * - [create]: make a link with a generated code. [claim] is needed as well to choose a code.
 * - [read] and [delete]: read and disable the client's own links.
 * - [admin]: read and disable any client's links, for abuse takedowns.
 */
@Validated
@Component("scopes")
@ConfigurationProperties("shortener.shortlink.scopes")
class ShortLinkScopes {
    @field:NotBlank
    var create: String = "shortlinks:create"
    @field:NotBlank
    var claim: String = "shortlinks:claim"
    @field:NotBlank
    var read: String = "shortlinks:read"
    @field:NotBlank
    var delete: String = "shortlinks:delete"
    @field:NotBlank
    var admin: String = "shortlinks:admin"
}
