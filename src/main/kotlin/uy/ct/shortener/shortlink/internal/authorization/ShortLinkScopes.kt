package uy.ct.shortener.shortlink.internal.authorization

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.validation.annotation.Validated

/**
 * The OAuth scope that grants each operation on short links, bound from `shortener.shortlink.scopes.*`
 * so the names are configuration, not code.
 *
 * - Immutable: bound through the constructor, so the rules cannot change after the application starts. The defaults
 *   are `@DefaultValue`s, because Kotlin default arguments would add a no-argument constructor that Spring rejects.
 * - Available as the bean `scopes`, which the method-security expressions refer to (`@scopes.read`), through
 *   [ShortLinkScopesConfiguration].
 * - Tokens carry the scopes as plain authorities, with no prefix.
 * - A blank name would make an operation unreachable or open to the wrong tokens, so the
 *   application refuses to start with one.
 *
 * The scopes:
 * - [create]: make a link with a generated code. [claim] is needed as well to choose the code.
 * - [read] and [delete]: read and disable the client's own links.
 * - [admin]: read and disable any client's links, for abuse takedowns.
 */
@Validated
@ConfigurationProperties("shortener.shortlink.scopes")
class ShortLinkScopes @ConstructorBinding constructor(
    @field:NotBlank @DefaultValue("shortlinks:create") val create: String,
    @field:NotBlank @DefaultValue("shortlinks:claim") val claim: String,
    @field:NotBlank @DefaultValue("shortlinks:read") val read: String,
    @field:NotBlank @DefaultValue("shortlinks:delete") val delete: String,
    @field:NotBlank @DefaultValue("shortlinks:admin") val admin: String,
)
