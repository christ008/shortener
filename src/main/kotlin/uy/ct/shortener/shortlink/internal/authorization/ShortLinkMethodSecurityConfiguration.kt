package uy.ct.shortener.shortlink.internal.authorization

import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.context.annotation.Role
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler

/**
 * Registers the `@scopes` bean and the `hasPermission` rule of [ShortLinkPermissionEvaluator] for method security.
 * The handler is a static infrastructure bean.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(AuthorizationRuntimeHints::class)
class ShortLinkMethodSecurityConfiguration {

    companion object {
        @Bean
        @JvmStatic
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun methodSecurityExpressionHandler(context: ApplicationContext): MethodSecurityExpressionHandler =
            DefaultMethodSecurityExpressionHandler().apply {
                setApplicationContext(context)
                setPermissionEvaluator(ShortLinkPermissionEvaluator(context.getBeanProvider(ShortLinkScopes::class.java)))
            }
    }
}
