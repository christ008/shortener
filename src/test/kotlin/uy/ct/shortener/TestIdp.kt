package uy.ct.shortener

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.ContextConfiguration
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * A stand-in identity provider for tests. It publishes the public half of a freshly generated
 * RSA key at a JWKS endpoint, so the application's real token validation runs against it, and
 * signs access tokens with the private half. [token] can produce valid tokens and each kind of
 * invalid one: another signing key, the wrong issuer or audience, an expired token, or one
 * missing its client or scope, with the wrong JOSE type, or bound to a client key by thumbprint
 * ([jkt]). The `owner` claim names who owns what the token creates: the client, as for a service, or
 * a user when [owner] and [authorizedParty] are given separately, as for the web UI. The JDK HTTP server behind it uses a non-daemon thread, so [stop]
 * must run when the test session ends or the test JVM never exits; [TestIdpShutdown] does that.
 */
object TestIdp {
    const val ISSUER = "http://idp.test/realms/shortener"
    const val AUDIENCE = "shortener-api"
    const val CLIENT = "test-client"
    const val ALL_CLIENT_SCOPES = "shortlinks:create shortlinks:claim shortlinks:read shortlinks:delete"

    private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("test-signing-key").generate()

    val unpublishedKey: RSAKey = RSAKeyGenerator(2048).keyID("test-signing-key").generate()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        val jwks = JWKSet(signingKey.toPublicJWK()).toString().toByteArray()
        createContext("/jwks") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, jwks.size.toLong())
            exchange.responseBody.use { it.write(jwks) }
        }
        start()
    }

    val jwksUri: String get() = "http://localhost:${server.address.port}/jwks"

    fun stop() = server.stop(0)

    fun token(
        client: String? = CLIENT,
        scope: String? = ALL_CLIENT_SCOPES,
        audience: String = AUDIENCE,
        issuer: String = ISSUER,
        expiresIn: Duration = Duration.ofMinutes(5),
        key: RSAKey = signingKey,
        type: String? = "at+jwt",
        jkt: String? = null,
        owner: String? = client,
        authorizedParty: String? = client,
    ): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject("service-account-${client ?: "unknown"}")
            .audience(audience)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(expiresIn)))
            .apply {
                authorizedParty?.let { claim("azp", it) }
                owner?.let { claim("owner", it) }
                scope?.let { claim("scope", it) }
                jkt?.let { claim("cnf", mapOf("jkt" to it)) }
            }
            .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).type(type?.let(::JOSEObjectType)).keyID(key.keyID).build(), claims)
            .apply { sign(RSASSASigner(key)) }
            .serialize()
    }
}

/** Stops [TestIdp] when the whole JUnit session is done, registered through `META-INF/services`. */
class TestIdpShutdown : LauncherSessionListener {
    override fun launcherSessionClosed(session: LauncherSession) = TestIdp.stop()
}

class TestIdpInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        TestPropertyValues.of(
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=${TestIdp.jwksUri}",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=${TestIdp.ISSUER}",
            "spring.security.oauth2.resourceserver.jwt.audiences=${TestIdp.AUDIENCE}",
        ).applyTo(context)
    }
}

/** Points the application's token validation at [TestIdp]. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ContextConfiguration(initializers = [TestIdpInitializer::class])
annotation class WithTestIdp
