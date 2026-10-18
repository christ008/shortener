package dpop;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** The transport of the client: the timeouts, the headers it reads and writes, and the helpers of a URL or a form. */
final class Http {
    private Http() {}

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    static final Duration TIMEOUT = Duration.ofSeconds(30);

    static final String CONTENT_TYPE = "Content-Type";

    static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    static final String DPOP_NONCE = "DPoP-Nonce";

    /** A resource server asks with a 401 whose challenge has {@code error="use_dpop_nonce"}, an authorization server with a 400 of that error. */
    static boolean asksForNonce(HttpResponse<String> response) {
        if (response.statusCode() == 401) {
            return response.headers().allValues(WWW_AUTHENTICATE).stream().anyMatch(challenge -> challenge.contains("error=\"use_dpop_nonce\""));
        }
        if (response.statusCode() == 400) {
            try {
                return "use_dpop_nonce".equals(Json.parse(response.body()).string(Oauth.ERROR));
            } catch (Failure _) {
                return false;
            }
        }
        return false;
    }

    static String origin(String url) {
        URI uri = uri(url);
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    static HttpResponse<String> exchange(HttpClient client, HttpRequest request) throws InterruptedException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException unreachable) {
            throw new Failure("could not reach " + request.uri() + ": " + unreachable, unreachable);
        }
    }

    // ---- small things -----------------------------------------------------------------------------------------------
    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static URI uri(String text) {
        try {
            return URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new Failure("not a URL: " + text, e);
        }
    }

    /** NAME, VALUE, NAME, VALUE...: an application/x-www-form-urlencoded body, or a query. */
    static String form(String... pairs) {
        List<String> encoded = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            encoded.add(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8) + "=" + URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return String.join("&", encoded);
    }

    /** The text of an HTML page or any text, on one line and cut short, for an error message. */
    static String excerpt(String text, int length) {
        String plain = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return plain.length() <= length ? plain : plain.substring(0, length) + "...";
    }
}
