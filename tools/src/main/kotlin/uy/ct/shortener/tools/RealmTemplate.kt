package uy.ct.shortener.tools

/**
 * A realm template and what goes into it: the public key of each client where the template has `@JWKS:CLIENT@`, and the
 * password of each user where it has `@PASSWORD:USER@`. Shared by [Realms], which makes a realm from files, and [DevSetup],
 * which makes one from keys it has just made.
 *
 * - Every placeholder in the template must be given and every one given must be in the template, so that a typo is an error and
 *   not a realm that trusts nothing.
 * - A key with a private part (a `d`) is refused: a realm is imported into a server and kept.
 * - Values are put inside JSON strings, so a quote or a backslash in a password stays part of the password.
 */
object RealmTemplate {

    private val PLACEHOLDER = Regex("@(JWKS|PASSWORD):([A-Za-z0-9._-]+)@")

    private val PRIVATE_KEY_MEMBER = Regex("\"d\"\\s*:")

    fun fill(template: String, jwks: Map<String, String>, passwords: Map<String, String>): String {
        val values = LinkedHashMap<String, String>()
        jwks.forEach { (client, key) -> values["@JWKS:$client@"] = jsonString("""{"keys":[$key]}""") }
        passwords.forEach { (user, password) -> values["@PASSWORD:$user@"] = jsonString(password) }

        val unused = values.keys.filter { it !in template }
        if (unused.isNotEmpty()) throw Failure("the template has no placeholder ${unused.joinToString(", ")}")

        var realm = template
        values.forEach { (placeholder, value) -> realm = realm.replace(placeholder, value) }

        val missing = PLACEHOLDER.findAll(realm).map { it.value }.distinct().toList()
        if (missing.isNotEmpty()) throw Failure("no value was given for ${missing.joinToString(", ")}")
        return realm
    }

    /** The public key in [json], which [where] names for the message, or a failure if it is not a public key. */
    fun publicKey(json: String, where: String): String {
        val key = json.trim()
        if (!key.startsWith("{") || !key.endsWith("}")) {
            throw Failure("$where is not a JSON object: give the public key, line 2 of what keygen prints")
        }
        if (PRIVATE_KEY_MEMBER.containsMatchIn(key)) {
            throw Failure("$where holds a private key (it has a \"d\"): give the public half, line 2 of what keygen prints")
        }
        return key
    }
}
