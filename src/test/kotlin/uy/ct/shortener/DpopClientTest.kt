package uy.ct.shortener

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.InvocationTargetException
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLClassLoader
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

/**
 * `deploy/keycloak/DpopClient.java` is not part of the application, but the local setup, `dev-setup`, the smoke test of
 * the native image and the README all depend on it, and a call that failed before sending anything went unnoticed because
 * the one script that exercises it only runs on demand. This runs the real file as a subprocess, as the scripts do,
 * against a stand-in for Keycloak and the API, and checks what it sent with a different implementation (Nimbus): the
 * proof is a valid signature by the key it embeds, made for this method and URL and this token, and the client assertion
 * verifies against the client's public key. The stand-in also plays the login of a single-page app, with PKCE, a
 * DPoP-bound code and a rotating, key-bound refresh token, so that `login` is exercised too.
 *
 * The client is written for JDK 17, and the tests run on whatever JDK builds the project, so one of them compiles it
 * with `--release 17` and every lint on, and the rest of what it has no unit to call, such as its JSON reader and the
 * padding of coordinates, is called on that compiled class. Compiling for 17 is not running on 17, so with
 * `DPOP_CLIENT_JAVA_HOME` set to a JDK 17 the client runs on that one, which is what CI does.
 */
class DpopClientTest {

    @TempDir
    lateinit var directory: Path

    private lateinit var clientKey: ECKey

    private lateinit var keyFile: Path

    private class Request(val method: String, val uri: String, val headers: Map<String, String>, val body: String)

    private class TokenRequest(val headers: Map<String, String>, val form: Map<String, String>)

    private class Output(val exitCode: Int, val stdout: List<String>, val stderr: String)

    private val received = CopyOnWriteArrayList<Request>()
    private val tokenRequests = CopyOnWriteArrayList<TokenRequest>()
    private lateinit var server: HttpServer

    /** What the stand-in answers to the client's own token request: 200, or a status that makes it refuse. */
    @Volatile
    private var clientTokenStatus = 200

    // What the stand-in identity provider remembers of a login, as the real one does.
    @Volatile
    private var authorizationRequest: Map<String, String> = emptyMap()
    private val refreshTokenKeys = ConcurrentHashMap<String, String>()
    private val usedRefreshTokens = ConcurrentHashMap.newKeySet<String>()
    private val issued = AtomicInteger()

    private val javaHomeOfTheClient = System.getenv("DPOP_CLIENT_JAVA_HOME")?.takeIf { it.isNotBlank() } ?: System.getProperty("java.home")

    private val baseUrl get() = "http://localhost:${server.address.port}"

