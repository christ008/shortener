// Makes the cryptographic material and the passwords a local development setup needs, so none of it is committed.
//
//   deploy/keycloak/dev-setup              asks for each setting, offering a random value
//   deploy/keycloak/dev-setup --yes        takes every default, and asks nothing (for CI and scripts)
//   deploy/keycloak/dev-setup --force      replaces what is already there
//   deploy/keycloak/dev-setup --show       prints the passwords of an existing setup
//   ./gradlew devSetup                     the same as --yes, with the right JDK; -Pforce adds --force
//
// It writes, all of it ignored by Git:
//   deploy/keycloak/dev-keys/<client>.jwk.json   a private key for each of the four dev clients (P-256, made by DpopClient.java)
//   deploy/keycloak/shortener-realm.json         the dev realm, made from shortener-realm.template.json with their public keys
//   .env                                         the passwords compose.yaml reads, merged into what is already there
//
// Each setting can also be given in the environment, which is how a script answers: DEV_KEYS_DIR, DEV_KEYCLOAK_ADMIN_USER,
// DEV_KEYCLOAK_ADMIN_PASSWORD, DEV_POSTGRES_PASSWORD, DEV_APP_PASSWORD, DEV_MIGRATOR_PASSWORD, DEV_EXPORTER_PASSWORD,
// DEV_USER_ALICE_PASSWORD and DEV_USER_BOB_PASSWORD. It is asked on a terminal and takes the defaults anywhere else.
//
// A compact source file for JDK 25 (it needs Cli.java and RealmTemplate.java beside it), run from the root of the repository
// by the script deploy/keycloak/dev-setup, which picks the JDK. Exit status: see Cli.run.

private static final String USAGE = "usage: deploy/keycloak/dev-setup [--yes] [--force] [--show]";

private static final String BANNER = """

           ____  _                _
          / ___|| |__   ___  _ __| |_ ___ _ __   ___ _ __
          \\___ \\| '_ \\ / _ \\| '__| __/ _ \\ '_ \\ / _ \\ '__|
           ___) | | | | (_) | |  | ||  __/ | | |  __/ |
          |____/|_| |_|\\___/|_|   \\__\\___|_| |_|\\___|_|

          development setup: throwaway keys and passwords

          ----------------------------------------------------------------------------
           FOR LOCAL DEVELOPMENT ONLY. Never reuse any of this in a deployment: a real
           one has its own identity provider, its own keys and its own secrets.
          ----------------------------------------------------------------------------

          This makes, and writes where Git ignores them:
            - a private key for each dev client (demo, other, admin, no-scope)
            - the dev realm for Keycloak, trusting those keys and these passwords
            - the passwords of Keycloak, its two web users and Postgres, in .env
        """;

private static final List<String> CLIENTS = List.of("demo-client", "other-client", "admin-client", "no-scope-client");

private static final Path ENV_FILE = Path.of(".env");

private static final Path TEMPLATE = Path.of("deploy/keycloak/shortener-realm.template.json");

private static final Path REALM = Path.of("deploy/keycloak/shortener-realm.json");

private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

private static final SecureRandom RANDOM = new SecureRandom();

/** One thing the setup needs to know: the environment variable that can give it, how to ask for it, and its default. */
private record Setting(String name, String label, Supplier<String> fallback, boolean secret) {
    static Setting plain(String name, String label, String fallback) {
        return new Setting(name, label, () -> fallback, false);
    }

    static Setting password(String name, String label) {
        return new Setting(name, label, () -> randomSecret(), true);
    }
}

