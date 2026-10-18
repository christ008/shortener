package dpop;

import module java.base;
import module java.net.http;

/** The token endpoint and the API calls of the client, with the nonce each server asked for. */
final class TokenEndpoint {
    private final String tokenUrl;
    private final String issuer;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Http.CONNECT_TIMEOUT).build();
    private final Map<String, String> nonces = new HashMap<>();

    TokenEndpoint(String tokenUrl, String issuer) {
        this.tokenUrl = tokenUrl;
        this.issuer = issuer;
    }
    // ---- the identity provider --------------------------------------------------------------------------------------
    record Token(String accessToken, String tokenType) {}

    /** Authenticates the client with a signed assertion, and asks for a token bound to {@code dpopKey}. */
    Token fetchToken(Path keyFile, String clientId, KeyPair dpopKey) throws InterruptedException {
        PrivateKey clientKey = Keys.readPrivateKey(keyFile);
        Json answer = Json.parse(postToken(() -> Http.form(Oauth.GRANT_TYPE, "client_credentials", Oauth.CLIENT_ID, clientId,
                "client_assertion_type", Oauth.CLIENT_ASSERTION_TYPE, "client_assertion", assertion(clientKey, clientId)), dpopKey));
        String accessToken = answer.string(Oauth.ACCESS_TOKEN);
        if (accessToken == null) throw new Failure("the token endpoint did not return an access_token: " + answer);
        return new Token(accessToken, answer.string("token_type"));
    }

    /** A new assertion for each token request, since an identity provider may refuse one that it has seen: the {@code jti} is new. */
    String assertion(PrivateKey clientKey, String clientId) {
        long now = Jose.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", clientId);
        claims.put("sub", clientId);
        claims.put("aud", issuer);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", now);
        claims.put("exp", now + 60);
        return Jose.jwt(clientKey, Map.of("alg", Jose.ES256), claims);
    }

    String postToken(String form, KeyPair dpopKey) throws InterruptedException {
        return postToken(() -> form, dpopKey);
    }
    /**
     * Posts a form to the token endpoint with a proof of {@code dpopKey}, and returns the body of the 200 it must answer. The form
     * is made again for a request that is repeated with a nonce.
     */
    private String postToken(Supplier<String> form, KeyPair dpopKey) throws InterruptedException {
        HttpResponse<String> response = sendProved(tokenUrl, nonce -> HttpRequest.newBuilder(Http.uri(tokenUrl))
                .timeout(Http.TIMEOUT)
                .header(Http.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .header("DPoP", Jose.proof(dpopKey, "POST", tokenUrl, null, nonce))
                .POST(HttpRequest.BodyPublishers.ofString(form.get()))
                .build());
        if (response.statusCode() != 200) {
            throw new Refused(response.statusCode(), response.body());
        }
        return response.body();
    }

    boolean accepts(String form, KeyPair dpopKey) throws InterruptedException {
        try {
            postToken(form, dpopKey);
            return true;
        } catch (Refused _) {
            return false;
        }
    }

    static String refreshForm(String clientId, String refreshToken) {
        return Http.form(Oauth.GRANT_TYPE, Oauth.REFRESH_TOKEN, Oauth.CLIENT_ID, clientId, Oauth.REFRESH_TOKEN, refreshToken);
    }

    /**
     * A request with the token and a proof made for it, carrying {@code nonce} when there is one. A body is sent as JSON, and
     * without one there is no content type. A trace context goes along when TRACEPARENT is set.
     */
    HttpRequest apiRequest(String method, String url, String body, String accessToken, KeyPair dpopKey, String nonce) {
        HttpRequest.Builder request = HttpRequest.newBuilder(Http.uri(url))
                .timeout(Http.TIMEOUT)
                .header("Authorization", "DPoP " + accessToken)
                .header("DPoP", Jose.proof(dpopKey, method, url, accessToken, nonce));
        boolean hasBody = body != null && !body.isEmpty();
        if (hasBody) request.header(Http.CONTENT_TYPE, "application/json");
        String traceparent = System.getenv("TRACEPARENT");
        if (traceparent != null && !traceparent.isBlank()) request.header("traceparent", traceparent);
        return request.method(method, hasBody ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody()).build();
    }

    HttpResponse<String> send(HttpRequest request) throws InterruptedException {
        return Http.exchange(http, request);
    }

    /**
     * Sends the request that {@code build} makes with the nonce the server of {@code url} last handed out (null when it has
     * handed none), and keeps the nonce of the answer. When the answer asks for a nonce and gives one, the request is made again
     * once, with a new proof that carries it.
     */
    HttpResponse<String> sendProved(String url, Function<String, HttpRequest> build) throws InterruptedException {
        String origin = Http.origin(url);
        HttpResponse<String> response = send(build.apply(nonces.get(origin)));
        response.headers().firstValue(Http.DPOP_NONCE).ifPresent(nonce -> nonces.put(origin, nonce));
        if (Http.asksForNonce(response) && response.headers().firstValue(Http.DPOP_NONCE).isPresent()) {
            response = send(build.apply(nonces.get(origin)));
            response.headers().firstValue(Http.DPOP_NONCE).ifPresent(nonce -> nonces.put(origin, nonce));
        }
        return response;
    }
}
