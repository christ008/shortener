package dpop;

import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The browser part of the login: the login page of Keycloak, the form submitted on it, and the redirect that carries the code. */
class KeycloakLogin {
    private static final Pattern FORM_TAG = Pattern.compile("<form\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION = Pattern.compile("\\baction=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /** The parameters of the authorization request, which the login page is asked for. */
    record AuthorizationRequest(String clientId, String redirect, String scope, String state, String challenge, String jkt) {}

    private final String issuer;
    private final Console console;

    KeycloakLogin(String issuer, Console console) {
        this.issuer = issuer;
        this.console = console;
    }

    private static Map<String, String> queryOf(String location) {
        Map<String, String> parameters = new LinkedHashMap<>();
        String query = Http.uri(location).getRawQuery();
        if (query == null) return parameters;
        for (String pair : query.split("&")) {
            String[] nameAndValue = pair.split("=", 2);
            parameters.put(URLDecoder.decode(nameAndValue[0], StandardCharsets.UTF_8),
                    nameAndValue.length > 1 ? URLDecoder.decode(nameAndValue[1], StandardCharsets.UTF_8) : "");
        }
        return parameters;
    }

    /** Opens the login page, submits the credentials, and returns the authorization code of the redirect. */
    String signIn(String user, String password, AuthorizationRequest request) throws InterruptedException {
        HttpClient browser = HttpClient.newBuilder().connectTimeout(Http.CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
        Map<String, String> cookies = new LinkedHashMap<>();
        String authUrl = issuer + "/protocol/openid-connect/auth?" + Http.form("response_type", "code", Oauth.CLIENT_ID, request.clientId(),
                "redirect_uri", request.redirect(), "scope", request.scope(), "state", request.state(), "code_challenge", request.challenge(),
                "code_challenge_method", "S256", "dpop_jkt", request.jkt());

        HttpResponse<String> page = Http.exchange(browser, HttpRequest.newBuilder(Http.uri(authUrl)).timeout(Http.TIMEOUT).GET().build());
        remember(cookies, page);
        String action = loginFormAction(page.body())
                .orElseThrow(() -> new Failure("no login form (status " + page.statusCode() + "): " + Http.excerpt(page.body(), 500)));

        HttpResponse<String> submitted = Http.exchange(browser, HttpRequest.newBuilder(Http.uri(action))
                .timeout(Http.TIMEOUT)
                .header(Http.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .header("Cookie", cookieHeader(cookies))
                .POST(HttpRequest.BodyPublishers.ofString(Http.form("username", user, "password", password, "credentialId", "")))
                .build());
        String location = submitted.headers().firstValue("Location").orElseThrow(() -> new Failure(
                "the login did not redirect (status " + submitted.statusCode() + "): " + Http.excerpt(submitted.body(), 300)));

        Map<String, String> returned = queryOf(location);
        if (returned.containsKey(Oauth.ERROR)) {
            throw new Failure("the login was refused: " + returned.get(Oauth.ERROR) + " " + returned.getOrDefault("error_description", ""));
        }
        if (!request.state().equals(returned.get("state"))) throw new Failure("state mismatch");
        String code = returned.get("code");
        if (code == null) throw new Failure("the redirect carries no code: " + location);
        int query = location.indexOf('?');
        console.out("1. signed in as " + user + "; redirected to " + (query < 0 ? location : location.substring(0, query)) + " with a code");
        return code;
    }

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
}
