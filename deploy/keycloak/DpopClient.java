import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A client for the local setup that authenticates to Keycloak with a signed assertion instead of a secret
 * (private_key_jwt), gets an access token bound to a fresh key, and calls the API with a DPoP proof made for
 * each request. Needs only the JDK.
 *
 *   java DpopClient.java keygen CLIENT_ID                 prints a private and a public JWK for a new client key
 *   java DpopClient.java call KEY_FILE CLIENT_ID METHOD URL [JSON_BODY]
 *   java DpopClient.java token KEY_FILE CLIENT_ID         prints a DPoP-bound access token and the key it is bound to
 *   java DpopClient.java login USER PASSWORD METHOD URL [JSON_BODY]
 *                                                         signs a user in the way a single-page app does (authorization
 *                                                         code with PKCE and a DPoP-bound code), shows the token's claims,
 *                                                         refreshes it, then calls the API as that user
 *
 * The token endpoint comes from TOKEN_URL, by default the local realm. TRACEPARENT, if set, is sent as the W3C trace
 * context of the request, so a sampled trace can be forced for a call. For login, CLIENT_ID (shortener-ui), REDIRECT_URI
 * and SCOPE can be overridden.
 */
public class DpopClient {

    static final String TOKEN_URL = System.getenv().getOrDefault("TOKEN_URL", "http://localhost:8180/realms/shortener/protocol/openid-connect/token");
    static final String ISSUER = System.getenv().getOrDefault("ISSUER", TOKEN_URL.replaceAll("/protocol/openid-connect/token$", ""));
    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final String USAGE = """
            usage: java DpopClient.java keygen CLIENT_ID
                   java DpopClient.java token KEY_FILE CLIENT_ID
                   java DpopClient.java call KEY_FILE CLIENT_ID METHOD URL [JSON_BODY]
                   java DpopClient.java login USER PASSWORD METHOD URL [JSON_BODY]""";

    public static void main(String[] args) throws Exception {
        if (!validArguments(args)) {
            System.err.println(USAGE);
            System.exit(2);
        }
        switch (args[0]) {
            case "keygen" -> keygen(args[1]);
            case "token" -> {
                KeyPair dpopKey = generate();
                Token token = fetchToken(Path.of(args[1]), args[2], dpopKey);
                System.out.println(token.accessToken);
            }
            case "call" -> {
                KeyPair dpopKey = generate();
                Token token = fetchToken(Path.of(args[1]), args[2], dpopKey);
                String method = args[3];
                String url = args[4];
                String body = args.length > 5 ? args[5] : null;
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(TIMEOUT)
                        .header("Authorization", "DPoP " + token.accessToken)
                        .header("DPoP", proof(dpopKey, method, url, token.accessToken));
                if (body != null) request.header("Content-Type", "application/json");
                String traceparent = System.getenv("TRACEPARENT");
                if (traceparent != null && !traceparent.isBlank()) request.header("traceparent", traceparent);
                request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
                HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
                System.out.println(response.statusCode());
                response.headers().firstValue("Location").ifPresent(l -> System.out.println("Location: " + l));
                response.headers().allValues("WWW-Authenticate").forEach(h -> System.out.println("WWW-Authenticate: " + h));
                System.out.println(response.body());
            }
            case "login" -> login(args);
            default -> throw new IllegalStateException("unreachable: " + args[0]);
        }
    }

