package dpop;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** JSON Web Signatures and the DPoP proofs of RFC 9449: signed with ES256, and the base64url text they are made of. */
final class Jose {
    private Jose() {}

    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();

    static final SecureRandom RANDOM = new SecureRandom();

    static final String ES256 = "ES256";

    // ---- DPoP and JWT -----------------------------------------------------------------------------------------------
    /** The DPoP proof for one request: the method, the URL without its query and fragment, and a hash of the token. */
    static String proof(KeyPair key, String method, String url, String accessToken) {
        return proof(key, method, url, accessToken, null);
    }
    /** The same, with the server's nonce in the {@code nonce} claim when there is one. */
    static String proof(KeyPair key, String method, String url, String accessToken, String nonce) {
        URI uri = Http.uri(url);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", ES256);
        header.put("typ", "dpop+jwt");
        header.put("jwk", Keys.publicJwk(key));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("htm", method);
        claims.put("htu", uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath());
        claims.put("iat", now());
        if (accessToken != null) claims.put("ath", B64.encodeToString(sha256(accessToken)));
        if (nonce != null) claims.put("nonce", nonce);
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

    /** The `jkt` of the `cnf` claim of an access token: the key it is bound to. */
    static String confirmationOf(String jwt) {
        Object cnf = Json.parse(jwtPart(jwt, 1)).get("cnf");
        return cnf instanceof Map<?, ?> map && map.get("jkt") instanceof String jkt ? jkt : null;
    }

    static String jwtPart(String jwt, int index) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) throw new Failure("not a JWT: " + Http.excerpt(jwt, 60));
        return new String(B64_DECODER.decode(parts[index]), StandardCharsets.UTF_8);
    }

    static long now() {
        return System.currentTimeMillis() / 1000;
    }

    static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }
}
