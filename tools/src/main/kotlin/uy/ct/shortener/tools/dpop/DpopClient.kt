package uy.ct.shortener.tools.dpop

import tools.jackson.databind.json.JsonMapper
import uy.ct.shortener.tools.ClientKeys
import uy.ct.shortener.tools.DpopCalls
import uy.ct.shortener.tools.Failure
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.regex.Pattern
import kotlin.system.exitProcess

/**
 * The reference client of the Kotlin side: the same commands, arguments and output as `deploy/keycloak/dpop/DpopClient.java`, which
 * the scripts run. `keygen`, `token` and `call` are [DpopCalls] and [ClientKeys]; `register` and `login` are here.
 *
 * - Run it with `./gradlew dpopJar` and `java -jar tools/build/libs/dpop.jar`. It needs the JRE of the tools' build (JDK 25).
 * - Errors print `DpopClient: ` and the reason, as the Java client does, and exit 1; a bad line prints the usage and exits 2.
 * - `DPOP_DEBUG` prints the stack trace of an error.
 * - The suite that tests the Java client runs this one too, so the two cannot drift apart.
 */
fun main(arguments: Array<String>) {
    val command = parse(arguments) ?: run {
        System.err.println(USAGE)
        exitProcess(2)
    }
    try {
        DpopClient().run(command)
    } catch (failure: Failure) {
        report(failure.message.orEmpty(), failure)
        exitProcess(1)
    } catch (refused: Refused) {
        report(refused.message.orEmpty(), refused)
        exitProcess(1)
    } catch (unexpected: Exception) {
        report(unexpected.toString(), unexpected)
        exitProcess(1)
    }
}

private fun report(message: String, error: Throwable) {
    System.err.println("DpopClient: $message")
    if (System.getenv("DPOP_DEBUG") != null) error.printStackTrace()
}

private const val USAGE = """usage: java deploy/keycloak/dpop/DpopClient.java keygen CLIENT_ID
       java deploy/keycloak/dpop/DpopClient.java register NAME INVITATION KEY_FILE
       java deploy/keycloak/dpop/DpopClient.java token KEY_FILE CLIENT_ID
       java deploy/keycloak/dpop/DpopClient.java call KEY_FILE CLIENT_ID METHOD URL [JSON_BODY]
       java deploy/keycloak/dpop/DpopClient.java login USER PASSWORD METHOD URL [JSON_BODY]"""

private sealed interface Command {
    data class Keygen(val clientId: String) : Command

    data class Register(val name: String, val invitation: String, val keyFile: Path) : Command

    data class FetchToken(val keyFile: Path, val clientId: String) : Command

    data class Call(val keyFile: Path, val clientId: String, val method: String, val url: String, val body: String?) : Command

    data class Login(val user: String, val password: String, val method: String, val url: String, val body: String?) : Command
}

/** The command the arguments make, or nothing when they make none. */
private fun parse(args: Array<String>): Command? {
    val count = args.size
    return when {
        count == 2 && args[0] == "keygen" -> Command.Keygen(args[1])
        count == 4 && args[0] == "register" -> Command.Register(args[1], args[2], Path.of(args[3]))
        count == 3 && args[0] == "token" -> Command.FetchToken(Path.of(args[1]), args[2])
        (count == 5 || count == 6) && args[0] == "call" -> Command.Call(Path.of(args[1]), args[2], args[3], args[4], args.getOrNull(5))
        (count == 5 || count == 6) && args[0] == "login" -> Command.Login(args[1], args[2], args[3], args[4], args.getOrNull(5))
        else -> null
    }
}

/** A token endpoint that answered something other than 200: the sign-in is refused. */
private class Refused(status: Int, body: String) : RuntimeException("the token endpoint answered $status: $body")

