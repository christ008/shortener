package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.yaml.snakeyaml.Yaml
import uy.ct.shortener.shortlink.internal.authorization.ShortLinkScopes
import java.io.File

/**
 * `docs/openapi.yaml` is written by hand, so this test keeps it honest: it documents exactly the
 * operations the controllers serve, names exactly the scopes the application is configured with, and
 * carries the version being released. Describing a route that does not exist, or forgetting one that
 * does, fails the build.
 */
@WithTestIdp
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class OpenApiContractTest {

    @Autowired
    lateinit var handlerMapping: RequestMappingHandlerMapping

    @Autowired
    lateinit var scopes: ShortLinkScopes

    private val spec: Map<String, Any?> = Yaml().load(File("docs/openapi.yaml").readText())

    @Suppress("UNCHECKED_CAST")
    private fun section(vararg path: String): Map<String, Any?> =
        path.fold(spec) { node, key -> node[key] as Map<String, Any?> }

    @Test
    fun `documents exactly the operations the application serves`() {
        val methods = setOf("get", "post", "put", "patch", "delete")
        val documented = section("paths").flatMap { (path, item) ->
            @Suppress("UNCHECKED_CAST")
            (item as Map<String, Any?>).keys.filter { it in methods }.map { "${it.uppercase()} $path" }
        }.toSet()

        val served = handlerMapping.handlerMethods.flatMap { (info, handler) ->
            if (!handler.beanType.name.startsWith("uy.ct.shortener")) return@flatMap emptyList()
            val paths = info.pathPatternsCondition?.patternValues.orEmpty()
            info.methodsCondition.methods.flatMap { method -> paths.map { "${method.name} $it" } }
        }.toSet()

        assertThat(documented).isEqualTo(served)
    }

    @Test
    fun `names the scopes the application is configured with`() {
        val flows = section("components", "securitySchemes", "dpop", "flows", "clientCredentials", "scopes")

        assertThat(flows.keys).containsExactlyInAnyOrder(scopes.create, scopes.claim, scopes.read, scopes.delete, scopes.admin)
    }

    @Test
    fun `carries the version of the release`() {
        assertThat(section("info")["version"]).isEqualTo(System.getProperty("project.version"))
    }
}
