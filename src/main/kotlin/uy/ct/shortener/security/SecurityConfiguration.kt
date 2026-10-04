package uy.ct.shortener.security

import jakarta.servlet.DispatcherType
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy
import uy.ct.shortener.security.internal.CaffeineRuntimeHints
import uy.ct.shortener.security.internal.ClientJwtAuthenticationConverter
import uy.ct.shortener.security.internal.ProblemDetailResponder
import uy.ct.shortener.security.internal.RateLimitFilter
import uy.ct.shortener.security.internal.RateLimiter
import uy.ct.shortener.security.internal.SecurityProperties

/**
 * The application's security filter chain: an OAuth2 resource server, stateless and deny by
 * default.
 *
 * Everything under the `/api` path requires a bearer access token, issued to a client by the identity
 * provider and validated locally against its published keys (signature, issuer, audience and
 * expiry; see `spring.security.oauth2.resourceserver.jwt.*`). Which scopes an operation needs is
 * not decided here but declared on the operations themselves with method security, which this
 * enables. Following a short link and the health probes are public, and every other request is
 * denied. Requests are rate limited per client IP and, once authenticated, per client. Every
 * response carries restrictive security headers, and authentication, authorization and rate-limit
 * failures are problem details.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties::class)
@ImportRuntimeHints(CaffeineRuntimeHints::class)
class SecurityConfiguration {

    @Bean
    fun securityFilterChain(http: HttpSecurity, properties: SecurityProperties): SecurityFilterChain {
        val responder = ProblemDetailResponder()
        val ipLimit = RateLimitFilter("ip", RateLimiter(properties.rateLimit.perIp), responder) { it.remoteAddr }
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
                    .authenticationEntryPoint(responder)
                    .accessDeniedHandler(responder)
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
        return http.build()
    }

    private fun authenticatedClient(): String? =
        SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
            ?.name
}