private static final List<Setting> SETTINGS = List.of(
        Setting.plain("DEV_KEYS_DIR", "Where the client keys go", "deploy/keycloak/dev-keys"),
        Setting.plain("DEV_KEYCLOAK_ADMIN_USER", "Keycloak console user", "admin"),
        Setting.password("DEV_KEYCLOAK_ADMIN_PASSWORD", "Keycloak console password"),
        Setting.password("DEV_USER_ALICE_PASSWORD", "Password of the web user alice"),
        Setting.password("DEV_USER_BOB_PASSWORD", "Password of the web user bob"),
        Setting.password("DEV_POSTGRES_PASSWORD", "Postgres password (user myuser)"),
        Setting.password("DEV_APP_PASSWORD", "Postgres password of the role shortener_app"),
        Setting.password("DEV_MIGRATOR_PASSWORD", "Postgres password of the role shortener_migrator"),
        Setting.password("DEV_EXPORTER_PASSWORD", "Postgres password of the role shortener_exporter"));

void main(String[] args) {
    Cli.run("dev-setup", USAGE, () -> {
        boolean yes = false;
        boolean force = false;
        boolean show = false;
        for (String argument : args) {
            switch (argument) {
                case "--yes", "-y" -> yes = true;
                case "--force", "-f" -> force = true;
                case "--show" -> show = true;
                case "--help", "-h" -> {
                    IO.println(USAGE);
                    return;
                }
                default -> throw new Cli.Usage("unknown option " + argument + " (see --help)");
            }
        }
        if (!Files.exists(TEMPLATE)) {
            throw new Cli.Failure("run it from the root of the repository: " + TEMPLATE + " is not here");
        }
        if (show) {
            show();
        } else {
            setUp(yes, force);
        }
    });
}

private static void show() throws IOException {
    var env = readEnv();
    if (env.keySet().stream().noneMatch(name -> name.startsWith("DEV_"))) {
        throw new Cli.Failure("nothing set up yet; run deploy/keycloak/dev-setup");
    }
    IO.println("Keycloak console   http://localhost:8180   %s / %s".formatted(env.get("DEV_KEYCLOAK_ADMIN_USER"), env.get("DEV_KEYCLOAK_ADMIN_PASSWORD")));
    IO.println("web users          alice / %s    bob / %s".formatted(env.get("DEV_USER_ALICE_PASSWORD"), env.get("DEV_USER_BOB_PASSWORD")));
    IO.println("postgres           myuser / " + env.get("DEV_POSTGRES_PASSWORD"));
    IO.println("client keys        " + env.get("DEV_KEYS_DIR"));
}

