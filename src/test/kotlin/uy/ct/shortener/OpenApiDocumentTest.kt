package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import uy.ct.shortener.shortlink.internal.web.CreateShortLinkRequest
import uy.ct.shortener.shortlink.internal.web.ShortLinkPageResponse
import uy.ct.shortener.shortlink.internal.web.ShortLinkResponse
import uy.ct.shortener.shortlink.internal.web.UpdateShortLinkRequest
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * What `docs/openapi.yaml` says about itself, read without the application, so it runs with no Docker and on every change.
 * `OpenApiContractTest` checks the document against the running routes and scopes. This one checks what the routes cannot
 * show: that the fields of a representation are the fields of the type that is sent, and that every operation documents the
 * failures its inputs and its security can cause, as problem details.
 *
 * It reads the document and the types, not the responses. A status the document lists and no test calls can still be wrong.
 */
class OpenApiDocumentTest {

    private val spec: Map<String, Any?> = Yaml().load(File("docs/openapi.yaml").readText())

    private val methods = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")

    @Suppress("UNCHECKED_CAST")
    private fun Any?.map(): Map<String, Any?> = this as Map<String, Any?>

    private fun section(vararg path: String): Map<String, Any?> = path.fold(spec) { node, key -> node[key].map() }

    private data class Operation(val method: String, val path: String, val definition: Map<String, Any?>) {
        override fun toString() = "${method.uppercase()} $path"
    }

    private val operations: List<Operation> = section("paths").flatMap { (path, item) ->
        item.map().filterKeys { it in methods }.map { (method, definition) -> Operation(method, path, definition.map()) }
    }

    private fun Operation.parameterNames(): List<String> =
        (definition["parameters"] as? List<*>).orEmpty().mapNotNull { it.map()["name"] as? String }

    /** A response, with a `$ref` into `components/responses` followed. */
    private fun response(documented: Any?): Map<String, Any?> {
        val reference = documented.map()["\$ref"] as? String ?: return documented.map()
        return section("components", "responses", reference.substringAfterLast('/'))
    }

    private fun Operation.responses(): Map<String, Any?> = definition["responses"].map()

    /** The failures an operation can cause, from what it takes in and who may call it. */
    private fun Operation.mustDocument(): Set<String> = buildSet {
        val secured = (definition["security"] as? List<*>)?.isNotEmpty() ?: false
        if (secured) addAll(listOf("401", "403"))
        if ("{shortCode}" in path || "requestBody" in definition || "sort" in parameterNames()) add("400")
        if ("{shortCode}" in path) add("404")
        addAll(listOf("429", "503"))
    }

    @Test
    fun `every operation documents the failures its inputs and its security can cause`() {
        assertThat(operations).isNotEmpty

        operations.forEach { operation ->
            assertThat(operation.responses().keys)
                .describedAs("the responses of $operation")
                .containsAll(operation.mustDocument())
        }
    }

    @Test
    fun `every failure is a problem detail`() {
        operations.forEach { operation ->
            operation.responses().filterKeys { it.toInt() >= 400 }.forEach { (status, documented) ->
                assertThat(response(documented)["content"].map().keys)
                    .describedAs("the $status of $operation")
                    .containsExactly("application/problem+json")
            }
        }
    }

    @Test
    fun `every scope an operation asks for is one the security scheme declares`() {
        val declared = section("components", "securitySchemes", "dpop", "flows", "clientCredentials", "scopes").keys

        val asked = operations.flatMap { operation ->
            (operation.definition["security"] as? List<*>).orEmpty().flatMap { it.map().values.flatMap { scopes -> scopes as List<*> } }
        }

        assertThat(asked).isNotEmpty
        asked.forEach { assertThat(declared).contains(it as String) }
    }

    @Test
    fun `operation ids are unique`() {
        val ids = operations.map { it.definition["operationId"] }

        assertThat(ids).doesNotContainNull().doesNotHaveDuplicates()
    }

    @Test
    fun `a request or a response is described with the fields of the type that is sent`() {
        mapOf<String, KClass<*>>(
            "ShortLink" to ShortLinkResponse::class,
            "ShortLinkPage" to ShortLinkPageResponse::class,
            "CreateShortLinkRequest" to CreateShortLinkRequest::class,
            "UpdateShortLinkRequest" to UpdateShortLinkRequest::class,
        ).forEach { (schemaName, type) ->
            val schema = section("components", "schemas", schemaName)
            val fields = type.primaryConstructor!!.parameters

            assertThat(schema["properties"].map().keys).describedAs("the properties of $schemaName").containsExactlyInAnyOrderElementsOf(fields.map { it.name })
            assertThat((schema["required"] as List<*>).map { it.toString() })
                .describedAs("what $schemaName requires: the fields that have no default")
                .containsExactlyInAnyOrderElementsOf(fields.filterNot { it.isOptional }.map { it.name })
        }
    }

    @Test
    fun `a field that can be null in a response is described as nullable, and the others are not`() {
        val response = ShortLinkResponse::class.primaryConstructor!!.parameters

        val schema = section("components", "schemas", "ShortLink")["properties"].map()

        response.forEach { field ->
            val type = schema[field.name].map()["type"]
            val nullable = type is List<*> && "null" in type
            assertThat(nullable).describedAs("is ${field.name} nullable").isEqualTo(field.type.isMarkedNullable)
        }
    }
}
