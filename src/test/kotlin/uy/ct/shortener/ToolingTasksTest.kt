package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * `gradle/tooling.gradle.kts` is the one list of the repository's scripts. A script that is renamed or removed must fail
 * here, and not when somebody runs the task, and every script of the repository must be on the list or be one that is
 * not ours to list.
 */
class ToolingTasksTest {

    private val tooling = Files.readString(Path.of("gradle/tooling.gradle.kts"))

    private val paths = Regex(""""((?:deploy|perf|tools)/[A-Za-z0-9_./-]+)"""").findAll(tooling).map { it.groupValues[1] }.toSet()

    @Test
    fun `every script a task starts exists`() {
        assertThat(paths).isNotEmpty
        paths.forEach { assertThat(Path.of(it)).describedAs("script of a tooling task: $it").exists() }
    }

    @Test
    fun `every task names itself in the tooling group, with a description`() {
        val tasks = Regex("""(?m)^tooling\(\s*"(\w+)",\s*"([^"]+)"""").findAll(tooling).toList()

        assertThat(tasks.map { it.groupValues[1] }).containsExactly("devSetup", "devPasswords", "keygen", "productionRealm", "dpopCall", "smoke", "stackPrepare")
        tasks.forEach { assertThat(it.groupValues[2]).isNotBlank }
    }

    @Test
    fun `the scripts that do not have a task are the ones that run inside the stack or are not ours`() {
        val withoutTask = setOf(
            "deploy/stack/deploy.sh", // run on a manager, by hand, with the version as its argument
            "deploy/keycloak/entrypoint.sh", // inside the Keycloak image
            "deploy/postgres/set-role-passwords.sh", "deploy/postgres/keycloak-database.sh", "deploy/postgres/include-diagnostics.sh", // inside Postgres
            "deploy/keycloak/dev-setup", "tools/DevSetup.java", // the script starts the tool, and both are listed through devSetup and devPasswords
            "deploy/keycloak/DpopClient.java", // listed through its tasks
            "perf/smoke.sh", // the shim of tools/run that the workflows call, listed through smoke
        )
        val libraries = setOf("tools/Cli.java", "tools/RealmTemplate.java", "tools/DpopClient.java") // shared by the tools or a link to the client, started by none
        val scripts = listOf("deploy", "tools").flatMap { root ->
            Files.walk(Path.of(root)).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .map { it.toString() }
                    .filter { it.endsWith(".sh") || it.endsWith(".java") || it == "deploy/keycloak/dev-setup" }
                    .toList()
            }
        }.toSet()

        assertThat(scripts - paths - withoutTask - libraries).describedAs("scripts under deploy/ and tools/ with neither a task nor a reason").isEmpty()
    }
}
