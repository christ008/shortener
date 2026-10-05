package uy.ct.shortener.shortlink

/**
 * The public identifier of a [ShortLink]: 3 to 32 letters, digits, `-` or `_` ([PATTERN]).
 *
 * - Generated codes are [GENERATED_LENGTH] base62 characters. Custom codes may be any valid value.
 * - The format is checked on construction, so an invalid code cannot exist.
 */
data class ShortCode(val value: String) {

    init {
        require(REGEX.matches(value)) { "shortCode must match $PATTERN, was '$value'" }
    }

    override fun toString(): String = value

    companion object {
        const val PATTERN = "[A-Za-z0-9_-]{3,32}"

        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        const val GENERATED_LENGTH = 7

        private val REGEX = PATTERN.toRegex()
    }
}