private static void setUp(boolean yes, boolean force) throws IOException {
    var console = System.console();
    boolean interactive = !yes && console != null && console.isTerminal();
    IO.println(BANNER);

    var env = readEnv();
    boolean exists = Files.exists(REALM) || env.keySet().stream().anyMatch(name -> name.startsWith("DEV_"));
    if (exists && !force) {
        boolean replace = interactive && Set.of("y", "yes").contains(ask(console, "  A setup already exists. Replace its keys, realm and passwords? [y/N]", false).toLowerCase());
        if (!replace) {
            System.err.println("  A setup already exists, so nothing was changed. Use --force to replace it, or --show to see its passwords.");
            return;
        }
    }
    if (interactive) {
        IO.println("  Press Enter to accept what is in brackets. Passwords are generated for you; type one to choose your own.");
        IO.println();
    }

    var values = new LinkedHashMap<String, String>();
    for (var setting : SETTINGS) {
        String given = System.getenv(setting.name());
        String fallback = setting.fallback().get();
        String value = given != null && !given.isEmpty() ? given
                : interactive ? orDefault(ask(console, "  %s [%s]".formatted(setting.label(), setting.secret() ? "a random one" : fallback), setting.secret()), fallback)
                : fallback;
        if (value.matches("(?s).*['\"\\\\\\n\\r].*")) {
            throw new Cli.Failure(setting.label() + " must not contain quotes, backslashes or line breaks");
        }
        values.put(setting.name(), value);
    }

    Path keysDirectory = Path.of(values.get("DEV_KEYS_DIR"));
    IO.println();
    IO.println("  Making the keys (P-256, ES256)");
    Files.createDirectories(keysDirectory);
    var publicKeys = new LinkedHashMap<String, String>();
    for (String client : CLIENTS) {
        var pair = keygen(client);
        Path file = keysDirectory.resolve(client + ".jwk.json");
        // The load test reads the keys from a container that runs as another user, and these are throwaway keys.
        Cli.writeAtomically(file, pair.privateKey() + "\n", "rw-r--r--");
        publicKeys.put(client, RealmTemplate.publicKey(pair.publicKey(), client + "'s public key"));
        IO.println("    " + file);
    }

    IO.println("  Making the dev realm from " + TEMPLATE.getFileName());
    var passwords = Map.of("alice", values.get("DEV_USER_ALICE_PASSWORD"), "bob", values.get("DEV_USER_BOB_PASSWORD"));
    Cli.writeAtomically(REALM, RealmTemplate.fill(Cli.read(TEMPLATE), publicKeys, passwords), "rw-r--r--");
    IO.println("    " + REALM);

    IO.println("  Writing the passwords to " + ENV_FILE.toAbsolutePath());
    env.putAll(values);
    Cli.writeAtomically(ENV_FILE, env.entrySet().stream().map(entry -> "%s='%s'%n".formatted(entry.getKey(), entry.getValue())).collect(Collectors.joining()), "rw-------");

    IO.println("""

              Done. What to do next:
                ./gradlew bootRun                 starts Postgres and Keycloak with these passwords, and the application
                java deploy/keycloak/DpopClient.java call %s/demo-client.jwk.json demo-client \\
                    POST http://localhost:8080/api/short-links '{"targetUrl":"https://example.com/"}'
                deploy/keycloak/dev-setup --show  prints the passwords again

              If Postgres or Keycloak ran before with other passwords, remove their data first, because they keep the old
              ones: docker compose down -v
            """.formatted(values.get("DEV_KEYS_DIR")));
}

/** A new client key as DpopClient.java makes it, the one place that knows how: line 1 the private JWK, line 2 the public. */
private record ClientKey(String privateKey, String publicKey) {
}

private static ClientKey keygen(String client) throws IOException {
    String java = ProcessHandle.current().info().command().orElse("java");
    var process = new ProcessBuilder(java, "deploy/keycloak/DpopClient.java", "keygen", client)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    var lines = new String(process.getInputStream().readAllBytes()).lines().filter(line -> line.startsWith("{")).toList();
    try {
        if (process.waitFor() != 0 || lines.size() != 2) {
            throw new Cli.Failure("DpopClient.java keygen " + client + " did not print a private and a public key");
        }
    } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new Cli.Failure("interrupted while making the key of " + client);
    }
    return new ClientKey(lines.get(0), lines.get(1));
}

/** The settings of `.env` in the order they are in the file, other lines of the file being ones it does not keep. */
private static Map<String, String> readEnv() throws IOException {
    var env = new LinkedHashMap<String, String>();
    if (Files.exists(ENV_FILE)) {
        for (String line : Files.readAllLines(ENV_FILE)) {
            int equals = line.indexOf('=');
            if (equals > 0 && !line.startsWith("#")) {
                env.put(line.substring(0, equals), line.substring(equals + 1).replaceAll("^'|'$", ""));
            }
        }
    }
    return env;
}

private static String ask(Console console, String question, boolean secret) {
    String answer = secret ? Optional.ofNullable(console.readPassword("%s: ", question)).map(String::new).orElse("")
            : Optional.ofNullable(console.readLine("%s: ", question)).orElse("");
    return answer.strip();
}

private static String orDefault(String value, String fallback) {
    return value.isEmpty() ? fallback : value;
}

private static String randomSecret() {
    return RANDOM.ints(24, 0, ALPHABET.length()).mapToObj(i -> String.valueOf(ALPHABET.charAt(i))).collect(Collectors.joining());
}
