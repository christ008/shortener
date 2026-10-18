package dpop;

import module java.base;
import module java.net.http;

/**
 * A reference implementation of a DPoP (RFC 9449) client, to read, to try the API with, and to port. It authenticates to the
 * identity provider with a signed assertion (private_key_jwt), gets an access token bound to a fresh key, and calls the API with
 * a proof made for each request. It answers a server's request for a nonce ({@code use_dpop_nonce}) by repeating the request
 * once with the nonce from {@code DPoP-Nonce}, and keeps the latest nonce of each server for the requests that follow. It runs on
 * the machine of whoever calls the API and is not part of the service.
 *
 * <p>It is not a library. A program that calls the API should use a maintained DPoP library for its language
 * (docs/OPERATING.md lists some) and do what this does: one key for each token, a proof for each request, the nonce kept and
 * repeated.
 *
 * <p>Needs a JDK 25 or newer. Several source files in this directory, run by the {@code java} launcher, which compiles the ones it uses.
 *
 * <pre>
 *   java deploy/keycloak/dpop/DpopClient.java keygen CLIENT_ID                 prints a private and a public JWK for a new client key
 *   java deploy/keycloak/dpop/DpopClient.java register NAME INVITATION KEY_FILE
 *                                                         makes a key, writes its private half to KEY_FILE (which must not
 *                                                         exist), registers a client with the public half using the
 *                                                         invitation an administrator gave, and prints the client id
 *   java deploy/keycloak/dpop/DpopClient.java call KEY_FILE CLIENT_ID METHOD URL [JSON_BODY]
 *   java deploy/keycloak/dpop/DpopClient.java token KEY_FILE CLIENT_ID         prints a DPoP-bound access token
 *   java deploy/keycloak/dpop/DpopClient.java login USER PASSWORD METHOD URL [JSON_BODY]
 *                                                         signs a user in the way a single-page app does (authorization
 *                                                         code with PKCE and a DPoP-bound code), shows the token's claims,
 *                                                         refreshes it, then calls the API as that user
 * </pre>
 *
 * KEY_FILE is a private JWK (P-256) with the fields {@code x}, {@code y} and {@code d}, as {@code deploy/keycloak/dev-setup}
 * writes them.
 *
 * <p>Settings, all optional, from the environment: TOKEN_URL, the token endpoint (by default the local realm); ISSUER (by
 * default TOKEN_URL without {@code /protocol/openid-connect/token}); TRACEPARENT, sent as the W3C trace context of a call;
 * DPOP_DEBUG, which prints a stack trace with an error. For login: CLIENT_ID (shortener-ui), REDIRECT_URI and SCOPE. A server
 * certificate of your own is trusted as for any Java program, for example with
 * {@code java -Djavax.net.ssl.trustStore=ts.p12 DpopClient.java ...} or JAVA_TOOL_OPTIONS.
 *
 * <p>Exit status: 0 when it did what was asked (an error status from the API is the answer), 1 when it could not, with the
 * reason on stderr, 2 for arguments it does not understand.
 */
public final class DpopClient {
    private static final String ERROR_PREFIX = "DpopClient: ";

    private static final String DEBUG_VARIABLE = "DPOP_DEBUG";

    private static final String USAGE = """
            usage: java deploy/keycloak/dpop/DpopClient.java keygen CLIENT_ID
                   java deploy/keycloak/dpop/DpopClient.java register NAME INVITATION KEY_FILE
                   java deploy/keycloak/dpop/DpopClient.java token KEY_FILE CLIENT_ID
                   java deploy/keycloak/dpop/DpopClient.java call KEY_FILE CLIENT_ID METHOD URL [JSON_BODY]
                   java deploy/keycloak/dpop/DpopClient.java login USER PASSWORD METHOD URL [JSON_BODY]""";

    private final String tokenUrl = Http.env("TOKEN_URL", Oauth.DEFAULT_TOKEN_URL);

    private final String issuer = Http.env("ISSUER", tokenUrl.endsWith(Oauth.TOKEN_ENDPOINT_PATH)
            ? tokenUrl.substring(0, tokenUrl.length() - Oauth.TOKEN_ENDPOINT_PATH.length())
            : tokenUrl);
    private final TokenEndpoint endpoint = new TokenEndpoint(tokenUrl, issuer);

    private final Console console;

    private DpopClient(Console console) {
        this.console = console;
    }

    // ---- the command line -------------------------------------------------------------------------------------------
    public static void main(String[] args) {
        Console console = new SystemConsole();
        Optional<Command> command = parse(args);
        if (command.isEmpty()) {
            console.err(USAGE);
            System.exit(2);
        }
        try {
            new DpopClient(console).run(command.get());
        } catch (Failure failure) {
            console.err(ERROR_PREFIX + failure.getMessage());
            if (System.getenv(DEBUG_VARIABLE) != null) console.trace(failure);
            System.exit(1);
        } catch (Exception unexpected) {
            if (unexpected instanceof InterruptedException) Thread.currentThread().interrupt();
            console.err(ERROR_PREFIX + unexpected);
            if (System.getenv(DEBUG_VARIABLE) != null) console.trace(unexpected);
            System.exit(1);
        }
    }

