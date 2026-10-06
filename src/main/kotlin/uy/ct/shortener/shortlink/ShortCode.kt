package uy.ct.shortener.shortlink

/**
 * The public identifier of a [ShortLink]: 3 to 32 letters, digits, `-` or `_` ([PATTERN]).
 *
 * - Generated codes are [GENERATED_LENGTH] base62 characters. Custom codes may be any valid value.
 * - The format is checked on construction.
 * - [RESERVED] codes cannot be chosen: [requireClaimable] throws for them.
 */
data class ShortCode(val value: String) {

    init {
        require(REGEX.matches(value)) { "shortCode must match $PATTERN, was '$value'" }
    }

    override fun toString(): String = value

    /** Returns this code, or throws [ShortCodeUnavailableException] if it is one the service keeps for itself. */
    fun requireClaimable(): ShortCode = if (value in RESERVED) throw ShortCodeUnavailableException(this) else this

    companion object {
        const val PATTERN = "[A-Za-z0-9_-]{3,32}"

        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        const val GENERATED_LENGTH = 7

        val RESERVED = setOf("api", "actuator", "error")

        private val REGEX = PATTERN.toRegex()
    }
}
