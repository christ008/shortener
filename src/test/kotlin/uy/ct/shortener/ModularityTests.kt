package uy.ct.shortener

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

/**
 * Verifies the Spring Modulith module structure: no access to another module's `internal`
 * package and no dependency cycles.
 */
class ModularityTests {

    val modules: ApplicationModules = ApplicationModules.of(ShortenerApplication::class.java)

    @Test
    fun verifiesModularStructure() {
        modules.verify()
    }

}