    static boolean validArguments(String[] args) {
        return args.length > 0 && switch (args[0]) {
            case "keygen" -> args.length == 2;
            case "token" -> args.length == 3;
            case "call" -> args.length == 5 || args.length == 6;
            case "login" -> args.length == 5 || args.length == 6;
            default -> false;
        };
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static void login(String[] args) throws Exception {
        String user = args[1], password = args[2], method = args[3], url = args[4];
        String body = args.length > 5 ? args[5] : null;
        String clientId = env("CLIENT_ID", "shortener-ui");
        String redirect = env("REDIRECT_URI", "http://localhost:3000/app/auth/callback");
        String scope = env("SCOPE", "shortlinks:create shortlinks:claim shortlinks:read shortlinks:delete");

        KeyPair dpopKey = generate();
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String verifier = B64.encodeToString(random);
        String challenge = B64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = UUID.randomUUID().toString();

        HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        Map<String, String> jar = new LinkedHashMap<>();
        String authUrl = ISSUER + "/protocol/openid-connect/auth?response_type=code&client_id=" + enc(clientId) + "&redirect_uri=" + enc(redirect)
                + "&scope=" + enc(scope) + "&state=" + state + "&code_challenge=" + challenge + "&code_challenge_method=S256&dpop_jkt=" + thumbprint(dpopKey);

        HttpResponse<String> page = browser.send(HttpRequest.newBuilder(URI.create(authUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
        remember(jar, page);
        Matcher form = Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"").matcher(page.body());
        if (!form.find()) throw new IllegalStateException("no login form (status " + page.statusCode() + "): " + page.body().substring(0, Math.min(500, page.body().length())));
        String action = form.group(1).replace("&amp;", "&");

        HttpResponse<String> submitted = browser.send(HttpRequest.newBuilder(URI.create(action))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", cookieHeader(jar))
                .POST(HttpRequest.BodyPublishers.ofString("username=" + enc(user) + "&password=" + enc(password) + "&credentialId=")).build(), HttpResponse.BodyHandlers.ofString());
        String location = submitted.headers().firstValue("Location").orElseThrow(() -> new IllegalStateException("the login did not redirect (status " + submitted.statusCode() + "): " + submitted.body().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim().substring(0, Math.min(300, submitted.body().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim().length()))));
        Map<String, String> returned = new LinkedHashMap<>();
        for (String pair : URI.create(location).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            returned.put(kv[0], java.net.URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        if (!state.equals(returned.get("state"))) throw new IllegalStateException("state mismatch");
        System.out.println("1. signed in as " + user + "; redirected to " + location.substring(0, location.indexOf('?')) + " with a code");

        String tokens = postToken("grant_type=authorization_code&client_id=" + enc(clientId) + "&code=" + enc(returned.get("code"))
                + "&redirect_uri=" + enc(redirect) + "&code_verifier=" + verifier, dpopKey);
        String accessToken = field(tokens, "access_token");
        System.out.println("2. token_type " + field(tokens, "token_type") + ", refresh token " + (tokens.contains("refresh_token") ? "issued" : "NOT issued"));
        String claims = payload(accessToken);
        System.out.println("3. access token header " + new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[0]), StandardCharsets.UTF_8));
        System.out.println("   claims " + claims);
        String boundTo = claims.replaceAll(".*\"jkt\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        System.out.println("   bound to this client's key: " + boundTo.equals(thumbprint(dpopKey)));

        String refreshToken = field(tokens, "refresh_token");
        String refreshed = postToken("grant_type=refresh_token&client_id=" + enc(clientId) + "&refresh_token=" + enc(refreshToken), dpopKey);
        String newAccess = field(refreshed, "access_token");
        System.out.println("4. refreshed with the same key; new token bound to it: " + payload(newAccess).contains(thumbprint(dpopKey)) + ", refresh token rotated: " + !refreshToken.equals(field(refreshed, "refresh_token")));
        try {
            postToken("grant_type=refresh_token&client_id=" + enc(clientId) + "&refresh_token=" + enc(refreshToken), dpopKey);
            System.out.println("   reusing the old refresh token: ACCEPTED (rotation is not enforced)");
        } catch (IllegalStateException expected) {
            System.out.println("   reusing the old refresh token: refused, as rotation requires");
        }
        KeyPair otherKey = generate();
        try {
            postToken("grant_type=refresh_token&client_id=" + enc(clientId) + "&refresh_token=" + enc(field(refreshed, "refresh_token")), otherKey);
            System.out.println("   refreshing with a different key: ACCEPTED (the refresh token is not bound to the key)");
        } catch (IllegalStateException expected) {
            System.out.println("   refreshing with a different key: refused");
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Authorization", "DPoP " + newAccess)
                .header("DPoP", proof(dpopKey, method, url, newAccess))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        System.out.println("5. " + method + " " + url + " -> " + response.statusCode());
        response.headers().allValues("WWW-Authenticate").forEach(h -> System.out.println("   WWW-Authenticate: " + h));
        System.out.println("   " + response.body());
    }

    static void remember(Map<String, String> jar, HttpResponse<?> response) {
        for (String header : response.headers().allValues("Set-Cookie")) {
            String pair = header.split(";", 2)[0];
            int equals = pair.indexOf('=');
            if (equals > 0) jar.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
        }
    }

    static String cookieHeader(Map<String, String> jar) {
        StringBuilder out = new StringBuilder();
        jar.forEach((name, value) -> out.append(out.length() == 0 ? "" : "; ").append(name).append('=').append(value));
        return out.toString();
    }

    static String postToken(String form, KeyPair dpopKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("DPoP", proof(dpopKey, "POST", TOKEN_URL, null))
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("token endpoint answered " + response.statusCode() + ": " + response.body());
        return response.body();
    }

    static String payload(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    static String thumbprint(KeyPair key) throws Exception {
        ECPublicKey pub = (ECPublicKey) key.getPublic();
        String canonical = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"" + coordinate(pub.getW().getAffineX()) + "\",\"y\":\"" + coordinate(pub.getW().getAffineY()) + "\"}";
        return B64.encodeToString(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    record Token(String accessToken, String tokenType) {}

    static Token fetchToken(Path keyFile, String clientId, KeyPair dpopKey) throws Exception {
        PrivateKey clientKey = readPrivateKey(Files.readString(keyFile));
        String assertion = jwt(clientKey, null, null,
                "{\"iss\":\"" + clientId + "\",\"sub\":\"" + clientId + "\",\"aud\":\"" + ISSUER + "\",\"jti\":\"" + UUID.randomUUID()
                        + "\",\"iat\":" + now() + ",\"exp\":" + (now() + 60) + "}");
        String form = "grant_type=client_credentials&client_id=" + clientId
                + "&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=" + assertion;
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("DPoP", proof(dpopKey, "POST", TOKEN_URL, null))
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("token endpoint answered " + response.statusCode() + ": " + response.body());
        return new Token(field(response.body(), "access_token"), field(response.body(), "token_type"));
    }

    static String proof(KeyPair key, String method, String url, String accessToken) throws Exception {
        URI uri = URI.create(url);
        String htu = uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath();
        String ath = accessToken == null ? "" : ",\"ath\":\"" + B64.encodeToString(MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII))) + "\"";
        ECPublicKey pub = (ECPublicKey) key.getPublic();
        String jwk = "{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"" + coordinate(pub.getW().getAffineX()) + "\",\"y\":\"" + coordinate(pub.getW().getAffineY()) + "\"}";
        return jwt(key.getPrivate(), "dpop+jwt", jwk,
                "{\"jti\":\"" + UUID.randomUUID() + "\",\"htm\":\"" + method + "\",\"htu\":\"" + htu + "\",\"iat\":" + now() + ath + "}");
    }

    static String jwt(PrivateKey key, String type, String jwk, String claims) throws Exception {
        String header = "{\"alg\":\"ES256\"" + (type == null ? "" : ",\"typ\":\"" + type + "\"") + (jwk == null ? "" : ",\"jwk\":" + jwk) + "}";
        String signingInput = B64.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "." + B64.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(key);
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + B64.encodeToString(signature.sign());
    }

    static KeyPair generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static void keygen(String clientId) throws Exception {
        KeyPair pair = generate();
        ECPublicKey pub = (ECPublicKey) pair.getPublic();
        String x = coordinate(pub.getW().getAffineX());
        String y = coordinate(pub.getW().getAffineY());
        String d = coordinate(((java.security.interfaces.ECPrivateKey) pair.getPrivate()).getS());
        String kid = clientId + "-key-1";
        System.out.println("{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"" + kid + "\",\"alg\":\"ES256\",\"use\":\"sig\",\"x\":\"" + x + "\",\"y\":\"" + y + "\",\"d\":\"" + d + "\"}");
        System.out.println("{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"" + kid + "\",\"alg\":\"ES256\",\"use\":\"sig\",\"x\":\"" + x + "\",\"y\":\"" + y + "\"}");
    }

    static PrivateKey readPrivateKey(String jwk) throws Exception {
        BigInteger d = new BigInteger(1, Base64.getUrlDecoder().decode(field(jwk, "d")));
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(d, parameters.getParameterSpec(ECParameterSpec.class)));
    }

    static String coordinate(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] fixed = new byte[32];
        int length = Math.min(bytes.length, 32);
        System.arraycopy(bytes, bytes.length - length, fixed, 32 - length, length);
        return B64.encodeToString(fixed);
    }

    static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!matcher.find()) throw new IllegalStateException("no " + name + " in " + json);
        return matcher.group(1);
    }

    static long now() {
        return System.currentTimeMillis() / 1000;
    }
}
