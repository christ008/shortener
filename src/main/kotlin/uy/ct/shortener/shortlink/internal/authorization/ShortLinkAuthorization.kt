package uy.ct.shortener.shortlink.internal.authorization

import org.springframework.security.access.prepost.PreAuthorize

/**
 * Who may call each operation of the short link service, as method-security meta-annotations, so
 * the rules read as intent on the service.
 *
 * - Each checks the scope the operation needs, named by the `scopes` bean.
 * - Creating and disabling also check that the caller acts under its own name, so a client cannot
 *   act in another client's name.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@PreAuthorize("hasAuthority(@scopes.create) and #createdBy == authentication.name")
annotation class MayCreate

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@PreAuthorize("hasAuthority(@scopes.create) and hasAuthority(@scopes.claim) and #createdBy == authentication.name")
annotation class MayClaim

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@PreAuthorize("hasAnyAuthority(@scopes.read, @scopes.admin)")
annotation class MayRead

/** A client lists only its own links; an administrator may list any client's, or all. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@PreAuthorize("hasAnyAuthority(@scopes.read, @scopes.admin) and (#filter.isLimitedTo(authentication.name) or hasAuthority(@scopes.admin))")
annotation class MayList

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@PreAuthorize("hasAnyAuthority(@scopes.delete, @scopes.admin) and #disabledBy == authentication.name")
annotation class MayDisable
