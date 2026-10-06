package uy.ct.shortener.security

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.DispatcherType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtTypeValidator
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AuthenticationFilter
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy
import org.springframework.web.servlet.HandlerExceptionResolver
import uy.ct.shortener.security.internal.CaffeineRuntimeHints
import uy.ct.shortener.security.internal.ClientJwtAuthenticationConverter
import uy.ct.shortener.security.internal.DpopRuntimeHints
import uy.ct.shortener.security.internal.RateLimitFilter
import uy.ct.shortener.security.internal.RateLimitKey
import uy.ct.shortener.security.internal.RateLimiter
import uy.ct.shortener.security.internal.SecurityEvents
import uy.ct.shortener.security.internal.SecurityProblemResponder
import uy.ct.shortener.security.internal.SecurityProperties
import uy.ct.shortener.security.internal.SenderConstrainedBearerTokenResolver

/**
 * The security filter chain: an OAuth2 resource server, stateless and deny by default.
 *
 * - Everything under `/api` needs an access token from the identity provider, validated locally against its
 *   published keys (signature, JOSE type, issuer, audience, expiry).
 * - Tokens must be bound to the client's key and sent with a DPoP proof (RFC 9449), unless
 *   `shortener.security.dpop.required` is off, which also accepts plain bearer tokens.
 * - The scopes an operation needs are declared on the operation with method security, enabled here.
 * - Following a short link and the health probes are public. Every other request is denied.
 * - Requests are rate limited per client IP, then per authenticated client.
 * - Responses carry restrictive security headers. Authentication, authorization and rate-limit
 *   failures are problem details, and each is also an event: a log line and a counter ([SecurityEvents]).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties::class)
@ImportRuntimeHints(CaffeineRuntimeHints::class, DpopRuntimeHints::class)
class SecurityConfiguration {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        properties: SecurityProperties,
        idp: OAuth2ResourceServerProperties,
        @Qualifier("handlerExceptionResolver") exceptionResolver: HandlerExceptionResolver,
        meters: MeterRegistry,
    ): SecurityFilterChain {
        val responder = SecurityProblemResponder(exceptionResolver, properties.dpop.required, SecurityEvents(meters))
        val ipLimit = RateLimitFilter("ip", RateLimiter(properties.rateLimit.perIp), responder) { RateLimitKey.Of(it.remoteAddr) }
        val clientLimit = RateLimitFilter("client", RateLimiter(properties.rateLimit.perClient), responder) { authenticatedClient() }

        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }
            .headers { headers ->
                headers.contentSecurityPolicy { it.policyDirectives("default-src 'none'; frame-ancestors 'none'") }
                headers.referrerPolicy { it.policy(ReferrerPolicy.NO_REFERRER) }
            }
            .exceptionHandling { it.authenticationEntryPoint(responder).accessDeniedHandler(responder) }
            .oauth2ResourceServer { resourceServer ->
                resourceServer
                    .jwt { it.jwtAuthenticationConverter(ClientJwtAuthenticationConverter(properties.clientIdClaim)) }
                    .dPoP { it.authenticationFailureHandler { request, response, failure -> responder.commence(request, response, failure) } }
                    .protectedResourceMetadata { metadata ->
                        metadata.protectedResourceMetadataCustomizer { builder ->
                            idp.jwt.issuerUri?.let(builder::authorizationServer)
                            builder.tlsClientCertificateBoundAccessTokens(false)
                            builder.claim("dpop_bound_access_tokens_required", properties.dpop.required)
                            builder.claim("dpop_signing_alg_values_supported", DPOP_ALGORITHMS)
                        }
                    }
                    .authenticationEntryPoint(responder)
                    .accessDeniedHandler(responder)
                if (properties.dpop.required) resourceServer.bearerTokenResolver(SenderConstrainedBearerTokenResolver())
            }
            .authorizeHttpRequests { requests ->
                requests
                    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(HttpMethod.GET, "/{shortCode}").permitAll()
                    .requestMatchers(HttpMethod.HEAD, "/{shortCode}").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().denyAll()
            }
            .addFilterBefore(ipLimit, BearerTokenAuthenticationFilter::class.java)
            .addFilterAfter(clientLimit, BearerTokenAuthenticationFilter::class.java)
        LoggerFactory.getLogger(SecurityConfiguration::class.java).info(
            "Security configured: dpop.required={}, rate limit per ip={} and per client={} a minute, access tokens of type '{}'",
            properties.dpop.required, properties.rateLimit.perIp.capacity, properties.rateLimit.perClient.capacity, properties.accessTokenType,
        )
        val chain = http.build()
        check(!properties.dpop.required || chain.filters.any { it is AuthenticationFilter }) {
            "DPoP is required but its authentication filter is not in the security chain. " +
                "In a native image the DPoP classes must be registered, see DpopRuntimeHints."
        }
        return chain
    }

    @Bean
    fun accessTokenType(properties: SecurityProperties): OAuth2TokenValidator<Jwt> =
        if (properties.accessTokenType.isBlank()) {
            JwtTypeValidator.jwt()
        } else {
            JwtTypeValidator(properties.accessTokenType, "application/${properties.accessTokenType}")
        }

    private companion object {
        val DPOP_ALGORITHMS = listOf("RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512")
    }

    private fun authenticatedClient(): RateLimitKey =
        SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
            ?.let { RateLimitKey.Of(it.name) }
            ?: RateLimitKey.Unlimited
}
