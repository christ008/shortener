import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.util.Base64;
import java.util.UUID;
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
 *
 * The token endpoint comes from TOKEN_URL, by default the local realm.
 */
public class DpopClient {

    static final String TOKEN_URL = System.getenv().getOrDefault("TOKEN_URL", "http://localhost:8180/realms/shortener/protocol/openid-connect/token");
    static final String ISSUER = System.getenv().getOrDefault("ISSUER", TOKEN_URL.replaceAll("/protocol/openid-connect/token$", ""));
    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
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
                        .header("Authorization", "DPoP " + token.accessToken)
                        .header("DPoP", proof(dpopKey, method, url, token.accessToken))
                        .header("Content-Type", "application/json")
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
                HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
                System.out.println(response.statusCode());
                response.headers().firstValue("Location").ifPresent(l -> System.out.println("Location: " + l));
                response.headers().allValues("WWW-Authenticate").forEach(h -> System.out.println("WWW-Authenticate: " + h));
                System.out.println(response.body());
            }
            default -> throw new IllegalArgumentException("unknown command " + args[0]);
        }
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
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec params = ((ECPublicKey) generator.generateKeyPair().getPublic()).getParams();
        return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(d, params));
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
