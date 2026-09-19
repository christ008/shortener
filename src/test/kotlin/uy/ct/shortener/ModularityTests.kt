package uy.ct.shortener

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTests {

    val modules: ApplicationModules = ApplicationModules.of(ShortenerApplication::class.java)

    @Test
    fun verifiesModularStructure() {
        modules.verify()
    }

}