private const val TOKEN_PATH = "/protocol/openid-connect/token"
private const val CLIENT_ID = "client_id"
private const val GRANT_TYPE = "grant_type"
private const val ACCESS_TOKEN = "access_token"
private const val REFRESH_TOKEN = "refresh_token"
private const val ERROR = "error"
private const val ES256 = "ES256"
private const val CONTENT_TYPE = "Content-Type"
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
private val TIMEOUT: Duration = Duration.ofSeconds(30)
private val JSON = JsonMapper.builder().build()
private val BASE64 = Base64.getUrlEncoder().withoutPadding()
private val BASE64_DECODER = Base64.getUrlDecoder()
private val RANDOM = SecureRandom()
private val FORM_TAG: Pattern = Pattern.compile("<form\\b[^>]*>", Pattern.CASE_INSENSITIVE)
private val ACTION: Pattern = Pattern.compile("\\baction=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)

private class DpopClient {
    private val tokenUrl = env("TOKEN_URL", DpopCalls.DEFAULT_TOKEN_URL)
    private val issuer = env("ISSUER", tokenUrl.removeSuffix(TOKEN_PATH).takeIf { tokenUrl.endsWith(TOKEN_PATH) } ?: tokenUrl)

    // One client for every request, so that the nonce of a server is kept for the requests that follow.
    private val calls = DpopCalls(tokenUrl, issuer)

    fun run(command: Command) {
        when (command) {
            is Command.Keygen -> {
                val key = ClientKeys.generate(command.clientId)
                println(key.privateJwk)
                println(key.publicJwk)
            }

            is Command.Register -> register(command.name, command.invitation, command.keyFile)

            is Command.FetchToken -> println(calls.token(command.keyFile, command.clientId))

            is Command.Call -> printCall(calls.call(command.keyFile, command.clientId, command.method, command.url, command.body))

            is Command.Login -> login(command.user, command.password, command.method, command.url, command.body)
        }
    }

    private fun printCall(answer: DpopCalls.Answer) {
        println(answer.status)
        answer.location?.let { println("Location: $it") }
        answer.challenges.forEach { println("WWW-Authenticate: $it") }
        println(answer.body)
    }

    /** Makes a client key and registers it with the invitation of an administrator. The key file is written first. */
    private fun register(name: String, invitation: String, keyFile: Path) {
        if (Files.exists(keyFile)) throw Failure("$keyFile exists already: it is not overwritten, name another file")
        val key = ClientKeys.generate(name)
        try {
            Files.writeString(keyFile, key.privateJwk + "\n", StandardOpenOption.CREATE_NEW)
            restrictToOwner(keyFile)
        } catch (problem: IOException) {
            throw Failure("could not write the key file $keyFile: ${problem.message}")
        }
        val metadata = linkedMapOf<String, Any>(
            "client_name" to name,
            "token_endpoint_auth_method" to "private_key_jwt",
            "token_endpoint_auth_signing_alg" to ES256,
            "grant_types" to listOf("client_credentials"),
            "jwks" to mapOf("keys" to listOf(JSON.readTree(key.publicJwk))),
        )
        val endpoint = "$issuer/clients-registrations/openid-connect"
        val response = calls.send(
            HttpRequest.newBuilder(uri(endpoint)).timeout(TIMEOUT)
                .header("Authorization", "Bearer $invitation").header(CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(metadata))).build(),
        )
        if (response.statusCode() != 201) {
            try {
                Files.deleteIfExists(keyFile)
            } catch (leftOver: IOException) {
                // the registration failed and so the key means nothing; a leftover file is harmless
            }
            throw Failure("the registration was refused (${response.statusCode()}): ${excerpt(response.body(), 300)}")
        }
        val clientId = JSON.readTree(response.body()).path(CLIENT_ID).stringValue(null)
            ?: throw Failure("the identity provider registered the client but did not say its id: ${excerpt(response.body(), 300)}")
        println("registered: $clientId")
        println("private key: $keyFile (keep it: it is the only way to be this client, and it cannot be recovered)")
        println("try it:  java deploy/keycloak/dpop/DpopClient.java call $keyFile $clientId GET <API URL>/api/short-links")
    }

    /**
     * The sign-in of a single-page app, step by step, each printed so that what the identity provider does with the key can
     * be read: the code is bound to the key, the token is bound to it, a refresh keeps it, and the old refresh token is (or is
     * not) refused.
     */
    private fun login(user: String, password: String, method: String, url: String, body: String?) {
        val clientId = env("CLIENT_ID", "shortener-ui")
        val redirect = env("REDIRECT_URI", "http://localhost:3000/app/auth/callback")
        val scope = env("SCOPE", "shortlinks:create shortlinks:claim shortlinks:read shortlinks:delete")

        val key = ClientKeys.newKeyPair()
        val jkt = thumbprint(key)
        val verifier = BASE64.encodeToString(randomBytes(32))
        val challenge = BASE64.encodeToString(sha256(verifier))
        val state = UUID.randomUUID().toString()

        val code = BrowserLogin(issuer).signIn(user, password, BrowserLogin.Request(clientId, redirect, scope, state, challenge, jkt))

        val tokens = JSON.readTree(
            postToken({ form(GRANT_TYPE to "authorization_code", CLIENT_ID to clientId, "code" to code, "redirect_uri" to redirect, "code_verifier" to verifier) }, key),
        )
        val accessToken = tokens.path(ACCESS_TOKEN).stringValue(null)
        val refreshToken = tokens.path(REFRESH_TOKEN).stringValue(null)
        if (accessToken == null) throw Failure("the token endpoint did not return an access_token: $tokens")
        println("2. token_type ${tokens.path("token_type").stringValue(null)}, refresh token ${if (refreshToken == null) "NOT issued" else "issued"}")
        println("3. access token header ${jwtPart(accessToken, 0)}")
        println("   claims ${jwtPart(accessToken, 1)}")
        println("   bound to this client's key: ${jkt == confirmationOf(accessToken)}")

        var latestAccess = accessToken
        if (refreshToken != null) {
            val refreshed = JSON.readTree(postToken({ refreshForm(clientId, refreshToken) }, key))
            latestAccess = refreshed.path(ACCESS_TOKEN).stringValue(null)
                ?: throw Failure("refreshing did not return an access_token: $refreshed")
            val rotated = refreshed.path(REFRESH_TOKEN).stringValue(null)
            println(
                "4. refreshed with the same key; new token bound to it: ${jkt == confirmationOf(latestAccess)}, " +
                    "refresh token rotated: ${rotated != null && refreshToken != rotated}",
            )
            val reused = accepts({ refreshForm(clientId, refreshToken) }, key)
            println("   reusing the old refresh token: " + if (reused) "ACCEPTED (rotation is not enforced)" else "refused, as rotation requires")
            val refreshWith = rotated ?: refreshToken
            val differentKey = accepts({ refreshForm(clientId, refreshWith) }, ClientKeys.newKeyPair())
            println("   refreshing with a different key: " + if (differentKey) "ACCEPTED (the refresh token is not bound to the key)" else "refused")
        }

        val accessToUse = latestAccess
        val response = calls.sendProved(url) { nonce -> apiRequest(method, url, body, accessToUse, key, nonce) }
        println("5. $method $url -> ${response.statusCode()}")
        response.headers().allValues("WWW-Authenticate").forEach { println("   WWW-Authenticate: $it") }
        println("   ${response.body()}")
    }

    /** Posts a form to the token endpoint with a proof of [key], and returns the body of the 200 it must answer. */
    private fun postToken(form: () -> String, key: KeyPair): String {
        val response = calls.sendProved(tokenUrl) { nonce ->
            HttpRequest.newBuilder(uri(tokenUrl)).timeout(TIMEOUT)
                .header(CONTENT_TYPE, "application/x-www-form-urlencoded")
                .header("DPoP", DpopCalls.proof(key, "POST", tokenUrl, null, nonce))
                .POST(HttpRequest.BodyPublishers.ofString(form())).build()
        }
        if (response.statusCode() != 200) throw Refused(response.statusCode(), response.body())
        return response.body()
    }

    private fun accepts(form: () -> String, key: KeyPair): Boolean =
        try {
            postToken(form, key)
            true
        } catch (refused: Refused) {
            false
        }

    private fun apiRequest(method: String, url: String, body: String?, accessToken: String, key: KeyPair, nonce: String?): HttpRequest {
        val request = HttpRequest.newBuilder(uri(url)).timeout(TIMEOUT)
            .header("Authorization", "DPoP $accessToken")
            .header("DPoP", DpopCalls.proof(key, method, url, accessToken, nonce))
        val hasBody = !body.isNullOrEmpty()
        if (hasBody) request.header(CONTENT_TYPE, "application/json")
        System.getenv("TRACEPARENT")?.takeIf { it.isNotBlank() }?.let { request.header("traceparent", it) }
        return request.method(method, if (hasBody) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody()).build()
    }

    private fun refreshForm(clientId: String, refreshToken: String): String =
        form(GRANT_TYPE to REFRESH_TOKEN, CLIENT_ID to clientId, REFRESH_TOKEN to refreshToken)

    private companion object {
        fun env(name: String, fallback: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

        fun form(vararg pairs: Pair<String, String>): String =
            pairs.joinToString("&") { (name, value) -> URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8) }

        fun uri(text: String): URI =
            try {
                URI.create(text)
            } catch (notAUrl: IllegalArgumentException) {
                throw Failure("not a URL: $text")
            }
    }
}

