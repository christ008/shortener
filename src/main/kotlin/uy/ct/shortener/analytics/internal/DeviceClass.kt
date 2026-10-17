package uy.ct.shortener.analytics.internal

/**
 * The kind of client that followed a link, as far as its `User-Agent` says.
 *
 * - [DESKTOP] is any browser that is not [MOBILE].
 * - [BOT] covers crawlers, link previewers and command line clients. A request with no `User-Agent` is one, because browsers
 *   always send it.
 * - [value] is what the database stores.
 */
enum class DeviceClass(val value: String) {
    DESKTOP("desktop"),
    MOBILE("mobile"),
    BOT("bot");

    companion object {

        /** The class the database stores as [value], which its constraint keeps to the three there are. */
        fun of(value: String): DeviceClass = entries.single { it.value == value }
    }
}

/**
 * Classifies a `User-Agent` by the substrings that tell the three classes apart. It is a heuristic: a bot that sends a browser's
 * header counts as a visitor, and ADR 0037 says so.
 *
 * - Only the first [MAX_LENGTH] characters are read, so a long header costs no more than a short one.
 * - The header is lowercased once, in the root locale, and then searched for each token. A search that ignores case for each token
 *   costs about twenty times as much, and this runs in the thread of every redirect.
 * - Bots are checked first: a previewer's header often names a phone too.
 */
object DeviceClassifier {

    private const val MAX_LENGTH = 512

    private val BOT_TOKENS = listOf(
        "bot", "crawl", "spider", "slurp", "preview", "facebookexternalhit", "whatsapp", "curl/", "wget/", "python-", "go-http-client",
        "httpclient", "libwww", "headlesschrome",
    )

    private val MOBILE_TOKENS = listOf("mobi", "android", "iphone", "ipad", "ipod")

    fun classify(userAgent: String): DeviceClass {
        val header = userAgent.take(MAX_LENGTH).lowercase()
        return when {
            header.isBlank() || BOT_TOKENS.any { it in header } -> DeviceClass.BOT
            MOBILE_TOKENS.any { it in header } -> DeviceClass.MOBILE
            else -> DeviceClass.DESKTOP
        }
    }
}