    private fun headersOf(exchange: HttpExchange) = exchange.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.first() }

    private fun formOf(text: String) = text.split("&").filter { it.isNotEmpty() }.associate {
        URLDecoder.decode(it.substringBefore("="), StandardCharsets.UTF_8) to URLDecoder.decode(it.substringAfter("=", ""), StandardCharsets.UTF_8)
    }

    @BeforeEach
    fun startStandIn() {
        clientKey = ECKeyGenerator(Curve.P_256).keyID("demo-client-key-1").generate()
        keyFile = directory.resolve("demo-client.jwk.json").also { Files.writeString(it, clientKey.toJSONString()) }
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/realms/shortener/protocol/openid-connect/token") { exchange ->
            val form = formOf(String(exchange.requestBody.readAllBytes()))
            val headers = headersOf(exchange)
            tokenRequests += TokenRequest(headers, form)
            when (form["grant_type"]) {
                "authorization_code" -> issueForCode(exchange, headers, form)
                "refresh_token" -> issueForRefresh(exchange, headers, form)
                else -> if (clientTokenStatus == 200) {
                    reply(exchange, 200, """{"access_token":"stand-in-token","token_type":"DPoP"}""")
                } else {
                    reply(exchange, clientTokenStatus, """{"error":"invalid_client"}""")
                }
            }
        }
        server.createContext("/realms/shortener/protocol/openid-connect/auth") { exchange ->
            authorizationRequest = formOf(exchange.requestURI.rawQuery)
            exchange.responseHeaders.add("Set-Cookie", "KC_SESSION=abc; Path=/; HttpOnly")
            reply(
                exchange,
                200,
                """<html><body><form id="kc-form-login" onsubmit="login.disabled = true; return true;" """ +
                    """action="$baseUrl/login-actions/authenticate?session_code=1&amp;tab_id=2" method="post"></form></body></html>""",
                "text/html",
            )
        }
        server.createContext("/login-actions/authenticate") { exchange ->
            val form = formOf(String(exchange.requestBody.readAllBytes()))
            val signedIn = headersOf(exchange)["cookie"] == "KC_SESSION=abc" && exchange.requestURI.rawQuery == "session_code=1&tab_id=2" &&
                form["username"] == "alice" && form["password"] == "secret"
            if (signedIn) {
                exchange.responseHeaders.add(
                    "Location",
                    "http://localhost:3000/app/auth/callback?session_state=s&code=the-code&state=${authorizationRequest["state"]}",
                )
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else {
                reply(exchange, 200, "<html><body><span>Invalid username or password.</span></body></html>", "text/html")
            }
        }
        server.createContext("/api") { exchange ->
            received += Request(exchange.requestMethod, exchange.requestURI.toString(), headersOf(exchange), String(exchange.requestBody.readAllBytes()))
            reply(exchange, 201, """{"shortCode":"abc"}""")
        }
        server.start()
    }

    @AfterEach
    fun stopStandIn() = server.stop(0)

    private fun reply(exchange: HttpExchange, status: Int, body: String, contentType: String = "application/json") {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun thumbprintOf(headers: Map<String, String>) = SignedJWT.parse(headers.getValue("dpop")).header.jwk.computeThumbprint().toString()

    private fun sha256(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    private fun accessTokenFor(jkt: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"ES256","typ":"at+jwt"}""".toByteArray())
        val payload = encoder.encodeToString("""{"sub":"alice","cnf":{"jkt":"$jkt"}}""".toByteArray())
        return "$header.$payload.stand-in-signature"
    }

    private fun issueTokens(exchange: HttpExchange, jkt: String) {
        val refresh = "refresh-${issued.incrementAndGet()}"
        refreshTokenKeys[refresh] = jkt
        reply(exchange, 200, """{"access_token":"${accessTokenFor(jkt)}","token_type":"DPoP","refresh_token":"$refresh"}""")
    }

    private fun issueForCode(exchange: HttpExchange, headers: Map<String, String>, form: Map<String, String>) {
        val jkt = thumbprintOf(headers)
        val valid = form["code"] == "the-code" && sha256(form.getValue("code_verifier")) == authorizationRequest["code_challenge"] &&
            jkt == authorizationRequest["dpop_jkt"]
        if (valid) issueTokens(exchange, jkt) else reply(exchange, 400, """{"error":"invalid_grant"}""")
    }

    private fun issueForRefresh(exchange: HttpExchange, headers: Map<String, String>, form: Map<String, String>) {
        val refresh = form.getValue("refresh_token")
        val jkt = thumbprintOf(headers)
        val valid = refreshTokenKeys[refresh] == jkt && !usedRefreshTokens.contains(refresh)
        if (valid) {
            usedRefreshTokens += refresh
            issueTokens(exchange, jkt)
        } else {
            reply(exchange, 400, """{"error":"invalid_grant"}""")
        }
    }

    private fun run(vararg args: String, environment: Map<String, String> = emptyMap()): Output {
        val java = Path.of(javaHomeOfTheClient, "bin", "java").toString()
        val process = ProcessBuilder(listOf(java, "deploy/keycloak/DpopClient.java") + args)
            .apply {
                environment().remove("TRACEPARENT")
                environment()["TOKEN_URL"] = "$baseUrl/realms/shortener/protocol/openid-connect/token"
                environment().putAll(environment)
            }
            .start()
        val stderr = CompletableFuture.supplyAsync { process.errorReader().readText() }
        val stdout = process.inputReader().readLines()
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).describedAs("the client finished").isTrue()
        return Output(process.exitValue(), stdout, stderr.get())
    }

    private fun call(method: String, path: String, body: String? = null, environment: Map<String, String> = emptyMap(), key: Path = keyFile) =
        run(
            *listOfNotNull("call", key.toString(), "demo-client", method, "$baseUrl$path", body).toTypedArray(),
            environment = environment,
        )

    @Test
    fun `calls the API with a DPoP token and a proof made for that request`() {
        val output = call("GET", "/api/short-links?size=1&sort=shortCode,asc")

        assertThat(output.exitCode).describedAs(output.stderr).isZero()
        assertThat(output.stdout.first()).isEqualTo("201")
        val request = received.single()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.headers["authorization"]).isEqualTo("DPoP stand-in-token")

        val proof = SignedJWT.parse(request.headers.getValue("dpop"))
        assertThat(proof.header.type.toString()).isEqualTo("dpop+jwt")
        assertThat(proof.verify(ECDSAVerifier(proof.header.jwk.toECKey()))).describedAs("signed by the key in its own header").isTrue()
        assertThat(proof.jwtClaimsSet.getStringClaim("htm")).isEqualTo("GET")
        assertThat(proof.jwtClaimsSet.getStringClaim("htu")).describedAs("without the query").isEqualTo("$baseUrl/api/short-links")
        assertThat(proof.jwtClaimsSet.getStringClaim("ath")).isEqualTo(sha256("stand-in-token"))
    }

    @Test
    fun `asks for the token with an assertion signed by the client key and a proof from the key the token is bound to`() {
        val output = call("GET", "/api/short-links")

        assertThat(output.exitCode).describedAs(output.stderr).isZero()
        val tokenRequest = tokenRequests.single()
        assertThat(tokenRequest.form).containsEntry("grant_type", "client_credentials").containsEntry("client_id", "demo-client")
            .containsEntry("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")

        val assertion = SignedJWT.parse(tokenRequest.form.getValue("client_assertion"))
        assertThat(assertion.verify(ECDSAVerifier(clientKey.toPublicJWK()))).describedAs("signed by the client's own key").isTrue()
        assertThat(assertion.jwtClaimsSet.issuer).isEqualTo("demo-client")
        assertThat(assertion.jwtClaimsSet.subject).isEqualTo("demo-client")
        assertThat(assertion.jwtClaimsSet.audience).containsExactly("$baseUrl/realms/shortener")
        assertThat(assertion.jwtClaimsSet.expirationTime.time - assertion.jwtClaimsSet.issueTime.time).describedAs("short lived").isEqualTo(60_000)

        val tokenProof = SignedJWT.parse(tokenRequest.headers.getValue("dpop"))
        assertThat(tokenProof.jwtClaimsSet.getStringClaim("htm")).isEqualTo("POST")
        assertThat(tokenProof.jwtClaimsSet.getStringClaim("htu")).isEqualTo("$baseUrl/realms/shortener/protocol/openid-connect/token")
        assertThat(tokenProof.jwtClaimsSet.getClaim("ath")).describedAs("there is no token yet to hash").isNull()
        val apiProof = SignedJWT.parse(received.single().headers.getValue("dpop"))
        assertThat(apiProof.header.jwk.computeThumbprint()).isEqualTo(tokenProof.header.jwk.computeThumbprint())
    }

    @Test
    fun `sends no trace context and no content type when there is neither a TRACEPARENT nor a body`() {
        call("GET", "/api/short-links")

        val headers = received.single().headers
        assertThat(headers).doesNotContainKeys("traceparent", "content-type")
    }

    @Test
    fun `forwards TRACEPARENT as the trace context of the request`() {
        val traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

        val output = call("GET", "/api/short-links", environment = mapOf("TRACEPARENT" to traceparent))

        assertThat(output.exitCode).describedAs(output.stderr).isZero()
        assertThat(received.single().headers["traceparent"]).isEqualTo(traceparent)
    }

    @Test
    fun `sends a body as JSON`() {
        val body = """{"targetUrl":"https://example.com/x"}"""

        val output = call("POST", "/api/short-links", body)

        assertThat(output.exitCode).describedAs(output.stderr).isZero()
        val request = received.single()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.headers["content-type"]).isEqualTo("application/json")
        assertThat(request.body).isEqualTo(body)
    }

    @Test
    fun `explains its usage instead of a stack trace when it is given nothing`() {
        val output = run()

        assertThat(output.exitCode).isEqualTo(2)
        assertThat(output.stderr).contains("usage:").doesNotContain("Exception")
    }

    @Test
    fun `prints just the token`() {
        val output = run("token", keyFile.toString(), "demo-client")

        assertThat(output.exitCode).describedAs(output.stderr).isZero()
        assertThat(output.stdout).containsExactly("stand-in-token")
    }

    @Test
    fun `makes keys of its own that another implementation reads, and that sign for the client`() {
        val output = run("keygen", "fresh-client")

        assertThat(output.exitCode).describedAs(output.stderr).isZero()
        assertThat(output.stdout).hasSize(2)
        val private = ECKey.parse(output.stdout[0])
        val public = ECKey.parse(output.stdout[1])
        assertThat(private.isPrivate).isTrue
        assertThat(public.isPrivate).isFalse
        assertThat(private.keyID).isEqualTo("fresh-client-key-1")
        assertThat(private.toPublicJWK().computeThumbprint()).describedAs("the public half is the private key's").isEqualTo(public.computeThumbprint())
        assertThat(private.d.decode()).describedAs("a 32 byte scalar").hasSize(32)

        val file = directory.resolve("fresh.jwk.json").also { Files.writeString(it, output.stdout[0]) }
        val used = call("GET", "/api/short-links", key = file)

        assertThat(used.exitCode).describedAs(used.stderr).isZero()
        val assertion = SignedJWT.parse(tokenRequests.single().form.getValue("client_assertion"))
        assertThat(assertion.verify(ECDSAVerifier(public))).describedAs("signed by the key that keygen made").isTrue()
    }

    @Test
    fun `signs with a client key whose private scalar starts with a zero byte`() {
        // About one key in 256 has one, and BigInteger drops it: the case that makes a hand-made encoding fail now and then.
        var key = ECKeyGenerator(Curve.P_256).keyID("demo-client-key-1").generate()
        while (key.d.decode()[0] != 0.toByte()) key = ECKeyGenerator(Curve.P_256).keyID("demo-client-key-1").generate()
        val file = directory.resolve("leading-zero.jwk.json").also { Files.writeString(it, key.toJSONString()) }

        val output = call("GET", "/api/short-links", key = file)

        assertThat(output.exitCode).describedAs(output.stderr).isZero()
        val assertion = SignedJWT.parse(tokenRequests.single().form.getValue("client_assertion"))
        assertThat(assertion.verify(ECDSAVerifier(key.toPublicJWK()))).isTrue()
    }

    @Test
    fun `signs a person in with a code bound to its key, refreshes the token, and calls the API as them`() {
        val output = run("login", "alice", "secret", "GET", "$baseUrl/api/short-links")

        assertThat(output.exitCode).describedAs(output.stderr).isZero()
        assertThat(output.stdout).contains(
            "1. signed in as alice; redirected to http://localhost:3000/app/auth/callback with a code",
            "2. token_type DPoP, refresh token issued",
            "   bound to this client's key: true",
            "4. refreshed with the same key; new token bound to it: true, refresh token rotated: true",
            "   reusing the old refresh token: refused, as rotation requires",
            "   refreshing with a different key: refused",
            "5. GET $baseUrl/api/short-links -> 201",
        )
        assertThat(output.stdout.single { it.startsWith("   claims ") }).contains(""""sub":"alice"""")
        assertThat(authorizationRequest)
            .containsEntry("client_id", "shortener-ui")
            .containsEntry("response_type", "code")
            .containsEntry("code_challenge_method", "S256")
            .containsEntry("redirect_uri", "http://localhost:3000/app/auth/callback")
        assertThat(tokenRequests.map { it.form["grant_type"] }).containsExactly("authorization_code", "refresh_token", "refresh_token", "refresh_token")

        val request = received.single()
        val latestAccessToken = request.headers.getValue("authorization").removePrefix("DPoP ")
        val proof = SignedJWT.parse(request.headers.getValue("dpop"))
        assertThat(proof.verify(ECDSAVerifier(proof.header.jwk.toECKey()))).isTrue()
        assertThat(proof.jwtClaimsSet.getStringClaim("ath")).isEqualTo(sha256(latestAccessToken))
        assertThat(proof.header.jwk.computeThumbprint().toString()).isEqualTo(authorizationRequest["dpop_jkt"])
        assertThat(request.headers).doesNotContainKey("content-type")
    }

    @Test
    fun `says why a login failed, in a line`() {
        val output = run("login", "alice", "wrong", "GET", "$baseUrl/api/short-links")

        assertThat(output.exitCode).isEqualTo(1)
        assertThat(output.stderr).contains("DpopClient: the login did not redirect (status 200): Invalid username or password.")
        assertThat(received).isEmpty()
    }

    @Test
    fun `says what the token endpoint answered, in a line, when it refuses the client`() {
        clientTokenStatus = 401

        val output = call("GET", "/api/short-links")

        assertThat(output.exitCode).isEqualTo(1)
        assertThat(output.stdout).isEmpty()
        assertThat(output.stderr).contains("""DpopClient: the token endpoint answered 401: {"error":"invalid_client"}""").doesNotContain("\tat ")
        assertThat(received).isEmpty()
    }

    @Test
    fun `says that the identity provider cannot be reached, in a line`() {
        val closed = ServerSocket(0).use { it.localPort }

        val output = call("GET", "/api/short-links", environment = mapOf("TOKEN_URL" to "http://localhost:$closed/token"))

        assertThat(output.exitCode).isEqualTo(1)
        assertThat(output.stderr).contains("DpopClient: could not reach http://localhost:$closed/token").doesNotContain("\tat ")
    }

    @Test
    fun `says what is wrong with a key file, in a line`() {
        val missing = call("GET", "/api/short-links", key = directory.resolve("missing.jwk.json"))
        val publicOnly = directory.resolve("public.jwk.json").also { Files.writeString(it, clientKey.toPublicJWK().toJSONString()) }
        val noPrivatePart = call("GET", "/api/short-links", key = publicOnly)
        val notJson = directory.resolve("not.json").also { Files.writeString(it, "-----BEGIN PRIVATE KEY-----") }
        val garbage = call("GET", "/api/short-links", key = notJson)

        assertThat(missing.exitCode).isEqualTo(1)
        assertThat(missing.stderr).contains("DpopClient: could not read the key file")
        assertThat(noPrivatePart.exitCode).isEqualTo(1)
        assertThat(noPrivatePart.stderr).contains("is not a private P-256 JWK")
        assertThat(garbage.exitCode).isEqualTo(1)
        assertThat(garbage.stderr).contains("DpopClient: not JSON")
        assertThat(listOf(missing, noPrivatePart, garbage).map { it.stderr }).noneMatch { it.contains("\tat ") }
        assertThat(tokenRequests).isEmpty()
    }

    // ---- the JDK 17 baseline ----------------------------------------------------------------------------------------

    @Test
    fun `compiles for JDK 17 with every lint on and no warning`() {
        val problems = compileForJdk17(directory.resolve("classes"))

        assertThat(problems).isEmpty()
        val classFile = Files.readAllBytes(directory.resolve("classes/DpopClient.class"))
        assertThat(ByteBuffer.wrap(classFile).getShort(6).toInt()).describedAs("class file major version: 61 is Java 17").isEqualTo(61)
    }

    @Test
    fun `pads and trims coordinates to the 32 bytes a JWK has`() {
        val client = compiled.load("DpopClient")
        fun decoded(value: BigInteger) = Base64.getUrlDecoder().decode(client.invoke("coordinate", value) as String)

        val cases = mapOf(
            "one" to BigInteger.ONE,
            "a high bit set, so BigInteger adds a sign byte" to BigInteger.TWO.pow(255),
            "three leading zero bytes" to BigInteger(1, ByteArray(32) { if (it < 3) 0 else 7 }),
            "all ones" to BigInteger.TWO.pow(256).subtract(BigInteger.ONE),
        )
        cases.forEach { (name, value) ->
            assertThat(decoded(value)).describedAs(name).hasSize(32)
            assertThat(BigInteger(1, decoded(value))).describedAs(name).isEqualTo(value)
        }
    }

    @Test
    fun `reads and writes the JSON of keys and tokens, and says when it is not JSON`() {
        val json = compiled.load("DpopClient\$Json")

        val written = json.invoke("write", linkedMapOf("a" to "q\"\\\n\u0001é", "n" to 5L, "list" to listOf(true, null)))
        assertThat(written).isEqualTo("""{"a":"q\"\\\n\u0001é","n":5,"list":[true,null]}""")

        val parsed = json.invoke("parse", """ { "s" : "a\"bé\\" , "n" : -1.5e2, "nested": {"x": [1, {"y": null}]}, "t": true } """)!!
        assertThat(parsed.invokeMethod("string", "s")).isEqualTo("a\"bé\\")
        assertThat(parsed.invokeMethod("string", "n")).describedAs("a number is not a string").isNull()
        assertThat(parsed.invokeMethod("string", "missing")).isNull()

        listOf("<html>sorry</html>", """{"a":1} x""", """{"a":"unterminated}""", """{"a" 1}""", "", "{,}").forEach { text ->
            assertThatThrownBy { json.invoke("parse", text) }.describedAs(text).hasMessageStartingWith("not JSON")
        }
        assertThatThrownBy { json.invoke("parse", "[1]") }.hasMessageStartingWith("expected a JSON object")
    }

    @Test
    fun `finds the action of the login form whatever order its attributes come in`() {
        val client = compiled.load("DpopClient")
        fun action(html: String) = (client.invoke("loginFormAction", html) as Optional<*>).orElse(null)

        assertThat(action("""<form id="kc-form-login" onsubmit="x" action="/a?b=1&amp;c=2" method="post">""")).isEqualTo("/a?b=1&c=2")
        assertThat(action("""<FORM method="post" action="/b" id="kc-form-login">""")).isEqualTo("/b")
        assertThat(action("""<form id="other" action="/c"><form id="kc-form-login"
            action="/d">""")).describedAs("not the first form, and a tag over two lines").isEqualTo("/d")
        assertThat(action("""<form id="other" action="/c">""")).isNull()
        assertThat(action("no form here")).isNull()
    }

    // ---- the compiled class, for what has no other way to be called -------------------------------------------------

    private class Compiled(val directory: Path) {
        private val loader = URLClassLoader(arrayOf(directory.toUri().toURL()), DpopClientTest::class.java.classLoader)

        fun load(name: String): Loaded = Loaded(Class.forName(name, true, loader))
    }

    private class Loaded(val type: Class<*>) {
        fun invoke(name: String, vararg arguments: Any?): Any? = invokeOn(null, name, *arguments)

        fun invokeOn(target: Any?, name: String, vararg arguments: Any?): Any? {
            val method = type.declaredMethods.single { it.name == name && it.parameterCount == arguments.size }
            method.isAccessible = true
            try {
                return method.invoke(target, *arguments)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    private fun Any.invokeMethod(name: String, vararg arguments: Any?): Any? = Loaded(javaClass).invokeOn(this, name, *arguments)

    private val compiled: Compiled by lazy {
        val classes = Files.createDirectories(Path.of("build/tmp/dpop-client-classes"))
        val problems = compileForJdk17(classes)
        check(problems.isEmpty()) { "DpopClient.java does not compile for JDK 17:\n" + problems.joinToString("\n") }
        Compiled(classes)
    }

    /** The problems, errors and warnings alike, of compiling the client for Java 17, as the baseline requires. */
    private fun compileForJdk17(output: Path): List<String> {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("the tests need a JDK, not a JRE")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, null).use { files ->
            Files.createDirectories(output)
            // No annotation processing: the classpath of the tests holds processors, which have nothing to do with this file.
            val options = listOf("--release", "17", "-proc:none", "-Xlint:all", "-Werror", "-d", output.toString())
            val sources = files.getJavaFileObjects(Path.of("deploy/keycloak/DpopClient.java"))
            compiler.getTask(null, files, diagnostics, options, null, sources).call()
        }
        return diagnostics.diagnostics
            .filter { it.kind != Diagnostic.Kind.NOTE && it.kind != Diagnostic.Kind.OTHER }
            .map { "${it.kind} line ${it.lineNumber}: ${it.getMessage(null)}" }
    }
}
