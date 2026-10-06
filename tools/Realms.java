// Makes a Keycloak realm from a template, by putting the public keys of its clients and, for development, the passwords of
// its users where the template has placeholders. It is the one place that does this: `dev-setup` makes the development
// realm with it and `production` makes the one a deployment imports. Nothing secret goes into a production realm, because
// a public key is all Keycloak needs to trust a client.
//
//   ./gradlew productionRealm -Pdemo=FILE -Padmin=FILE [-Poutput=FILE]     the two public keys, line 2 of what keygen prints
//   java tools/Realms.java render TEMPLATE OUTPUT [--jwks CLIENT=PUBLIC_JWK_FILE]... [--password USER=ENVIRONMENT_VARIABLE]...
//   java tools/Realms.java production DEMO_PUBLIC_JWK_FILE ADMIN_PUBLIC_JWK_FILE [OUTPUT]
//
// `render` replaces `@JWKS:CLIENT@` with a key set made of that one key, and `@PASSWORD:USER@` with the value of the
// environment variable named, so that a password is not on a command line. Every placeholder in the template must be given,
// and every one given must be in the template, so a typo is an error and not a realm that trusts nothing. A file with a
// private key (it has a `d`) is refused: a realm is imported into a server and kept.
//
// `production` is `render` for the production template, run from the root of the repository, with `demo-client` and
// `admin-client`, into `deploy/keycloak/shortener-realm.production.json` unless an output is named. Make the keys with
// `./gradlew keygen -Pclient=NAME`: line 1 is the private key, which the client keeps, and line 2 the public one, which is
// what this takes. The private half of `admin-client` can take down any link, so it stays with the operator.
//
// A compact source file for JDK 25 (it needs the Cli.java and RealmTemplate.java beside it). Exit status: see Cli.run.

private static final String USAGE = """
        usage: java tools/Realms.java render TEMPLATE OUTPUT [--jwks CLIENT=FILE]... [--password USER=VARIABLE]...
               java tools/Realms.java production DEMO_PUBLIC_JWK_FILE ADMIN_PUBLIC_JWK_FILE [OUTPUT]""";

private static final Path PRODUCTION_TEMPLATE = Path.of("deploy/keycloak/shortener-realm.production.template.json");

private static final Path PRODUCTION_OUTPUT = Path.of("deploy/keycloak/shortener-realm.production.json");

void main(String[] args) {
    Cli.run("Realms", USAGE, () -> {
        if (args.length == 0) {
            throw new Cli.Usage("a command is needed");
        }
        switch (args[0]) {
            case "render" -> render(args);
            case "production" -> production(args);
            default -> throw new Cli.Usage("unknown command " + args[0]);
        }
    });
}

private static void production(String[] args) throws IOException {
    if (args.length < 3 || args.length > 4) {
        throw new Cli.Usage("production takes the two public key files and optionally an output");
    }
    var jwks = new LinkedHashMap<String, String>();
    jwks.put("demo-client", publicKey(Path.of(args[1])));
    jwks.put("admin-client", publicKey(Path.of(args[2])));
    write(RealmTemplate.fill(Cli.read(PRODUCTION_TEMPLATE), jwks, Map.of()), args.length == 4 ? Path.of(args[3]) : PRODUCTION_OUTPUT);
}

private static void render(String[] args) throws IOException {
    if (args.length < 3) {
        throw new Cli.Usage("render takes a template and an output");
    }
    var jwks = new LinkedHashMap<String, String>();
    var passwords = new LinkedHashMap<String, String>();
    for (int i = 3; i < args.length; i += 2) {
        if (i + 1 >= args.length || !(args[i].equals("--jwks") || args[i].equals("--password"))) {
            throw new Cli.Usage("expected --jwks or --password with a value, found " + args[i]);
        }
        int equals = args[i + 1].indexOf('=');
        if (equals < 1) {
            throw new Cli.Usage("expected NAME=VALUE, found " + args[i + 1]);
        }
        String name = args[i + 1].substring(0, equals);
        String value = args[i + 1].substring(equals + 1);
        if (args[i].equals("--jwks")) {
            jwks.put(name, publicKey(Path.of(value)));
        } else {
            String password = System.getenv(value);
            if (password == null || password.isEmpty()) {
                throw new Cli.Failure("the environment variable " + value + " for the password of " + name + " is not set");
            }
            passwords.put(name, password);
        }
    }
    write(RealmTemplate.fill(Cli.read(Path.of(args[1])), jwks, passwords), Path.of(args[2]));
}

private static String publicKey(Path file) throws IOException {
    return RealmTemplate.publicKey(Cli.read(file), file.toString());
}

private static void write(String realm, Path output) throws IOException {
    Cli.writeAtomically(output, realm, "rw-r--r--");
    IO.println("wrote " + output);
}
