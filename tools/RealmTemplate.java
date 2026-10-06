import module java.base;

/**
 * A realm template and what goes into it: the public key of each client where the template has `@JWKS:CLIENT@`, and the
 * password of each user where it has `@PASSWORD:USER@`. Shared by `Realms`, which makes a realm from files, and `DevSetup`,
 * which makes one from keys it has just made.
 *
 * - Every placeholder in the template must be given and every one given must be in the template, so that a typo is an error and
 *   not a realm that trusts nothing.
 * - A key with a private part (a `d`) is refused: a realm is imported into a server and kept.
 * - Values are put inside JSON strings, so a quote or a backslash in a password stays part of the password.
 */
final class RealmTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("@(JWKS|PASSWORD):([A-Za-z0-9._-]+)@");

    private static final Pattern PRIVATE_KEY_MEMBER = Pattern.compile("\"d\"\\s*:");

    private RealmTemplate() {
    }

    static String fill(String template, Map<String, String> jwks, Map<String, String> passwords) {
        var values = new LinkedHashMap<String, String>();
        jwks.forEach((client, key) -> values.put("@JWKS:" + client + "@", Cli.jsonString("{\"keys\":[" + key + "]}")));
        passwords.forEach((user, password) -> values.put("@PASSWORD:" + user + "@", Cli.jsonString(password)));

        var unused = values.keySet().stream().filter(placeholder -> !template.contains(placeholder)).toList();
        if (!unused.isEmpty()) {
            throw new Cli.Failure("the template has no placeholder " + String.join(", ", unused));
        }
        String realm = template;
        for (var entry : values.entrySet()) {
            realm = realm.replace(entry.getKey(), entry.getValue());
        }
        var missing = PLACEHOLDER.matcher(realm).results().map(MatchResult::group).distinct().toList();
        if (!missing.isEmpty()) {
            throw new Cli.Failure("no value was given for " + String.join(", ", missing));
        }
        return realm;
    }

    /** The public key in [json], which [where] names for the message, or a failure if it is not a public key. */
    static String publicKey(String json, String where) {
        String key = json.strip();
        if (!key.startsWith("{") || !key.endsWith("}")) {
            throw new Cli.Failure(where + " is not a JSON object: give the public key, line 2 of what keygen prints");
        }
        if (PRIVATE_KEY_MEMBER.matcher(key).find()) {
            throw new Cli.Failure(where + " holds a private key (it has a \"d\"): give the public half, line 2 of what keygen prints");
        }
        return key;
    }
}
