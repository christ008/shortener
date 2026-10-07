import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A DPoP (RFC 9449) client. It authenticates to the identity provider with a signed assertion (private_key_jwt), gets an
 * access token bound to a fresh key, and calls the API with a proof made for each request. It runs on the machine of whoever
 * calls the API and is not part of the service.
 *
 * <p>Needs a JDK 17 or newer. One source file, run by the {@code java} launcher.
 *
 * <pre>
 *   java DpopClient.java keygen CLIENT_ID                 prints a private and a public JWK for a new client key
 *   java DpopClient.java call KEY_FILE CLIENT_ID METHOD URL [JSON_BODY]
 *   java DpopClient.java token KEY_FILE CLIENT_ID         prints a DPoP-bound access token
 *   java DpopClient.java login USER PASSWORD METHOD URL [JSON_BODY]
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

    private static final String TOKEN_ENDPOINT_PATH = "/protocol/openid-connect/token";
    private static final String DEFAULT_TOKEN_URL = "http://localhost:8180/realms/shortener" + TOKEN_ENDPOINT_PATH;
    private static final String CLIENT_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String USAGE = """
            usage: java DpopClient.java keygen CLIENT_ID
                   java DpopClient.java token KEY_FILE CLIENT_ID
                   java DpopClient.java call KEY_FILE CLIENT_ID METHOD URL [JSON_BODY]
                   java DpopClient.java login USER PASSWORD METHOD URL [JSON_BODY]""";

    private final String tokenUrl = env("TOKEN_URL", DEFAULT_TOKEN_URL);
    private final String issuer = env("ISSUER", tokenUrl.endsWith(TOKEN_ENDPOINT_PATH)
            ? tokenUrl.substring(0, tokenUrl.length() - TOKEN_ENDPOINT_PATH.length())
            : tokenUrl);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    // ---- the command line -------------------------------------------------------------------------------------------

    public static void main(String[] args) {
        if (!validArguments(args)) {
            System.err.println(USAGE);
            System.exit(2);
        }
        try {
            new DpopClient().run(args);
        } catch (Failure failure) {
            System.err.println("DpopClient: " + failure.getMessage());
            if (System.getenv("DPOP_DEBUG") != null) failure.printStackTrace();
            System.exit(1);
        } catch (Exception unexpected) {
            System.err.println("DpopClient: " + unexpected);
            if (System.getenv("DPOP_DEBUG") != null) unexpected.printStackTrace();
            System.exit(1);
        }
    }

    static boolean validArguments(String[] args) {
        return args.length > 0 && switch (args[0]) {
            case "keygen" -> args.length == 2;
            case "token" -> args.length == 3;
            case "call", "login" -> args.length == 5 || args.length == 6;
            default -> false;
        };
    }

    private void run(String[] args) throws Exception {
        switch (args[0]) {
            case "keygen" -> keygen(args[1]);
            case "token" -> System.out.println(fetchToken(Path.of(args[1]), args[2], newKey()).accessToken());
            case "call" -> printCall(Path.of(args[1]), args[2], args[3], args[4], args.length > 5 ? args[5] : null);
            case "login" -> login(args[1], args[2], args[3], args[4], args.length > 5 ? args[5] : null);
            default -> throw new IllegalStateException("unreachable: " + args[0]);
        }
    }

    /** What went wrong, in words for the person running this, without a stack trace. */
    private static class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Failure(String message) {
            super(message);
        }

        Failure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The identity provider answered, and said no. */
    private static final class Refused extends Failure {
        private static final long serialVersionUID = 1L;

        Refused(int status, String body) {
            super("the token endpoint answered " + status + ": " + body);
        }
    }

    // ---- the commands -----------------------------------------------------------------------------------------------

    private static void keygen(String clientId) {
        KeyPair pair = newKey();
        ECPublicKey pub = (ECPublicKey) pair.getPublic();
        Map<String, Object> publicJwk = new LinkedHashMap<>();
        publicJwk.put("kty", "EC");
        publicJwk.put("crv", "P-256");
        publicJwk.put("kid", clientId + "-key-1");
        publicJwk.put("alg", "ES256");
        publicJwk.put("use", "sig");
        publicJwk.put("x", coordinate(pub.getW().getAffineX()));
        publicJwk.put("y", coordinate(pub.getW().getAffineY()));
        Map<String, Object> privateJwk = new LinkedHashMap<>(publicJwk);
        privateJwk.put("d", coordinate(((ECPrivateKey) pair.getPrivate()).getS()));
        System.out.println(Json.write(privateJwk));
        System.out.println(Json.write(publicJwk));
    }

    private void printCall(Path keyFile, String clientId, String method, String url, String body) throws Exception {
        Answer answer = answer(keyFile, clientId, method, url, body);
        System.out.println(answer.status());
        if (answer.location() != null) System.out.println("Location: " + answer.location());
        answer.challenges().forEach(header -> System.out.println("WWW-Authenticate: " + header));
        System.out.println(answer.body());
    }

    // ---- a call to the API -------------------------------------------------------------------------------------------

    /** What the API answered to a call: the status, the {@code Location} if any, the {@code WWW-Authenticate} challenges, the body. */
    private record Answer(int status, String location, List<String> challenges, String body) {}

    private Answer answer(Path keyFile, String clientId, String method, String url, String body) throws Exception {
        KeyPair dpopKey = newKey();
        Token token = fetchToken(keyFile, clientId, dpopKey);
        HttpResponse<String> response = send(apiRequest(method, url, body, token.accessToken(), dpopKey));
        return new Answer(response.statusCode(), response.headers().firstValue("Location").orElse(null),
                response.headers().allValues("WWW-Authenticate"), response.body());
    }

    /**
     * The sign-in of a single-page app, step by step, each printed so that what the identity provider does with the key
     * can be read: the code is bound to the key, the token is bound to it, a refresh keeps it, and the old refresh token
     * is (or is not) refused.
     */
    private void login(String user, String password, String method, String url, String body) throws Exception {
        String clientId = env("CLIENT_ID", "shortener-ui");
        String redirect = env("REDIRECT_URI", "http://localhost:3000/app/auth/callback");
        String scope = env("SCOPE", "shortlinks:create shortlinks:claim shortlinks:read shortlinks:delete");

        KeyPair dpopKey = newKey();
        String jkt = thumbprint(dpopKey);
        String verifier = B64.encodeToString(randomBytes(32));
        String challenge = B64.encodeToString(sha256(verifier));
        String state = UUID.randomUUID().toString();

        String code = signIn(user, password, clientId, redirect, scope, state, challenge, jkt);

        Json tokens = Json.parse(postToken(form("grant_type", "authorization_code", "client_id", clientId, "code", code,
                "redirect_uri", redirect, "code_verifier", verifier), dpopKey));
        String accessToken = tokens.string("access_token");
        String refreshToken = tokens.string("refresh_token");
        if (accessToken == null) throw new Failure("the token endpoint did not return an access_token: " + tokens);
        System.out.println("2. token_type " + tokens.string("token_type") + ", refresh token "
                + (refreshToken == null ? "NOT issued" : "issued"));
        System.out.println("3. access token header " + jwtPart(accessToken, 0));
        System.out.println("   claims " + jwtPart(accessToken, 1));
        System.out.println("   bound to this client's key: " + jkt.equals(confirmationOf(accessToken)));

        String latestAccess = accessToken;
        if (refreshToken != null) {
            Json refreshed = Json.parse(postToken(refreshForm(clientId, refreshToken), dpopKey));
            latestAccess = refreshed.string("access_token");
            if (latestAccess == null) throw new Failure("refreshing did not return an access_token: " + refreshed);
            String rotated = refreshed.string("refresh_token");
            System.out.println("4. refreshed with the same key; new token bound to it: " + jkt.equals(confirmationOf(latestAccess))
                    + ", refresh token rotated: " + (rotated != null && !refreshToken.equals(rotated)));
            System.out.println("   reusing the old refresh token: " + (accepts(refreshForm(clientId, refreshToken), dpopKey)
                    ? "ACCEPTED (rotation is not enforced)" : "refused, as rotation requires"));
            System.out.println("   refreshing with a different key: " + (accepts(refreshForm(clientId, rotated != null ? rotated : refreshToken), newKey())
                    ? "ACCEPTED (the refresh token is not bound to the key)" : "refused"));
        }

        HttpResponse<String> response = send(apiRequest(method, url, body, latestAccess, dpopKey));
        System.out.println("5. " + method + " " + url + " -> " + response.statusCode());
        response.headers().allValues("WWW-Authenticate").forEach(header -> System.out.println("   WWW-Authenticate: " + header));
        System.out.println("   " + response.body());
    }

    // ---- the identity provider --------------------------------------------------------------------------------------

    private record Token(String accessToken, String tokenType) {}

    /** Authenticates the client with a signed assertion, and asks for a token bound to {@code dpopKey}. */
    private Token fetchToken(Path keyFile, String clientId, KeyPair dpopKey) throws Exception {
        PrivateKey clientKey = readPrivateKey(keyFile);
        long now = now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", clientId);
        claims.put("sub", clientId);
        claims.put("aud", issuer);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", now);
        claims.put("exp", now + 60);
        String assertion = jwt(clientKey, Map.of("alg", "ES256"), claims);
        Json answer = Json.parse(postToken(form("grant_type", "client_credentials", "client_id", clientId,
                "client_assertion_type", CLIENT_ASSERTION_TYPE, "client_assertion", assertion), dpopKey));
        String accessToken = answer.string("access_token");
        if (accessToken == null) throw new Failure("the token endpoint did not return an access_token: " + answer);
        return new Token(accessToken, answer.string("token_type"));
    }

    /** Posts a form to the token endpoint with a proof of {@code dpopKey}, and returns the body of the 200 it must answer. */
    private String postToken(String form, KeyPair dpopKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(tokenUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("DPoP", proof(dpopKey, "POST", tokenUrl, null))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw new Refused(response.statusCode(), response.body());
        }
        return response.body();
    }

    private boolean accepts(String form, KeyPair dpopKey) throws Exception {
        try {
            postToken(form, dpopKey);
            return true;
        } catch (Refused refused) {
            return false;
        }
    }

    private static String refreshForm(String clientId, String refreshToken) {
        return form("grant_type", "refresh_token", "client_id", clientId, "refresh_token", refreshToken);
    }

    /** Opens the login page, submits the credentials, and returns the authorization code of the redirect. */
    private String signIn(String user, String password, String clientId, String redirect, String scope, String state,
                          String challenge, String jkt) throws Exception {
        HttpClient browser = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
        Map<String, String> cookies = new LinkedHashMap<>();
        String authUrl = issuer + "/protocol/openid-connect/auth?" + form("response_type", "code", "client_id", clientId,
                "redirect_uri", redirect, "scope", scope, "state", state, "code_challenge", challenge,
                "code_challenge_method", "S256", "dpop_jkt", jkt);

        HttpResponse<String> page = send(browser, HttpRequest.newBuilder(uri(authUrl)).timeout(TIMEOUT).GET().build());
        remember(cookies, page);
        String action = loginFormAction(page.body())
                .orElseThrow(() -> new Failure("no login form (status " + page.statusCode() + "): " + excerpt(page.body(), 500)));

        HttpResponse<String> submitted = send(browser, HttpRequest.newBuilder(uri(action))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", cookieHeader(cookies))
                .POST(HttpRequest.BodyPublishers.ofString(form("username", user, "password", password, "credentialId", "")))
                .build());
        String location = submitted.headers().firstValue("Location").orElseThrow(() -> new Failure(
                "the login did not redirect (status " + submitted.statusCode() + "): " + excerpt(submitted.body(), 300)));

        Map<String, String> returned = queryOf(location);
        if (returned.containsKey("error")) {
            throw new Failure("the login was refused: " + returned.get("error") + " " + returned.getOrDefault("error_description", ""));
        }
        if (!state.equals(returned.get("state"))) throw new Failure("state mismatch");
        String code = returned.get("code");
        if (code == null) throw new Failure("the redirect carries no code: " + location);
        int query = location.indexOf('?');
        System.out.println("1. signed in as " + user + "; redirected to " + (query < 0 ? location : location.substring(0, query)) + " with a code");
        return code;
    }

    private static final Pattern FORM_TAG = Pattern.compile("<form\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION = Pattern.compile("\\baction=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /** The action of the login form of Keycloak, whatever order its attributes come in. */
    static Optional<String> loginFormAction(String html) {
        Matcher forms = FORM_TAG.matcher(html);
        while (forms.find()) {
            String tag = forms.group();
            if (!tag.contains("id=\"kc-form-login\"")) continue;
            Matcher action = ACTION.matcher(tag);
            if (action.find()) return Optional.of(action.group(1).replace("&amp;", "&"));
        }
        return Optional.empty();
    }

    /**
     * A request with the token and a proof made for it. A body is sent as JSON, and without one there is no content type.
     * A trace context goes along when TRACEPARENT is set.
     */
    private HttpRequest apiRequest(String method, String url, String body, String accessToken, KeyPair dpopKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(url))
                .timeout(TIMEOUT)
                .header("Authorization", "DPoP " + accessToken)
                .header("DPoP", proof(dpopKey, method, url, accessToken));
        boolean hasBody = body != null && !body.isEmpty();
        if (hasBody) request.header("Content-Type", "application/json");
        String traceparent = System.getenv("TRACEPARENT");
        if (traceparent != null && !traceparent.isBlank()) request.header("traceparent", traceparent);
        return request.method(method, hasBody ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody()).build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return send(http, request);
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException unreachable) {
            throw new Failure("could not reach " + request.uri() + ": " + unreachable, unreachable);
        }
    }

    // ---- DPoP and JWT -----------------------------------------------------------------------------------------------

    /** The DPoP proof for one request: the method, the URL without its query and fragment, and a hash of the token. */
    static String proof(KeyPair key, String method, String url, String accessToken) {
        URI uri = uri(url);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "ES256");
        header.put("typ", "dpop+jwt");
        header.put("jwk", publicJwk(key));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("htm", method);
        claims.put("htu", uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath());
        claims.put("iat", now());
        if (accessToken != null) claims.put("ath", B64.encodeToString(sha256(accessToken)));
        return jwt(key.getPrivate(), header, claims);
    }

    /** A compact JWS signed with ES256, whose signature is the raw r and s of JWS and not the DER of ECDSA. */
    static String jwt(PrivateKey key, Map<String, ?> header, Map<String, ?> claims) {
        String signingInput = B64.encodeToString(Json.write(header).getBytes(StandardCharsets.UTF_8)) + "."
                + B64.encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + B64.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new Failure("could not sign: " + e, e);
        }
    }

    private static Map<String, Object> publicJwk(KeyPair key) {
        ECPublicKey pub = (ECPublicKey) key.getPublic();
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "EC");
        jwk.put("crv", "P-256");
        jwk.put("x", coordinate(pub.getW().getAffineX()));
        jwk.put("y", coordinate(pub.getW().getAffineY()));
        return jwk;
    }

    /** The RFC 7638 thumbprint of the public key: its required members, in alphabetical order, with no spaces. */
    static String thumbprint(KeyPair key) {
        Map<String, Object> jwk = publicJwk(key);
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("crv", jwk.get("crv"));
        canonical.put("kty", jwk.get("kty"));
        canonical.put("x", jwk.get("x"));
        canonical.put("y", jwk.get("y"));
        return B64.encodeToString(sha256(Json.write(canonical)));
    }

    /** The `jkt` of the `cnf` claim of an access token: the key it is bound to. */
    private static String confirmationOf(String jwt) {
        Object cnf = Json.parse(jwtPart(jwt, 1)).get("cnf");
        return cnf instanceof Map<?, ?> map && map.get("jkt") instanceof String jkt ? jkt : null;
    }

    private static String jwtPart(String jwt, int index) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) throw new Failure("not a JWT: " + excerpt(jwt, 60));
        return new String(B64_DECODER.decode(parts[index]), StandardCharsets.UTF_8);
    }

    // ---- keys -------------------------------------------------------------------------------------------------------

    private static KeyPair newKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new Failure("this JDK cannot make P-256 keys: " + e, e);
        }
    }

    /** The private key of a JWK file. Only `d` is needed to sign, but the key must be a P-256 key to be one for ES256. */
    private static PrivateKey readPrivateKey(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new Failure("could not read the key file " + file + ": " + e.getMessage(), e);
        }
        Json jwk = Json.parse(text);
        if (!"EC".equals(jwk.string("kty")) || !"P-256".equals(jwk.string("crv")) || jwk.string("d") == null) {
            throw new Failure(file + " is not a private P-256 JWK (it needs kty EC, crv P-256 and d)");
        }
        try {
            BigInteger d = new BigInteger(1, B64_DECODER.decode(jwk.string("d")));
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(d, parameters.getParameterSpec(ECParameterSpec.class)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new Failure("could not read the key in " + file + ": " + e.getMessage(), e);
        }
    }

    /** A coordinate or a scalar of P-256 as the 32 bytes JWK wants: BigInteger drops leading zeros and may add a sign byte. */
    static String coordinate(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] fixed = new byte[32];
        int length = Math.min(bytes.length, 32);
        System.arraycopy(bytes, bytes.length - length, fixed, 32 - length, length);
        return B64.encodeToString(fixed);
    }

    // ---- small things -----------------------------------------------------------------------------------------------

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static URI uri(String text) {
        try {
            return URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new Failure("not a URL: " + text, e);
        }
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }

    /** NAME, VALUE, NAME, VALUE...: an application/x-www-form-urlencoded body, or a query. */
    private static String form(String... pairs) {
        List<String> encoded = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            encoded.add(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8) + "=" + URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return String.join("&", encoded);
    }

    private static Map<String, String> queryOf(String location) {
        Map<String, String> parameters = new LinkedHashMap<>();
        String query = uri(location).getRawQuery();
        if (query == null) return parameters;
        for (String pair : query.split("&")) {
            String[] nameAndValue = pair.split("=", 2);
            parameters.put(URLDecoder.decode(nameAndValue[0], StandardCharsets.UTF_8),
                    nameAndValue.length > 1 ? URLDecoder.decode(nameAndValue[1], StandardCharsets.UTF_8) : "");
        }
        return parameters;
    }

    private static void remember(Map<String, String> cookies, HttpResponse<?> response) {
        for (String header : response.headers().allValues("Set-Cookie")) {
            String pair = header.split(";", 2)[0];
            int equals = pair.indexOf('=');
            if (equals > 0) cookies.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
        }
    }

    private static String cookieHeader(Map<String, String> cookies) {
        List<String> pairs = new ArrayList<>();
        cookies.forEach((name, value) -> pairs.add(name + "=" + value));
        return String.join("; ", pairs);
    }

    /** The text of an HTML page or any text, on one line and cut short, for an error message. */
    private static String excerpt(String text, int length) {
        String plain = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return plain.length() <= length ? plain : plain.substring(0, length) + "...";
    }

    // ---- JSON -------------------------------------------------------------------------------------------------------

    /**
     * A strict JSON reader into maps, lists, strings, numbers, booleans and null, and a writer for the same, with no library. A
     * response that is not JSON is an error that names it.
     */
    static final class Json {
        private final Map<String, Object> members;

        private Json(Map<String, Object> members) {
            this.members = members;
        }

        static Json parse(String text) {
            Object value = new Reader(text).readDocument();
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> members = new LinkedHashMap<>();
                map.forEach((name, member) -> members.put((String) name, member));
                return new Json(members);
            }
            throw new Failure("expected a JSON object, got: " + excerpt(text, 200));
        }

        /** A member that is a string, or null when it is missing or of another type. */
        String string(String name) {
            return members.get(name) instanceof String value ? value : null;
        }

        Object get(String name) {
            return members.get(name);
        }

        @Override
        public String toString() {
            return write(members);
        }

        static String write(Object value) {
            StringBuilder out = new StringBuilder();
            write(out, value);
            return out.toString();
        }

        private static void write(StringBuilder out, Object value) {
            if (value == null) {
                out.append("null");
            } else if (value instanceof String text) {
                writeString(out, text);
            } else if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else if (value instanceof Map<?, ?> map) {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    writeString(out, (String) entry.getKey());
                    out.append(':');
                    write(out, entry.getValue());
                }
                out.append('}');
            } else if (value instanceof List<?> list) {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) out.append(',');
                    write(out, list.get(i));
                }
                out.append(']');
            } else {
                throw new IllegalArgumentException("cannot write " + value.getClass());
            }
        }

        private static void writeString(StringBuilder out, String text) {
            out.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) out.append("\\u%04x".formatted((int) c));
                        else out.append(c);
                    }
                }
            }
            out.append('"');
        }

        private static final class Reader {
            private final String text;
            private int position;

            Reader(String text) {
                this.text = text;
            }

            Object readDocument() {
                Object value = readValue();
                skipWhitespace();
                if (position != text.length()) throw error("unexpected text after the value");
                return value;
            }

            private Object readValue() {
                skipWhitespace();
                if (position >= text.length()) throw error("unexpected end");
                char c = text.charAt(position);
                return switch (c) {
                    case '{' -> readObject();
                    case '[' -> readArray();
                    case '"' -> readString();
                    case 't' -> literal("true", Boolean.TRUE);
                    case 'f' -> literal("false", Boolean.FALSE);
                    case 'n' -> literal("null", null);
                    default -> readNumber();
                };
            }

            private Map<String, Object> readObject() {
                Map<String, Object> members = new LinkedHashMap<>();
                position++;
                skipWhitespace();
                if (peek('}')) {
                    position++;
                    return members;
                }
                while (true) {
                    skipWhitespace();
                    if (!peek('"')) throw error("expected a member name");
                    String name = readString();
                    skipWhitespace();
                    expect(':');
                    members.put(name, readValue());
                    skipWhitespace();
                    if (peek(',')) {
                        position++;
                    } else {
                        expect('}');
                        return members;
                    }
                }
            }

            private List<Object> readArray() {
                List<Object> items = new ArrayList<>();
                position++;
                skipWhitespace();
                if (peek(']')) {
                    position++;
                    return items;
                }
                while (true) {
                    items.add(readValue());
                    skipWhitespace();
                    if (peek(',')) {
                        position++;
                    } else {
                        expect(']');
                        return items;
                    }
                }
            }

            private String readString() {
                StringBuilder out = new StringBuilder();
                position++;
                while (position < text.length()) {
                    char c = text.charAt(position++);
                    if (c == '"') return out.toString();
                    if (c < 0x20) throw error("a control character in a string");
                    if (c != '\\') {
                        out.append(c);
                        continue;
                    }
                    if (position >= text.length()) break;
                    char escaped = text.charAt(position++);
                    switch (escaped) {
                        case '"', '\\', '/' -> out.append(escaped);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (position + 4 > text.length()) throw error("a short \\u escape");
                            try {
                                out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                            } catch (NumberFormatException e) {
                                throw error("a bad \\u escape");
                            }
                            position += 4;
                        }
                        default -> throw error("a bad escape \\" + escaped);
                    }
                }
                throw error("an unterminated string");
            }

            private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

            private Object readNumber() {
                Matcher matcher = NUMBER.matcher(text).region(position, text.length());
                if (!matcher.lookingAt()) throw error("unexpected character '" + text.charAt(position) + "'");
                position = matcher.end();
                String number = matcher.group();
                if (matcher.group(2) == null && matcher.group(3) == null) return new BigInteger(number);
                return Double.valueOf(number);
            }

            private Object literal(String word, Object value) {
                if (!text.startsWith(word, position)) throw error("unexpected character '" + text.charAt(position) + "'");
                position += word.length();
                return value;
            }

            private boolean peek(char c) {
                return position < text.length() && text.charAt(position) == c;
            }

            private void expect(char c) {
                if (!peek(c)) throw error("expected '" + c + "'");
                position++;
            }

            private void skipWhitespace() {
                while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++;
            }

            private Failure error(String what) {
                return new Failure("not JSON (" + what + " at " + position + "): " + excerpt(text, 200));
            }
        }
    }
}
