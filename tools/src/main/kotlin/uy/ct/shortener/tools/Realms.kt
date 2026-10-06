package uy.ct.shortener.tools

import java.nio.file.Path

/**
 * Makes a Keycloak realm from a template, filling in the public keys of its clients and, for development, the passwords of its users.
 *
 *     ./gradlew productionRealm -Pdemo=FILE -Padmin=FILE [-Poutput=FILE]     the two public keys, line 2 of what keygen prints
 *     tools/run Realms render TEMPLATE OUTPUT [--jwks CLIENT=PUBLIC_JWK_FILE]... [--password USER=ENVIRONMENT_VARIABLE]...
 *     tools/run Realms production DEMO_PUBLIC_JWK_FILE ADMIN_PUBLIC_JWK_FILE [OUTPUT]
 *
 * `render` replaces `@JWKS:CLIENT@` with a key set holding that key, and `@PASSWORD:USER@` with the value of the named
 * environment variable. Every placeholder in the template must be given and every value given must be in the template. A file
 * with a private key (it has a `d`) is refused.
 *
 * `production` is `render` for the production template with `demo-client` and `admin-client`, into
 * `deploy/keycloak/shortener-realm.production.json` unless an output is named. `./gradlew keygen -Pclient=NAME` prints the
 * private key on line 1 and the public key on line 2, which is what this takes.
 */
object Realms : Tool(
    "Realms",
    """
    usage: tools/run Realms render TEMPLATE OUTPUT [--jwks CLIENT=FILE]... [--password USER=VARIABLE]...
           tools/run Realms production DEMO_PUBLIC_JWK_FILE ADMIN_PUBLIC_JWK_FILE [OUTPUT]
    """.trimIndent(),
) {

    private const val PRODUCTION_TEMPLATE = "deploy/keycloak/shortener-realm.production.template.json"

    private const val PRODUCTION_OUTPUT = "deploy/keycloak/shortener-realm.production.json"

    override fun run(arguments: List<String>, context: Context) {
        if (arguments.isEmpty()) throw Usage("a command is needed")
        when (arguments[0]) {
            "render" -> render(arguments, context)
            "production" -> production(arguments, context)
            else -> throw Usage("unknown command ${arguments[0]}")
        }
    }

    private fun production(arguments: List<String>, context: Context) {
        if (arguments.size !in 3..4) throw Usage("production takes the two public key files and optionally an output")
        val jwks = linkedMapOf(
            "demo-client" to publicKey(context.path(arguments[1]), context),
            "admin-client" to publicKey(context.path(arguments[2]), context),
        )
        val realm = RealmTemplate.fill(context.read(context.path(PRODUCTION_TEMPLATE)), jwks, emptyMap())
        write(realm, context.path(arguments.getOrNull(3) ?: PRODUCTION_OUTPUT), context)
    }

    private fun render(arguments: List<String>, context: Context) {
        if (arguments.size < 3) throw Usage("render takes a template and an output")
        val jwks = linkedMapOf<String, String>()
        val passwords = linkedMapOf<String, String>()
        for (i in 3 until arguments.size step 2) {
            val option = arguments[i]
            if (i + 1 >= arguments.size || option != "--jwks" && option != "--password") {
                throw Usage("expected --jwks or --password with a value, found $option")
            }
            val pair = arguments[i + 1]
            val equals = pair.indexOf('=')
            if (equals < 1) throw Usage("expected NAME=VALUE, found $pair")
            val name = pair.substring(0, equals)
            val value = pair.substring(equals + 1)
            if (option == "--jwks") {
                jwks[name] = publicKey(context.path(value), context)
            } else {
                val password = context.environment[value]
                if (password.isNullOrEmpty()) throw Failure("the environment variable $value for the password of $name is not set")
                passwords[name] = password
            }
        }
        write(RealmTemplate.fill(context.read(context.path(arguments[1])), jwks, passwords), context.path(arguments[2]), context)
    }

    private fun publicKey(file: Path, context: Context) = RealmTemplate.publicKey(context.read(file), file.toString())

    private fun write(realm: String, output: Path, context: Context) {
        context.writeAtomically(output, realm, "rw-r--r--")
        context.out.println("wrote $output")
    }
}
