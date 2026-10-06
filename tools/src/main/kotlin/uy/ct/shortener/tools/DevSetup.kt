package uy.ct.shortener.tools

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom

/**
 * Makes the keys, realm and passwords of a local development setup.
 *
 *     deploy/keycloak/dev-setup              asks for each setting, offering a random value
 *     deploy/keycloak/dev-setup --yes        takes every default, and asks nothing (for CI and scripts)
 *     deploy/keycloak/dev-setup --force      replaces what is already there
 *     deploy/keycloak/dev-setup --show       prints the passwords of an existing setup
 *     ./gradlew devSetup                     the same as --yes; -Pforce adds --force
 *
 * It writes, all of it ignored by Git:
 *
 * - `deploy/keycloak/dev-keys/<client>.jwk.json`: a private key for each of the four dev clients (P-256, made by the DPoP client).
 * - `deploy/keycloak/shortener-realm.json`: the dev realm, made from `shortener-realm.template.json` with their public keys.
 * - `.env`: the passwords `compose.yaml` reads, merged into what is already there.
 *
 * Each setting can also be given in the environment: DEV_KEYS_DIR, DEV_KEYCLOAK_ADMIN_USER, DEV_KEYCLOAK_ADMIN_PASSWORD,
 * DEV_POSTGRES_PASSWORD, DEV_APP_PASSWORD, DEV_MIGRATOR_PASSWORD, DEV_EXPORTER_PASSWORD, DEV_USER_ALICE_PASSWORD and
 * DEV_USER_BOB_PASSWORD. It asks when there is a terminal and takes the defaults anywhere else.
 */
object DevSetup : Tool("DevSetup", "usage: deploy/keycloak/dev-setup [--yes] [--force] [--show]", label = "dev-setup") {

    private val BANNER = """
           ____  _                _
          / ___|| |__   ___  _ __| |_ ___ _ __   ___ _ __
          \___ \| '_ \ / _ \| '__| __/ _ \ '_ \ / _ \ '__|
           ___) | | | | (_) | |  | ||  __/ | | |  __/ |
          |____/|_| |_|\___/|_|   \__\___|_| |_|\___|_|

          development setup: throwaway keys and passwords

          ----------------------------------------------------------------------------
           FOR LOCAL DEVELOPMENT ONLY. Never reuse any of this in a deployment: a real
           one has its own identity provider, its own keys and its own secrets.
          ----------------------------------------------------------------------------

          This makes, and writes where Git ignores them:
            - a private key for each dev client (demo, other, admin, no-scope)
            - the dev realm for Keycloak, trusting those keys and these passwords
            - the passwords of Keycloak, its two web users and Postgres, in .env
        """.trimIndent().let(::indented)

    private val CLIENTS = listOf("demo-client", "other-client", "admin-client", "no-scope-client")

    private const val ENV_FILE = ".env"

    private const val TEMPLATE = "deploy/keycloak/shortener-realm.template.json"

    private const val REALM = "deploy/keycloak/shortener-realm.json"

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    private val RANDOM = SecureRandom()

    private val FORBIDDEN_IN_A_VALUE = Regex("['\"\\\\\n\r]")

    /** [text] set in two spaces from the margin, between blank lines, with no trailing spaces on the ones that are blank. */
    private fun indented(text: String): String =
        text.lines().joinToString("\n", prefix = "\n", postfix = "\n") { if (it.isBlank()) "" else "  $it" }

    /** One thing the setup needs to know: the environment variable that can give it, how to ask for it, and its default. */
    private class Setting(val name: String, val label: String, val secret: Boolean, val fallback: () -> String)

    private fun plain(name: String, label: String, fallback: String) = Setting(name, label, false) { fallback }

    private fun password(name: String, label: String) = Setting(name, label, true) { randomSecret() }

    private val SETTINGS = listOf(
        plain("DEV_KEYS_DIR", "Where the client keys go", "deploy/keycloak/dev-keys"),
        plain("DEV_KEYCLOAK_ADMIN_USER", "Keycloak console user", "admin"),
        password("DEV_KEYCLOAK_ADMIN_PASSWORD", "Keycloak console password"),
        password("DEV_USER_ALICE_PASSWORD", "Password of the web user alice"),
        password("DEV_USER_BOB_PASSWORD", "Password of the web user bob"),
        password("DEV_POSTGRES_PASSWORD", "Postgres password (user myuser)"),
        password("DEV_APP_PASSWORD", "Postgres password of the role shortener_app"),
        password("DEV_MIGRATOR_PASSWORD", "Postgres password of the role shortener_migrator"),
        password("DEV_EXPORTER_PASSWORD", "Postgres password of the role shortener_exporter"),
    )

    override fun run(arguments: List<String>, context: Context) {
        var yes = false
        var force = false
        var show = false
        for (argument in arguments) {
            when (argument) {
                "--yes", "-y" -> yes = true
                "--force", "-f" -> force = true
                "--show" -> show = true
                "--help", "-h" -> {
                    context.out.println(usage)
                    return
                }
                else -> throw Usage("unknown option $argument (see --help)")
            }
        }
        if (!Files.exists(context.path(TEMPLATE))) throw Failure("run it from the root of the repository: $TEMPLATE is not here")
        if (show) show(context) else setUp(yes, force, context)
    }

    private fun show(context: Context) {
        val env = readEnv(context)
        if (env.keys.none { it.startsWith("DEV_") }) throw Failure("nothing set up yet; run deploy/keycloak/dev-setup")
        context.out.println("Keycloak console   http://localhost:8180   ${env["DEV_KEYCLOAK_ADMIN_USER"]} / ${env["DEV_KEYCLOAK_ADMIN_PASSWORD"]}")
        context.out.println("web users          alice / ${env["DEV_USER_ALICE_PASSWORD"]}    bob / ${env["DEV_USER_BOB_PASSWORD"]}")
        context.out.println("postgres           myuser / ${env["DEV_POSTGRES_PASSWORD"]}")
        context.out.println("client keys        ${env["DEV_KEYS_DIR"]}")
    }

