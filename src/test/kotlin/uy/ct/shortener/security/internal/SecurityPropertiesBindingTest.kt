package uy.ct.shortener.security.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * Pins the environment variable names the Kubernetes manifests use. For a fixed property such as
 * `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`, Spring accepts the dashes either
 * dropped (`..._JWKSETURI`, which the manifests use) or as underscores (`..._JWK_SET_URI`).
 */
class SecurityPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(SecurityProperties::class)
    class Properties

    private fun environment(vararg variables: Pair<String, String>) =
        SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables.toMap())

    private fun bindSecurity(vararg variables: Pair<String, String>): SecurityProperties {
        var bound: SecurityProperties? = null
        ApplicationContextRunner()
            .withUserConfiguration(Properties::class.java)
            .withInitializer { it.environment.propertySources.addFirst(environment(*variables)) }
            .run { bound = it.getBean(SecurityProperties::class.java) }
        return bound!!
    }

    private fun bindResourceServer(vararg variables: Pair<String, String>): OAuth2ResourceServerProperties.Jwt =
        Binder.get(StandardEnvironment().apply { propertySources.addFirst(environment(*variables)) })
            .bind("spring.security.oauth2.resourceserver", OAuth2ResourceServerProperties::class.java)
            .orElseGet(::OAuth2ResourceServerProperties).jwt

    @Test
    fun `binds the identity provider settings from the environment variable names the manifests use`() {
        val jwt = bindResourceServer(
            "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI" to "https://idp.example.com/realms/shortener",
            "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI" to "http://idp:8080/certs",
            "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES" to "shortener-api",
        )

        assertThat(jwt.issuerUri).isEqualTo("https://idp.example.com/realms/shortener")
        assertThat(jwt.jwkSetUri).isEqualTo("http://idp:8080/certs")
        assertThat(jwt.audiences).containsExactly("shortener-api")
    }

    @Test
    fun `also binds the underscore spelling of the property names`() {
        val jwt = bindResourceServer("SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI" to "http://idp:8080/certs")

        assertThat(jwt.jwkSetUri).isEqualTo("http://idp:8080/certs")
    }

    @Test
    fun `binds the client claim and rate limits, keeping defaults for the rest`() {
        val properties = bindSecurity(
            "SHORTENER_SECURITY_CLIENTIDCLAIM" to "client_id",
            "SHORTENER_SECURITY_RATELIMIT_PERCLIENT_CAPACITY" to "5",
        )

        assertThat(properties.clientIdClaim).isEqualTo("client_id")
        assertThat(properties.rateLimit.perClient.capacity).isEqualTo(5)
        assertThat(properties.rateLimit.perClient.period.toMinutes()).isEqualTo(1)
        assertThat(properties.rateLimit.perIp.capacity).isEqualTo(300)
    }

    @Test
    fun `requires sender-constrained tokens of type at+jwt by default, and binds changes from the environment`() {
        val defaults = bindSecurity()
        val relaxed = bindSecurity("SHORTENER_SECURITY_DPOP_REQUIRED" to "false", "SHORTENER_SECURITY_ACCESSTOKENTYPE" to "")

        assertThat(defaults.dpop.required).isTrue
        assertThat(defaults.accessTokenType).isEqualTo("at+jwt")
        assertThat(relaxed.dpop.required).isFalse
        assertThat(relaxed.accessTokenType).isEmpty()
    }
}
