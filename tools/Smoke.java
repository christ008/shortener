// Exercises every endpoint of a running application with DPoP-bound tokens from the identity provider, as the demo, other and
// admin clients, and checks the status each should answer. Meant for a native image, where what works on the JVM can still fail
// for want of reflection metadata, and run on every release against the image that is about to be published.
//
//   ./gradlew smoke [-PbaseUrl=http://localhost:8080] [-Pmgmt=http://localhost:8081]
//   tools/run Smoke [BASE_URL]            with MGMT in the environment for the management port
//
// The clients are the dev ones that deploy/keycloak/dev-setup makes, with their keys in deploy/keycloak/dev-keys (KEYS_DIR
// says another place), and the token endpoint is the one DpopClient uses (TOKEN_URL says another). A server with a certificate
// of your own is trusted as for any Java program, for example with JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=ts.p12.
// Redirects are not followed: the answer of a redirect is what is checked.
//
// A compact source file for JDK 25. DpopClient.java, beside it as a link, is the client; Cli.java is what the tools share.
// Exit status: 0 when every check passed, 1 when one did not, 2 for arguments it does not understand.

import module java.net.http;

private static final String USAGE = "usage: tools/run Smoke [BASE_URL]   (MGMT in the environment: the management port, default http://localhost:8081)";

private static final Path KEYS = Path.of(Optional.ofNullable(System.getenv("KEYS_DIR")).filter(dir -> !dir.isBlank()).orElse("deploy/keycloak/dev-keys"));

private static final Pattern SHORT_CODE = Pattern.compile("\"shortCode\":\"([^\"]*)\"");

private static final HttpClient HTTP = HttpClient.newHttpClient();

private int failed;

void main(String[] args) {
    Cli.run("Smoke", USAGE, () -> {
        if (args.length > 1) {
            throw new Cli.Usage("at most one argument, the base URL");
        }
        String base = args.length == 1 ? args[0] : "http://localhost:8080";
        String management = Optional.ofNullable(System.getenv("MGMT")).filter(url -> !url.isBlank()).orElse("http://localhost:8081");
        checks(base, management);
        if (failed == 0) {
            IO.println("all checks passed");
        } else {
            IO.println(failed + " checks failed");
            System.exit(1);
        }
    });
}

private void checks(String base, String management) {
    String api = base + "/api/short-links";

    var generated = call("demo-client", "POST", api, """
            {"targetUrl":"https://example.com/smoke"}""");
    check("create with a generated code", 201, generated);
    String code = SHORT_CODE.matcher(generated.body()).results().map(match -> match.group(1)).findFirst().orElse("");
    String custom = "smoke-" + ThreadLocalRandom.current().nextInt(32768);
    String customBody = """
            {"targetUrl":"https://example.com/custom","customCode":"%s"}""".formatted(custom);
    check("create with a custom code", 201, call("demo-client", "POST", api, customBody));
    check("custom code taken", 409, call("demo-client", "POST", api, customBody));
    check("invalid url", 400, call("demo-client", "POST", api, """
            {"targetUrl":"not a url"}"""));
    check("missing url", 400, call("demo-client", "POST", api, "{}"));
    check("get own link", 200, call("demo-client", "GET", api + "/" + code, null));
    check("another client's link looks not found", 404, call("other-client", "GET", api + "/" + code, null));
    check("admin reads any link", 200, call("admin-client", "GET", api + "/" + code, null));
    check("list own links", 200, call("demo-client", "GET", api + "?size=1&sort=shortCode,asc", null));
    check("list with a sort that is not allowed", 400, call("demo-client", "GET", api + "?sort=targetUrl", null));
    check("no-scope client cannot create", 403, call("no-scope-client", "POST", api, """
            {"targetUrl":"https://example.com/x"}"""));
    check("redirect is public", 302, plain(base + "/" + code, Map.of()));
    check("unknown code", 404, plain(base + "/zzzzzzz", Map.of()));
    check("another client cannot disable", 404, call("other-client", "DELETE", api + "/" + code, null));
    check("owner disables", 204, call("demo-client", "DELETE", api + "/" + code, null));
    check("disabled link answers gone", 410, plain(base + "/" + code, Map.of()));
    check("bearer scheme is refused", 401, plain(api, Map.of("Authorization", "Bearer " + token("demo-client"))));
    check("no credentials", 401, plain(api, Map.of()));
    check("protected resource metadata", 200, plain(base + "/.well-known/oauth-protected-resource", Map.of()));
    check("readiness", 200, plain(management + "/actuator/health/readiness", Map.of()));
    var metrics = plain(management + "/actuator/prometheus", Map.of());
    check("request metrics are exported", metrics.status() == 200 && metrics.body().contains("\nhttp_server_requests_seconds_bucket"),
            metrics.status() == 200 ? "no http_server_requests_seconds_bucket" : "status " + metrics.status());
}

/** What a request answered, or the reason it did not: a refused connection is a failed check, not the end of the run. */
private record Reply(int status, String body) {
    static Reply unreachable(Exception failure) {
        return new Reply(-1, failure.toString());
    }
}

private static Reply call(String client, String method, String url, String body) {
    try {
        var answer = DpopClient.call(KEYS.resolve(client + ".jwk.json"), client, method, url, body);
        return new Reply(answer.status(), answer.body());
    } catch (Exception failure) {
        return Reply.unreachable(failure);
    }
}

private static String token(String client) {
    try {
        return DpopClient.token(KEYS.resolve(client + ".jwk.json"), client);
    } catch (Exception failure) {
        return "no-token: " + failure.getMessage();
    }
}

private static Reply plain(String url, Map<String, String> headers) {
    try {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET();
        headers.forEach(request::header);
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    } catch (IOException | InterruptedException failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return Reply.unreachable(failure);
    }
}

private void check(String name, int expected, Reply reply) {
    check(name, expected == reply.status(), "expected %d, got %s".formatted(expected, reply.status() < 0 ? "no answer: " + reply.body() : reply.status()));
}

private void check(String name, boolean passed, String otherwise) {
    if (passed) {
        IO.println("ok    " + name);
    } else {
        IO.println("FAIL  %s (%s)".formatted(name, otherwise));
        failed++;
    }
}
