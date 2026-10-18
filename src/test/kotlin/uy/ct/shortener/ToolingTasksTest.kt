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

        assertThat(tasks.map { it.groupValues[1] }).containsExactly("devSetup", "devPasswords", "keygen", "productionRealm", "dpopCall", "smoke", "stackPrepare", "report", "postgresBackup")
        tasks.forEach { assertThat(it.groupValues[2]).isNotBlank }
    }

    @Test
    fun `the scripts that do not have a task are the ones that run inside the stack or are not ours`() {
        val withoutTask = setOf(
            "deploy/stack/deploy.sh", // run on a manager, by hand, with the version as its argument
            "deploy/stack/certificate.sh", // run as root on the node that runs the edge, and by certbot's renewal hook
            "deploy/stack/pki.sh", // run as root on the node that runs the CA, by hand and by its renewal timer; the root and its signature, on another machine
            "deploy/stack/local/prepare-pki.sh", // adds the CA to the rehearsal of prepare.sh, by hand
            "deploy/aws/launch.sh", // creates the VM with the AWS CLI, from any machine, by hand
            "deploy/aws/bootstrap.sh", // runs on the new VM
            "deploy/aws/offsite.sh", // makes the bucket and the role of the copy of the backups, with the AWS CLI, from any machine, by hand
            "deploy/keycloak/entrypoint.sh", // inside the Keycloak image
            "deploy/postgres/entrypoint.sh", "deploy/postgres/replica-entrypoint.sh", "deploy/postgres/restore-test.sh", // inside the Postgres image
            "deploy/postgres/set-role-passwords.sh", "deploy/postgres/keycloak-database.sh", "deploy/postgres/include-diagnostics.sh", "deploy/postgres/replication.sh", // inside Postgres
            "deploy/keycloak/dev-setup", // the script starts the DevSetup tool, and is listed through devSetup and devPasswords
            "deploy/keycloak/DpopClient.java", // listed through its tasks
            "deploy/keycloak/Console.java", // a class of the client, compiled and run by DpopClient.java
            "deploy/keycloak/Failure.java", // a class of the client, compiled and run by DpopClient.java
            "deploy/keycloak/Http.java", // a class of the client, compiled and run by DpopClient.java
            "deploy/keycloak/Jose.java", // a class of the client, compiled and run by DpopClient.java
            "deploy/keycloak/Json.java", // a class of the client, compiled and run by DpopClient.java
            "deploy/keycloak/KeycloakLogin.java", // a class of the client, compiled and run by DpopClient.java
            "deploy/keycloak/Keys.java", // a class of the client, compiled and run by DpopClient.java
            "deploy/keycloak/Oauth.java", // a class of the client, compiled and run by DpopClient.java
            "deploy/keycloak/Refused.java", // a class of the client, compiled and run by DpopClient.java
            "deploy/keycloak/SystemConsole.java", // a class of the client, compiled and run by DpopClient.java
            "deploy/keycloak/TokenEndpoint.java", // a class of the client, compiled and run by DpopClient.java
            "perf/smoke.sh", // the shim of tools/run that the workflows call, listed through smoke
        )
        val scripts = listOf("deploy", "tools").flatMap { root ->
            Files.walk(Path.of(root)).use { stream ->
                stream.filter { Files.isRegularFile(it) && "/build/" !in it.toString() }
                    .map { it.toString() }
                    .filter { it.endsWith(".sh") || it.endsWith(".java") || it == "deploy/keycloak/dev-setup" || it == "tools/run" }
                    .toList()
            }
        }.toSet()

        assertThat(scripts - paths - withoutTask).describedAs("scripts under deploy/ and tools/ with neither a task nor a reason").isEmpty()
    }
}
