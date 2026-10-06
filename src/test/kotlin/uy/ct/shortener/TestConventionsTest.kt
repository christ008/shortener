package uy.ct.shortener

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * What the tests may not depend on. Mockito comes with the Spring Boot test starters, so nothing but this keeps it out:
 * the tests fake what the project owns and run against Testcontainers Postgres for the rest, and fakes of types the project
 * does not own (a `DataSource`, a `ResultSet`) are written by hand or replaced by the real thing.
 */
class TestConventionsTest {

    private val tests = ClassFileImporter()
        .withImportOption(ImportOption.OnlyIncludeTests())
        .importPackages("uy.ct.shortener")

    @Test
    fun `tests do not use Mockito, directly or through Spring's bean overrides`() {
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("org.mockito..", "org.springframework.test.context.bean.override.mockito..")
            .check(tests)
    }
}