    /** One command of the line, with the arguments it takes. */
    private sealed interface Command permits Keygen, Register, FetchToken, Call, Login {}

    private record Keygen(String clientId) implements Command {}

    private record Register(String name, String invitation, Path keyFile) implements Command {}

    private record FetchToken(Path keyFile, String clientId) implements Command {}

    private record Call(Path keyFile, String clientId, String method, String url, String body) implements Command {}

    private record Login(String user, String password, String method, String url, String body) implements Command {}

    /** The command the arguments make, or nothing when they make none. */
    static Optional<Command> parse(String[] args) {
        int count = args.length;
        String word = count == 0 ? "" : args[0];
        switch (word) {
            case "keygen":
                return count == 2 ? Optional.of(new Keygen(args[1])) : Optional.empty();
            case "register":
                return count == 4 ? Optional.of(new Register(args[1], args[2], Path.of(args[3]))) : Optional.empty();
            case "token":
                return count == 3 ? Optional.of(new FetchToken(Path.of(args[1]), args[2])) : Optional.empty();
            case "call":
                return count == 5 || count == 6
                        ? Optional.of(new Call(Path.of(args[1]), args[2], args[3], args[4], argumentAt(args, 5)))
                        : Optional.empty();
            case "login":
                return count == 5 || count == 6
                        ? Optional.of(new Login(args[1], args[2], args[3], args[4], argumentAt(args, 5)))
                        : Optional.empty();
            default:
                return Optional.empty();
        }
    }

    /** The argument at index, or nothing when the line has no such argument: the body of a call is optional. */
    private static String argumentAt(String[] args, int index) {
        return args.length > index ? args[index] : null;
    }

    private void run(Command command) throws IOException, InterruptedException {
        if (command instanceof Keygen keygen) {
            keygen(keygen.clientId());
        } else if (command instanceof Register register) {
            register(register.name(), register.invitation(), register.keyFile());
        } else if (command instanceof FetchToken fetch) {
            console.out(endpoint.fetchToken(fetch.keyFile(), fetch.clientId(), Keys.newKey()).accessToken());
        } else if (command instanceof Call call) {
            printCall(call.keyFile(), call.clientId(), call.method(), call.url(), call.body());
        } else if (command instanceof Login login) {
            login(login.user(), login.password(), login.method(), login.url(), login.body());
        } else {
            throw new IllegalStateException("unreachable: " + command);
        }
    }

    // ---- the commands -----------------------------------------------------------------------------------------------
    private void keygen(String clientId) {
        List<Map<String, Object>> jwks = Keys.newJwks(clientId);
        console.out(Json.write(jwks.get(0)));
        console.out(Json.write(jwks.get(1)));
    }

    /**
     * Registers a client with the invitation an administrator made (an initial access token of the realm: one use, with a
     * deadline). The key is made here and its private half never leaves the file, which is made readable by its owner alone and
     * is written before the request, so that a registration that succeeds cannot be left without its key. The identity provider
     * chooses the client id, the scopes and the owner: only the name and the public key are sent.
     */
    private void register(String name, String invitation, Path keyFile) throws IOException, InterruptedException {
        if (Files.exists(keyFile)) throw new Failure(keyFile + " exists already: it is not overwritten, name another file");
        List<Map<String, Object>> jwks = Keys.newJwks(name);
        try {
            Files.writeString(keyFile, Json.write(jwks.get(0)) + "\n", StandardOpenOption.CREATE_NEW);
            Keys.restrictToOwner(keyFile);
        } catch (IOException e) {
            throw new Failure("could not write the key file " + keyFile + ": " + e.getMessage(), e);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("client_name", name);
        metadata.put("token_endpoint_auth_method", "private_key_jwt");
        metadata.put("token_endpoint_auth_signing_alg", Jose.ES256);
        metadata.put("grant_types", List.of("client_credentials"));
        metadata.put("jwks", Map.of("keys", List.of(jwks.get(1))));
        String registration = issuer + "/clients-registrations/openid-connect";
        HttpResponse<String> response = endpoint.send(HttpRequest.newBuilder(Http.uri(registration)).timeout(Http.TIMEOUT)
                .header("Authorization", "Bearer " + invitation).header(Http.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(metadata))).build());
        if (response.statusCode() != 201) {
            try {
                Files.deleteIfExists(keyFile);
            } catch (IOException _) {
                // the registration failed and so the key means nothing; a leftover file is harmless
            }
            throw new Failure("the registration was refused (" + response.statusCode() + "): " + Http.excerpt(response.body(), 300));
        }
        String clientId = Json.parse(response.body()).string(Oauth.CLIENT_ID);
        if (clientId == null) throw new Failure("the identity provider registered the client but did not say its id: " + Http.excerpt(response.body(), 300));
        console.out("registered: " + clientId);
        console.out("private key: " + keyFile + " (keep it: it is the only way to be this client, and it cannot be recovered)");
        console.out("try it:  java deploy/keycloak/dpop/DpopClient.java call " + keyFile + " " + clientId + " GET <API URL>/api/short-links");
    }

