package dpop;


/** The names of the protocols, as Keycloak speaks them: OAuth 2.0, OpenID Connect and the DPoP extension. */
final class Oauth {
    private Oauth() {}

    static final String TOKEN_ENDPOINT_PATH = "/protocol/openid-connect/token";

    static final String DEFAULT_TOKEN_URL = "http://localhost:8180/realms/shortener" + TOKEN_ENDPOINT_PATH;

    static final String CLIENT_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    static final String CLIENT_ID = "client_id";

    static final String GRANT_TYPE = "grant_type";

    static final String ACCESS_TOKEN = "access_token";

    static final String REFRESH_TOKEN = "refresh_token";

    static final String ERROR = "error";
}
