package uy.ct.shortener.shortlink

import org.springframework.data.jpa.repository.JpaRepository

interface ShortLinkRepository : JpaRepository<ShortLink, Long> {

    fun findByShortCode(shortCode: ShortCode): ShortLink?

    /** For generate-and-retry code allocation: check before insert instead of catching the constraint violation. */
    fun existsByShortCode(shortCode: ShortCode): Boolean
}