/** The RFC 7638 thumbprint of the public key: its required members, in alphabetical order, with no spaces. */
private fun thumbprint(key: KeyPair): String {
    val jwk = ClientKeys.publicJwk(key)
    val canonical = linkedMapOf<String, Any>("crv" to jwk.getValue("crv"), "kty" to jwk.getValue("kty"), "x" to jwk.getValue("x"), "y" to jwk.getValue("y"))
    return BASE64.encodeToString(sha256(JSON.writeValueAsString(canonical)))
}

/** The `jkt` of the `cnf` claim of an access token: the key it is bound to. */
private fun confirmationOf(jwt: String): String? =
    JSON.readTree(jwtPart(jwt, 1)).path("cnf").path("jkt").stringValue(null)

private fun jwtPart(jwt: String, index: Int): String {
    val parts = jwt.split(".")
    if (parts.size < 2) throw Failure("not a JWT: ${excerpt(jwt, 60)}")
    return String(BASE64_DECODER.decode(parts[index]), StandardCharsets.UTF_8)
}

private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.US_ASCII))

private fun randomBytes(count: Int): ByteArray = ByteArray(count).also { RANDOM.nextBytes(it) }

/** Leaves the key file readable by its owner alone; a file system without POSIX permissions has nothing to restrict. */
private fun restrictToOwner(keyFile: Path) {
    try {
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"))
    } catch (noPosix: UnsupportedOperationException) {
        // not a POSIX file system: nothing more can be done here
    }
}

