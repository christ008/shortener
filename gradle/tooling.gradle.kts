/**
 * One command for each script of the repository, so that `./gradlew tasks --group tooling` is the list and nobody has to
 * remember a path. Each task only starts the script, which stays the thing that does the work and can still be run by hand
 * (docs/adr/0026-scripting-standard.md). Settings are Gradle properties: `./gradlew keygen -Pclient=my-client`.
 *
 * - The Java tools run on the JDK 25 of the project's toolchain, whichever `java` is first on the PATH, because the tools of
 *   `tools/` are compact source files that older JDKs refuse. `DpopClient.java`, which people outside the project run, is written
 *   for 17 and runs here on 25 as well.
 * - A task that needs a setting says which one when it is missing.
 * - `dev-setup` asks questions when it can, and Gradle has no terminal for it, so `devSetup` takes the defaults. Run the script
 *   itself to be asked.
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
    listOf(java, "tools/Realms.java", "production", setting("demo"), setting("admin")) + listOfNotNull(optional("output"))
}

tooling("dpopCall", "Calls the API with DPoP. -Pkey=FILE -Pclient=NAME -Purl=URL, and -Pmethod=GET and -Pbody=JSON") {
    listOf(java, "deploy/keycloak/DpopClient.java", "call", setting("key"), setting("client"), optional("method") ?: "GET", setting("url")) +
        listOfNotNull(optional("body"))
}

tooling(
    "smoke", "Checks every endpoint of a running instance. -PbaseUrl=URL, default http://localhost:8080, and -Pmgmt=URL, default http://localhost:8081",
    settings = { listOfNotNull(optional("mgmt")?.let { "MGMT" to it }).toMap() },
) {
    listOf(java, "tools/Smoke.java") + listOfNotNull(optional("baseUrl"))
}

tooling("stackPrepare", "Makes throwaway secrets and a certificate to rehearse the production stack on this machine.") {
    listOf("deploy/stack/local/prepare.sh")
}

tooling("report", "Reads results. -Preport=summary|gc|hprof|profile|json -Ptarget=DIRECTORY_OR_FILE, and -Ptop=N for hprof") {
    listOf(java, "tools/Report.java", setting("report"), setting("target")) + listOfNotNull(optional("top"))
}

tooling("postgresBackup", "Backs up and checks the stack's Postgres. -Pcommand=init|full|diff|check|info|restore-test") {
    listOf("deploy/postgres/backup", setting("command"))
}