    private fun setUp(yes: Boolean, force: Boolean, context: Context) {
        val prompt = context.prompt.takeUnless { yes }
        context.out.println(BANNER)

        val env = readEnv(context)
        val exists = Files.exists(context.path(REALM)) || env.keys.any { it.startsWith("DEV_") }
        if (exists && !force) {
            val replace = prompt != null &&
                ask(prompt, "  A setup already exists. Replace its keys, realm and passwords? [y/N]", false).lowercase() in setOf("y", "yes")
            if (!replace) {
                context.err.println("  A setup already exists, so nothing was changed. Use --force to replace it, or --show to see its passwords.")
                return
            }
        }
        if (prompt != null) {
            context.out.println("  Press Enter to accept what is in brackets. Passwords are generated for you; type one to choose your own.")
            context.out.println()
        }

        val values = linkedMapOf<String, String>()
        for (setting in SETTINGS) {
            val given = context.environment[setting.name]
            val fallback = setting.fallback()
            val value = when {
                !given.isNullOrEmpty() -> given
                prompt != null -> ask(prompt, "  ${setting.label} [${if (setting.secret) "a random one" else fallback}]", setting.secret).ifEmpty { fallback }
                else -> fallback
            }
            if (FORBIDDEN_IN_A_VALUE.containsMatchIn(value)) throw Failure("${setting.label} must not contain quotes, backslashes or line breaks")
            values[setting.name] = value
        }

        val keysDirectory = Path.of(values.getValue("DEV_KEYS_DIR"))
        context.out.println()
        context.out.println("  Making the keys (P-256, ES256)")
        Files.createDirectories(context.root.resolve(keysDirectory))
        val publicKeys = linkedMapOf<String, String>()
        // The four keys are made in parallel.
        val pending = CLIENTS.map { it to startKeygen(it, context) }
        for ((client, process) in pending) {
            val pair = finishKeygen(client, process)
            val file = keysDirectory.resolve("$client.jwk.json")
            // World-readable: the load test reads the keys from a container that runs as another user.
            context.writeAtomically(context.root.resolve(file), pair.privateKey + "\n", "rw-r--r--")
            publicKeys[client] = RealmTemplate.publicKey(pair.publicKey, "$client's public key")
            context.out.println("    $file")
        }

        context.out.println("  Making the dev realm from ${Path.of(TEMPLATE).fileName}")
        val passwords = mapOf("alice" to values.getValue("DEV_USER_ALICE_PASSWORD"), "bob" to values.getValue("DEV_USER_BOB_PASSWORD"))
        context.writeAtomically(context.path(REALM), RealmTemplate.fill(context.read(context.path(TEMPLATE)), publicKeys, passwords), "rw-r--r--")
        context.out.println("    $REALM")

        context.out.println("  Writing the passwords to ${context.path(ENV_FILE).toAbsolutePath()}")
        env.putAll(values)
        context.writeAtomically(context.path(ENV_FILE), env.entries.joinToString("") { (name, value) -> "$name='$value'\n" }, "rw-------")

        context.out.println(
            """
              Done. What to do next:
                ./gradlew bootRun                 starts Postgres and Keycloak with these passwords, and the application
                java deploy/keycloak/DpopClient.java call %s/demo-client.jwk.json demo-client \
                    POST http://localhost:8080/api/short-links '{"targetUrl":"https://example.com/"}'
                deploy/keycloak/dev-setup --show  prints the passwords again

              If Postgres or Keycloak ran before with other passwords, remove their data first, because they keep the old
              ones: docker compose down -v
            """.trimIndent().format(values.getValue("DEV_KEYS_DIR")).let(::indented),
        )
    }

    /** A client key as `DpopClient keygen` prints it: line 1 the private JWK, line 2 the public. */
    private class ClientKey(val privateKey: String, val publicKey: String)

    /** Starts `DpopClient keygen` in its own process, from the classpath this one runs with. */
    private fun startKeygen(client: String, context: Context): Process {
        val java = ProcessHandle.current().info().command().orElse("java")
        return ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "DpopClient", "keygen", client)
            .directory(context.root.toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
    }

    private fun finishKeygen(client: String, process: Process): ClientKey {
        val lines = String(process.inputStream.readAllBytes()).lines().filter { it.startsWith("{") }
        try {
            if (process.waitFor() != 0 || lines.size != 2) throw Failure("DpopClient keygen $client did not print a private and a public key")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw Failure("interrupted while making the key of $client")
        }
        return ClientKey(lines[0], lines[1])
    }

    /** The settings of `.env`, in file order. Other lines of the file are not kept. */
    private fun readEnv(context: Context): LinkedHashMap<String, String> {
        val env = LinkedHashMap<String, String>()
        val file = context.path(ENV_FILE)
        if (Files.exists(file)) {
            for (line in Files.readAllLines(file)) {
                val equals = line.indexOf('=')
                if (equals > 0 && !line.startsWith("#")) env[line.substring(0, equals)] = line.substring(equals + 1).replace(Regex("^'|'$"), "")
            }
        }
        return env
    }

    @Throws(IOException::class)
    private fun ask(prompt: Prompt, question: String, secret: Boolean): String =
        ((if (secret) prompt.secret(question) else prompt.line(question)) ?: "").trim()

    private fun randomSecret(): String = (1..24).map { ALPHABET[RANDOM.nextInt(ALPHABET.length)] }.joinToString("")
}