/** The text of an HTML page or any text, on one line and cut short, for an error message. */
private fun excerpt(text: String, length: Int): String {
    val plain = text.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
    return if (plain.length <= length) plain else plain.substring(0, length) + "..."
}

/** The browser part of the login: the login page of Keycloak, the form submitted on it, and the redirect that carries the code. */
private class BrowserLogin(private val issuer: String) {
    /** The parameters of the authorization request, which the login page is asked for. */
    data class Request(val clientId: String, val redirect: String, val scope: String, val state: String, val challenge: String, val jkt: String)

    private val browser: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()

    fun signIn(user: String, password: String, request: Request): String {
        val cookies = linkedMapOf<String, String>()
        val authUrl = "$issuer/protocol/openid-connect/auth?" + form(
            "response_type" to "code", CLIENT_ID to request.clientId, "redirect_uri" to request.redirect, "scope" to request.scope,
            "state" to request.state, "code_challenge" to request.challenge, "code_challenge_method" to "S256", "dpop_jkt" to request.jkt,
        )
        val page = send(HttpRequest.newBuilder(uri(authUrl)).timeout(TIMEOUT).GET().build())
        remember(cookies, page)
        val action = loginFormAction(page.body())
            ?: throw Failure("no login form (status ${page.statusCode()}): ${excerpt(page.body(), 500)}")

        val submitted = send(
            HttpRequest.newBuilder(uri(action)).timeout(TIMEOUT)
                .header(CONTENT_TYPE, "application/x-www-form-urlencoded")
                .header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                .POST(HttpRequest.BodyPublishers.ofString(form("username" to user, "password" to password, "credentialId" to "")))
                .build(),
        )
        val location = submitted.headers().firstValue("Location").orElse(null)
            ?: throw Failure("the login did not redirect (status ${submitted.statusCode()}): ${excerpt(submitted.body(), 300)}")

        val returned = queryOf(location)
        returned[ERROR]?.let { throw Failure("the login was refused: $it ${returned["error_description"].orEmpty()}") }
        if (request.state != returned["state"]) throw Failure("state mismatch")
        val code = returned["code"] ?: throw Failure("the redirect carries no code: $location")
        val query = location.indexOf('?')
        println("1. signed in as $user; redirected to ${if (query < 0) location else location.substring(0, query)} with a code")
        return code
    }

    private fun send(request: HttpRequest): HttpResponse<String> =
        try {
            browser.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (unreachable: IOException) {
            throw Failure("could not reach ${request.uri()}: $unreachable")
        }

    private fun remember(cookies: MutableMap<String, String>, response: HttpResponse<*>) {
        for (header in response.headers().allValues("Set-Cookie")) {
            val pair = header.split(";", limit = 2)[0]
            val equals = pair.indexOf('=')
            if (equals > 0) cookies[pair.substring(0, equals).trim()] = pair.substring(equals + 1).trim()
        }
    }

    private companion object {
        /** The action of the login form of Keycloak, whatever order its attributes come in. */
        fun loginFormAction(html: String): String? {
            val forms = FORM_TAG.matcher(html)
            while (forms.find()) {
                val tag = forms.group()
                if (!tag.contains("id=\"kc-form-login\"")) continue
                val action = ACTION.matcher(tag)
                if (action.find()) return action.group(1).replace("&amp;", "&")
            }
            return null
        }

        fun queryOf(location: String): Map<String, String> {
            val parameters = linkedMapOf<String, String>()
            val query = URI.create(location).rawQuery ?: return parameters
            for (pair in query.split("&")) {
                val nameAndValue = pair.split("=", limit = 2)
                parameters[URLDecoder.decode(nameAndValue[0], StandardCharsets.UTF_8)] =
                    if (nameAndValue.size > 1) URLDecoder.decode(nameAndValue[1], StandardCharsets.UTF_8) else ""
            }
            return parameters
        }

        fun form(vararg pairs: Pair<String, String>): String =
            pairs.joinToString("&") { (name, value) -> URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8) }

        fun uri(text: String): URI =
            try {
                URI.create(text)
            } catch (notAUrl: IllegalArgumentException) {
                throw Failure("not a URL: $text")
            }
    }
}
