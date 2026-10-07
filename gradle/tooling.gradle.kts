/**
 * One task for each script a person runs: `./gradlew tasks --group tooling` lists them. A task only starts the script, which can
 * still be run by hand (docs/adr/0026-scripting-standard.md). Settings are Gradle properties: `./gradlew keygen -Pclient=my-client`.
 *
 * - Tasks run with the project's JDK 25 toolchain. The tools of `tools/` need only a JDK 17 or newer, and `DpopClient.java` is
 *   written for 17.
 * - A task that needs a setting says which one when it is missing.
 * - `devSetup` takes the defaults, because Gradle has no terminal. Run `deploy/keycloak/dev-setup` to be asked.
 */
import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec

import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

val toolchain = extensions.getByType<JavaToolchainService>().launcherFor { languageVersion = JavaLanguageVersion.of(25) }

val javaHome: String get() = toolchain.get().metadata.installationPath.asFile.path

val java: String get() = File(javaHome, "bin/java").path

fun setting(name: String): String =
    (findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: throw GradleException("pass -P$name=...")

fun optional(name: String): String? = (findProperty(name) as String?)?.takeIf { it.isNotBlank() }

fun tooling(name: String, what: String, settings: () -> Map<String, String> = { emptyMap() }, command: () -> List<String>) =
    tasks.register<Exec>(name) {
        group = "tooling"
        description = what
        workingDir = rootDir
        doFirst {
            environment("JAVA_HOME", javaHome)
            environment(settings())
            commandLine(command())
        }
        commandLine("true")
    }

tooling("devSetup", "Makes the dev keys, realm, passwords and .env with their defaults. -Pforce replaces what exists.") {
    listOf("deploy/keycloak/dev-setup", "--yes") + listOfNotNull(if (hasProperty("force")) "--force" else null)
}

tooling("devPasswords", "Prints the passwords of the dev setup.") {
    listOf("deploy/keycloak/dev-setup", "--show")
}

tooling("keygen", "Makes a client key: line 1 private, line 2 public. -Pclient=NAME") {
    listOf(java, "deploy/keycloak/DpopClient.java", "keygen", setting("client"))
}

tooling("productionRealm", "Makes the realm a production Keycloak imports. -Pdemo=FILE -Padmin=FILE of public keys, -Poutput=FILE") {
    listOf("tools/run", "Realms", "production", setting("demo"), setting("admin")) + listOfNotNull(optional("output"))
}

tooling("dpopCall", "Calls the API with DPoP. -Pkey=FILE -Pclient=NAME -Purl=URL, and -Pmethod=GET and -Pbody=JSON") {
    listOf(java, "deploy/keycloak/DpopClient.java", "call", setting("key"), setting("client"), optional("method") ?: "GET", setting("url")) +
        listOfNotNull(optional("body"))
}

tooling(
    "smoke", "Checks every endpoint of a running instance. -PbaseUrl=URL, default http://localhost:8080, and -Pmgmt=URL, default http://localhost:8081",
    settings = { listOfNotNull(optional("mgmt")?.let { "MGMT" to it }).toMap() },
) {
    listOf("tools/run", "Smoke") + listOfNotNull(optional("baseUrl"))
}

tooling("stackPrepare", "Makes throwaway secrets and a certificate to rehearse the production stack on this machine.") {
    listOf("deploy/stack/local/prepare.sh")
}

tooling("report", "Reads results. -Preport=summary|gc|hprof|profile|json -Ptarget=DIRECTORY_OR_FILE, and -Ptop=N for hprof") {
    listOf("tools/run", "Report", setting("report"), setting("target")) + listOfNotNull(optional("top"))
}

tooling("postgresBackup", "Backs up and checks the stack's Postgres. -Pcommand=init|full|diff|check|info|restore-test") {
    listOf("deploy/postgres/backup", setting("command"))
}

