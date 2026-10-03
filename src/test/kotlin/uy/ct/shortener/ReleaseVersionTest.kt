package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The project version follows semantic versioning (https://semver.org), baselined at 0.1.0, and
 * is the single source of truth: the image tag in the Kubernetes manifests must match it, so a
 * release cannot ship with manifests that point at a different image. While the major version is
 * 0 the API is not yet stable, so a breaking change bumps the minor version.
 */
class ReleaseVersionTest {

    private val version = System.getProperty("project.version")

    private val semver = Regex(
        """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$""",
    )

    @Test
    fun `the version is valid semver at or above the 0_1_0 baseline`() {
        val parsed = semver.matchEntire(version)?.destructured?.let { (major, minor, patch) -> Triple(major.toInt(), minor.toInt(), patch.toInt()) }
            ?: error("'$version' is not a valid semantic version")

        assertThat(compareValuesBy(parsed, Triple(0, 1, 0), { it.first }, { it.second }, { it.third })).isGreaterThanOrEqualTo(0)
    }

    @Test
    fun `the version can be used as an image tag`() {
        assertThat(version).describedAs("build metadata after '+' is not allowed in image tags").doesNotContain("+")
    }

    @Test
    fun `the manifests point at the image of this version`() {
        listOf("deploy/k8s/base/kustomization.yaml", "deploy/k8s/overlays/production/kustomization.yaml").forEach {
            val tag = Regex("""newTag:\s*(\S+)""").find(Files.readString(Path.of(it)))?.groupValues?.get(1)

            assertThat(tag).describedAs("image tag in $it").isEqualTo(version)
        }
    }
}
