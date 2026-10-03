package uy.ct.shortener.security

import jakarta.servlet.DispatcherType
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy
import uy.ct.shortener.security.internal.ApiKeyAuthenticator
import uy.ct.shortener.security.internal.BearerApiKeyAuthenticationFilter
import uy.ct.shortener.security.internal.ProblemDetailResponder
import uy.ct.shortener.security.internal.RateLimitFilter
import uy.ct.shortener.security.internal.RateLimiter
import uy.ct.shortener.security.internal.SecurityProperties

/**
 * The application's security filter chain: stateless, deny by default.
 *
 * Creating a link requires an API key sent as `Authorization: Bearer <key>`. Following a short
 * link and the health probes are public, and every other request is denied. Requests are rate
 * limited per client IP and, once authenticated, per API key. Every response carries
 * restrictive security headers, and authentication, authorization and rate-limit failures are
 * problem details. With no keys configured nobody can create links, and a warning is logged.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties::class)
class SecurityConfiguration {

    @Bean
    fun securityFilterChain(http: HttpSecurity, properties: SecurityProperties): SecurityFilterChain {
        if (properties.apiKeys.isEmpty()) {
            LoggerFactory.getLogger(SecurityConfiguration::class.java)
                .warn("No API keys configured under shortener.security.api-keys: creating links is impossible")
        }
        val responder = ProblemDetailResponder()
        val authentication = BearerApiKeyAuthenticationFilter(ApiKeyAuthenticator(properties.apiKeys), responder)
        val clientLimit = RateLimitFilter("client", RateLimiter(properties.rateLimit.perClient), responder) { it.remoteAddr }
        val keyLimit = RateLimitFilter("key", RateLimiter(properties.rateLimit.perKey), responder) { authenticatedClient() }

        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }
            .headers { headers ->
                headers.contentSecurityPolicy { it.policyDirectives("default-src 'none'; frame-ancestors 'none'") }
                headers.referrerPolicy { it.policy(ReferrerPolicy.NO_REFERRER) }
            }
            .exceptionHandling { it.authenticationEntryPoint(responder).accessDeniedHandler(responder) }
            .authorizeHttpRequests { requests ->
                requests
                    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/short-links").hasRole("API_CLIENT")
                    .requestMatchers(HttpMethod.GET, "/{shortCode}").permitAll()
                    .requestMatchers(HttpMethod.HEAD, "/{shortCode}").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .anyRequest().denyAll()
            }
            .addFilterBefore(authentication, AuthorizationFilter::class.java)
            .addFilterBefore(clientLimit, BearerApiKeyAuthenticationFilter::class.java)
            .addFilterAfter(keyLimit, BearerApiKeyAuthenticationFilter::class.java)
        return http.build()
    }

    private fun authenticatedClient(): String? =
        SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
            ?.name
}
