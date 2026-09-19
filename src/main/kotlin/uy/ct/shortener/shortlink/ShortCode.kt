package uy.ct.shortener.shortlink

/** The public-facing identifier of a [ShortLink]. Plain `data class`, not a `value class`, to avoid Hibernate/`AttributeConverter` interop issues. */
data class ShortCode(val value: String) {

    init {
        require(value.length == LENGTH) {
            "shortCode must be exactly $LENGTH characters, was ${value.length} ('$value')"
        }
        require(value.all { it in ALPHABET }) {
            "shortCode must only contain [A-Za-z0-9], was '$value'"
        }
    }

    override fun toString(): String = value

    companion object {
        /** Base62: unambiguous and URL-safe without percent-encoding. */
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        /** 62^7 (~3.5 trillion) possible codes - keeps generate-and-retry collisions rare. */
        const val LENGTH = 7
    }
}
