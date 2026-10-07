package uy.ct.shortener

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * A client that holds its own key and proves possession of it, as RFC 9449 describes. [token]
 * asks [TestIdp] for an access token bound to the key's thumbprint, and [proof] builds the DPoP
 * header value for one request, with each claim overridable so that every way a proof can be wrong
 * can be produced: the wrong method, URL, token hash, age or key, a repeated identifier, or a
 * wrong JOSE type. A server nonce goes in the `nonce` claim.
 */
class TestDpopClient(private val key: ECKey = ECKeyGenerator(Curve.P_256).generate()) {

    val thumbprint: String = key.computeThumbprint().toString()

    fun token(client: String = TestIdp.CLIENT, scope: String? = TestIdp.ALL_CLIENT_SCOPES): String =
        TestIdp.token(client = client, scope = scope, jkt = thumbprint)

    fun proof(
        method: String,
        url: String,
        accessToken: String? = null,
        jti: String = UUID.randomUUID().toString(),
        issuedAt: Instant = Instant.now(),
        signWith: ECKey = key,
        type: String = "dpop+jwt",
        athOverride: String? = null,
        nonce: String? = null,
    ): String {
        val claims = JWTClaimsSet.Builder()
            .jwtID(jti)
            .claim("htm", method)
            .claim("htu", URI.create(url).let { URI(it.scheme, it.authority, it.path, null, null).toString() })
            .issueTime(Date.from(issuedAt))
            .apply { (athOverride ?: accessToken?.let(::hash))?.let { claim("ath", it) } }
            .apply { nonce?.let { claim("nonce", it) } }
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType(type)).jwk(signWith.toPublicJWK()).build()
        return SignedJWT(header, claims).apply { sign(ECDSASigner(signWith)) }.serialize()
    }

    private fun hash(accessToken: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray(Charsets.US_ASCII)))
}
