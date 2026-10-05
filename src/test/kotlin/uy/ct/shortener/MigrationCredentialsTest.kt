package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * The point of running migrations in an init container is that the container serving requests never holds the
 * credentials of the role that owns the tables. A remote code execution in the application would otherwise be able to
 * drop them. This reads the Deployment and checks that only the init container that applies the migrations
 * references the `shortener-db-migrator` Secret, whether as an environment variable, through `envFrom` or as a mounted
 * volume, and that it is the one that stops after migrating.
 */
class MigrationCredentialsTest {

    private val migratorSecret = "shortener-db-migrator"

    @Suppress("UNCHECKED_CAST")
    private val pod: Map<String, Any> = Yaml().load<Map<String, Any>>(Files.readString(Path.of("deploy/k8s/base/deployment.yaml")))
        .let { ((it["spec"] as Map<String, Any>)["template"] as Map<String, Any>)["spec"] as Map<String, Any> }

    @Suppress("UNCHECKED_CAST")
    private fun containers(key: String) = (pod[key] as List<Map<String, Any>>).associateBy { it["name"] as String }

    @Suppress("UNCHECKED_CAST")
    private fun secretsReferencedBy(container: Map<String, Any>): Set<String> {
        val fromEnv = (container["env"] as? List<Map<String, Any>> ?: emptyList())
            .mapNotNull { ((it["valueFrom"] as? Map<String, Any>)?.get("secretKeyRef") as? Map<String, Any>)?.get("name") as? String }
        val fromEnvFrom = (container["envFrom"] as? List<Map<String, Any>> ?: emptyList())
            .mapNotNull { (it["secretRef"] as? Map<String, Any>)?.get("name") as? String }
        val mounted = (container["volumeMounts"] as? List<Map<String, Any>> ?: emptyList()).map { it["name"] as String }.toSet()
        val fromVolumes = (pod["volumes"] as? List<Map<String, Any>> ?: emptyList())
            .filter { it["name"] in mounted }
            .mapNotNull { (it["secret"] as? Map<String, Any>)?.get("secretName") as? String }
        return (fromEnv + fromEnvFrom + fromVolumes).toSet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun environment(container: Map<String, Any>) =
        (container["env"] as List<Map<String, Any>>).associate { it["name"] as String to it["value"] }

    @Test
    fun `only the init container that migrates holds the credentials of the migrator`() {
        assertThat(secretsReferencedBy(containers("initContainers").getValue("migrate"))).contains(migratorSecret)

        containers("containers").forEach { (name, container) ->
            assertThat(secretsReferencedBy(container)).describedAs("secrets of container '$name'").doesNotContain(migratorSecret)
        }
    }

    @Test
    fun `the application container does not get Flyway settings of its own`() {
        containers("containers").values.forEach { container ->
            assertThat(environment(container).keys).noneMatch { it.startsWith("SPRING_FLYWAY_") }
        }
    }

    @Test
    fun `the init container migrates and stops, with Flyway on its own connection`() {
        val migrate = containers("initContainers").getValue("migrate")
        val env = environment(migrate)

        assertThat(env["SHORTENER_MIGRATE_ONLY"]).isEqualTo("true")
        assertThat(env.keys).contains("SPRING_FLYWAY_URL", "SPRING_FLYWAY_USER", "SPRING_FLYWAY_PASSWORD")
    }

    @Test
    fun `the service account has no token to read the secrets with`() {
        assertThat(pod["automountServiceAccountToken"]).isEqualTo(false)
    }
}
