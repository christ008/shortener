package uy.ct.shortener.shortlink.internal.authorization

import org.springframework.security.access.prepost.PreAuthorize

/**
 * Who may call each operation of the short link service, as Spring method-security
 * meta-annotations so the rules read as intent on the service. They check the scope an operation
 * needs (named by the `scopes` bean) and that the name the caller acts under is its own, so a
 * client cannot create or disable links in another client's name.
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

/** A client lists only its own links; an administrator may list those of any client, or all. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@PreAuthorize("hasAnyAuthority(@scopes.read, @scopes.admin) and (#createdBy == authentication.name or hasAuthority(@scopes.admin))")
annotation class MayList

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@PreAuthorize("hasAnyAuthority(@scopes.delete, @scopes.admin) and #disabledBy == authentication.name")
annotation class MayDisable
