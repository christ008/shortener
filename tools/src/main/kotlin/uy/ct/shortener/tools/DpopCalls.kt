package uy.ct.shortener.tools

import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Calls the API the way a client of the project does, with a DPoP-bound token (RFC 9449), for the tools that need to be one.
 *
 * - The client authenticates to the identity provider with a signed assertion (`private_key_jwt`), and asks for a token bound to a
 *   key made for the call. The API is then called with that token and a proof made for the request.
 * - It is written for the tools and does not use `deploy/keycloak/DpopClient.java`, the reference for people outside the project,
 *   so there are two of them on purpose (see [ClientKeys]).
 * - [tokenUrl] is the token endpoint, and [issuer] is the audience of the assertion: the endpoint without its path, unless said.
 * - A call returns its answer whatever its status, because an error status is an answer. What it throws is a [Failure]: the
 *   identity provider said no, or a server was not there.
 */
class DpopCalls(
    private val tokenUrl: String = DEFAULT_TOKEN_URL,
    private val issuer: String = tokenUrl.removeSuffix(TOKEN_ENDPOINT_PATH),
) {

    /** What the API answered to a call. */
    class Answer(val status: Int, val body: String)

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

    /** Makes a call as [client], signing in each time, with a body sent as JSON when there is one. */
    fun call(keyFile: Path, client: String, method: String, url: String, body: String?): Answer {
        val key = ClientKeys.newKeyPair()
        val token = token(keyFile, client, key)
        val request = HttpRequest.newBuilder(uri(url))
            .timeout(TIMEOUT)
            .header("Authorization", "DPoP $token")
            .header("DPoP", proof(key, method, url, token))
        val hasBody = !body.isNullOrEmpty()
        if (hasBody) request.header("Content-Type", "application/json")
        val response = send(request.method(method, if (hasBody) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody()).build())
        return Answer(response.statusCode(), response.body())
    }

    /** A new access token for [client], bound to a key that nobody else has. */
    fun token(keyFile: Path, client: String): String = token(keyFile, client, ClientKeys.newKeyPair())

    private fun token(keyFile: Path, client: String, key: KeyPair): String {
        val now = now()
        val assertion = jws(
            ClientKeys.readPrivate(keyFile),
            linkedMapOf("alg" to "ES256"),
            linkedMapOf("iss" to client, "sub" to client, "aud" to issuer, "jti" to UUID.randomUUID().toString(), "iat" to now, "exp" to now + 60),
        )
        val form = form(
            "grant_type" to "client_credentials", "client_id" to client,
            "client_assertion_type" to ASSERTION_TYPE, "client_assertion" to assertion,
        )
        val request = HttpRequest.newBuilder(uri(tokenUrl))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("DPoP", proof(key, "POST", tokenUrl, null))
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = send(request)
        if (response.statusCode() != 200) throw Failure("the token endpoint answered ${response.statusCode()}: ${response.body()}")
        val answer = try {
            JSON.readTree(response.body())
        } catch (notJson: JacksonException) {
            throw Failure("the token endpoint did not answer JSON: ${notJson.originalMessage}")
        }
        return answer.path("access_token").stringValue(null) ?: throw Failure("the token endpoint did not return an access_token: ${response.body()}")
    }

    private fun send(request: HttpRequest): HttpResponse<String> =
        try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (unreachable: IOException) {
            throw Failure("could not reach ${request.uri()}: $unreachable")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw Failure("interrupted while calling ${request.uri()}")
        }

    companion object {
        const val TOKEN_ENDPOINT_PATH = "/protocol/openid-connect/token"
        const val DEFAULT_TOKEN_URL = "http://localhost:8180/realms/shortener$TOKEN_ENDPOINT_PATH"
        private const val ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
        private val CONNECT_TIMEOUT = Duration.ofSeconds(5)
        private val TIMEOUT = Duration.ofSeconds(30)
        private val JSON = JsonMapper.builder().build()
        private val BASE64 = Base64.getUrlEncoder().withoutPadding()

        /** The proof for one request: the method, the URL without its query and fragment, and a hash of the token when there is one. */
        fun proof(key: KeyPair, method: String, url: String, accessToken: String?): String {
            val target = uri(url)
            val claims = linkedMapOf<String, Any>(
                "jti" to UUID.randomUUID().toString(),
                "htm" to method,
                "htu" to "${target.scheme}://${target.rawAuthority}${target.rawPath}",
                "iat" to now(),
            )
            if (accessToken != null) claims["ath"] = BASE64.encodeToString(MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray(StandardCharsets.US_ASCII)))
            return jws(key.private, linkedMapOf("alg" to "ES256", "typ" to "dpop+jwt", "jwk" to ClientKeys.publicJwk(key)), claims)
        }

        /** A compact JWS signed with ES256, whose signature is the raw r and s of JWS and not the DER of ECDSA. */
        fun jws(key: PrivateKey, header: Map<String, Any>, claims: Map<String, Any>): String {
            val signingInput = BASE64.encodeToString(JSON.writeValueAsBytes(header)) + "." + BASE64.encodeToString(JSON.writeValueAsBytes(claims))
            try {
                val signature = Signature.getInstance("SHA256withECDSAinP1363Format")
                signature.initSign(key)
                signature.update(signingInput.toByteArray(StandardCharsets.US_ASCII))
                return signingInput + "." + BASE64.encodeToString(signature.sign())
            } catch (problem: GeneralSecurityException) {
                throw Failure("could not sign: $problem")
            }
        }

        private fun form(vararg pairs: Pair<String, String>): String =
            pairs.joinToString("&") { (name, value) -> URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8) }

        private fun uri(text: String): URI =
            try {
                URI.create(text)
            } catch (notAUrl: IllegalArgumentException) {
                throw Failure("not a URL: $text")
            }

        private fun now(): Long = System.currentTimeMillis() / 1000
    }
}