    private void printCall(Path keyFile, String clientId, String method, String url, String body) throws InterruptedException {
        Answer answer = answer(keyFile, clientId, method, url, body);
        console.out(String.valueOf(answer.status()));
        if (answer.location() != null) console.out("Location: " + answer.location());
        answer.challenges().forEach(header -> console.out("WWW-Authenticate: " + header));
        console.out(answer.body());
    }

    // ---- a call to the API -------------------------------------------------------------------------------------------
    /** What the API answered to a call: the status, the {@code Location} if any, the {@code WWW-Authenticate} challenges, the body. */
    private record Answer(int status, String location, List<String> challenges, String body) {}

    private Answer answer(Path keyFile, String clientId, String method, String url, String body) throws InterruptedException {
        KeyPair dpopKey = Keys.newKey();
        TokenEndpoint.Token token = endpoint.fetchToken(keyFile, clientId, dpopKey);
        HttpResponse<String> response = endpoint.sendProved(url, nonce -> endpoint.apiRequest(method, url, body, token.accessToken(), dpopKey, nonce));
        return new Answer(response.statusCode(), response.headers().firstValue("Location").orElse(null),
                response.headers().allValues(Http.WWW_AUTHENTICATE), response.body());
    }

    /**
     * The sign-in of a single-page app, step by step, each printed so that what the identity provider does with the key
     * can be read: the code is bound to the key, the token is bound to it, a refresh keeps it, and the old refresh token
     * is (or is not) refused.
     */
    private void login(String user, String password, String method, String url, String body) throws InterruptedException {
        String clientId = Http.env("CLIENT_ID", "shortener-ui");
        String redirect = Http.env("REDIRECT_URI", "http://localhost:3000/app/auth/callback");
        String scope = Http.env("SCOPE", "shortlinks:create shortlinks:claim shortlinks:read shortlinks:delete");

        KeyPair dpopKey = Keys.newKey();
        String jkt = Keys.thumbprint(dpopKey);
        String verifier = Jose.B64.encodeToString(Jose.randomBytes(32));
        String challenge = Jose.B64.encodeToString(Jose.sha256(verifier));
        String state = UUID.randomUUID().toString();

        String code = new KeycloakLogin(issuer, console).signIn(user, password,
                new KeycloakLogin.AuthorizationRequest(clientId, redirect, scope, state, challenge, jkt));

        Json tokens = Json.parse(endpoint.postToken(Http.form(Oauth.GRANT_TYPE, "authorization_code", Oauth.CLIENT_ID, clientId, "code", code,
                "redirect_uri", redirect, "code_verifier", verifier), dpopKey));
        String accessToken = tokens.string(Oauth.ACCESS_TOKEN);
        String refreshToken = tokens.string(Oauth.REFRESH_TOKEN);
        if (accessToken == null) throw new Failure("the token endpoint did not return an access_token: " + tokens);
        console.out("2. token_type " + tokens.string("token_type") + ", refresh token "
                + (refreshToken == null ? "NOT issued" : "issued"));
        console.out("3. access token header " + Jose.jwtPart(accessToken, 0));
        console.out("   claims " + Jose.jwtPart(accessToken, 1));
        console.out("   bound to this client's key: " + jkt.equals(Jose.confirmationOf(accessToken)));

        String latestAccess = accessToken;
        if (refreshToken != null) {
            Json refreshed = Json.parse(endpoint.postToken(TokenEndpoint.refreshForm(clientId, refreshToken), dpopKey));
            latestAccess = refreshed.string(Oauth.ACCESS_TOKEN);
            if (latestAccess == null) throw new Failure("refreshing did not return an access_token: " + refreshed);
            String rotated = refreshed.string(Oauth.REFRESH_TOKEN);
            console.out("4. refreshed with the same key; new token bound to it: " + jkt.equals(Jose.confirmationOf(latestAccess))
                    + ", refresh token rotated: " + (rotated != null && !refreshToken.equals(rotated)));
            console.out("   reusing the old refresh token: " + (endpoint.accepts(TokenEndpoint.refreshForm(clientId, refreshToken), dpopKey)
                    ? "ACCEPTED (rotation is not enforced)" : "refused, as rotation requires"));
            String refreshWith = rotated != null ? rotated : refreshToken;
            console.out("   refreshing with a different key: " + (endpoint.accepts(TokenEndpoint.refreshForm(clientId, refreshWith), Keys.newKey())
                    ? "ACCEPTED (the refresh token is not bound to the key)" : "refused"));
        }

        String accessToUse = latestAccess;
        HttpResponse<String> response = endpoint.sendProved(url, nonce -> endpoint.apiRequest(method, url, body, accessToUse, dpopKey, nonce));
        console.out("5. " + method + " " + url + " -> " + response.statusCode());
        response.headers().allValues(Http.WWW_AUTHENTICATE).forEach(header -> console.out("   WWW-Authenticate: " + header));
        console.out("   " + response.body());
    }
}
