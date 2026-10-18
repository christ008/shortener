import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The key material of the client: the P-256 keys, their JWKs, their thumbprints, and the key file. */
final class Keys {
    static final String P256 = "P-256";

    /** A new client key as a private JWK and a public one, in that order. */
    static List<Map<String, Object>> newJwks(String clientId) {
        KeyPair pair = newKey();
        ECPublicKey pub = ecPublic(pair);
        Map<String, Object> publicJwk = new LinkedHashMap<>();
        publicJwk.put("kty", "EC");
        publicJwk.put("crv", P256);
        publicJwk.put("kid", clientId + "-key-1");
        publicJwk.put("alg", Jose.ES256);
        publicJwk.put("use", "sig");
        publicJwk.put("x", coordinate(pub.getW().getAffineX()));
        publicJwk.put("y", coordinate(pub.getW().getAffineY()));
        Map<String, Object> privateJwk = new LinkedHashMap<>(publicJwk);
        privateJwk.put("d", coordinate(ecPrivate(pair).getS()));
        return List.of(privateJwk, publicJwk);
    }

    /** Leaves the key file readable by its owner alone; a file system without POSIX permissions has nothing to restrict. */
    static void restrictToOwner(Path keyFile) throws IOException {
        try {
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException _) {
            // not a POSIX file system: nothing more can be done here
        }
    }

    static ECPublicKey ecPublic(KeyPair pair) {
        if (pair.getPublic() instanceof ECPublicKey key) return key;
        throw new Failure("the key pair is not an EC key: " + pair.getPublic().getAlgorithm());
    }

    static ECPrivateKey ecPrivate(KeyPair pair) {
        if (pair.getPrivate() instanceof ECPrivateKey key) return key;
        throw new Failure("the key pair is not an EC key: " + pair.getPrivate().getAlgorithm());
    }

    static Map<String, Object> publicJwk(KeyPair key) {
        ECPublicKey pub = ecPublic(key);
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "EC");
        jwk.put("crv", P256);
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
        return Jose.B64.encodeToString(Jose.sha256(Json.write(canonical)));
    }

    // ---- keys -------------------------------------------------------------------------------------------------------
    static KeyPair newKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new Failure("this JDK cannot make P-256 keys: " + e, e);
        }
    }

    /** The private key of a JWK file. Only `d` is needed to sign, but the key must be a P-256 key to be one for Jose.ES256. */
    static PrivateKey readPrivateKey(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new Failure("could not read the key file " + file + ": " + e.getMessage(), e);
        }
        Json jwk = Json.parse(text);
        if (!"EC".equals(jwk.string("kty")) || !P256.equals(jwk.string("crv")) || jwk.string("d") == null) {
            throw new Failure(file + " is not a private P-256 JWK (it needs kty EC, crv P-256 and d)");
        }
        try {
            BigInteger d = new BigInteger(1, Jose.B64_DECODER.decode(jwk.string("d")));
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
        return Jose.B64.encodeToString(fixed);
    }
}
