package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `deploy/stack/pki.sh`, the part of it that needs OpenSSL and not Docker: the root, the signature of the issuing CA, its installation and the variables for Compose.
 *
 * - The root's key is encrypted, and the issuing CA's certificate is for a year, cannot sign another CA, and may sign only for the stack's names.
 * - What is not right is refused before anything is written: a request its own key did not sign, a certificate another root signed, one for another key.
 * - A role the script does not know is refused before it asks the CA for anything.
 * - `env` names a hash for each file, and the labels of each certificate, and nothing that is not a variable name.
 */
class PkiScriptTest {

    @TempDir
    lateinit var dir: Path

    private val script = Path.of("deploy/stack/pki.sh")

    private fun run(vararg args: String): Pair<Int, String> {
        val builder = ProcessBuilder("sh", script.toString(), *args).redirectErrorStream(true)
        builder.environment()["SECRETS_DIR"] = dir.resolve("secrets").toString()
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    private fun openssl(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder("openssl", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    private fun root(name: String = "offline"): Path = dir.resolve(name).also { assertThat(run("root", it.toString()).first).isZero() }

    private fun request(name: String, subject: String = "/O=Shortener/OU=PKI/CN=Shortener Issuing CA 2026"): Pair<Path, Path> {
        val key = dir.resolve("$name.key")
        val csr = dir.resolve("$name.csr")
        assertThat(openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", key.toString()).first).isZero()
        assertThat(openssl("req", "-new", "-key", key.toString(), "-subj", subject, "-out", csr.toString()).first).isZero()
        return key to csr
    }

    @Test
    fun `the script is valid shell, and says what it takes when it is asked for nothing or for what it does not do`() {
        assertThat(ProcessBuilder("sh", "-n", script.toString()).start().waitFor()).describedAs("sh -n").isZero()
        assertThat(Files.isExecutable(script)).isTrue

        val none = run()
        assertThat(none.first).isNotZero()
        assertThat(none.second).contains("usage:", "issue", "renew")
        assertThat(run("destroy").first).describedAs("a command it does not know").isEqualTo(1)
        assertThat(run("issue").first).describedAs("no role").isNotZero()
    }

    @Test
    fun `a role it does not know is refused before the CA is asked for anything`() {
        val unknown = run("issue", "admin")

        assertThat(unknown.first).isEqualTo(1)
        assertThat(unknown.second).contains("unknown role 'admin'", "edge", "shortener", "migrator", "postgres").doesNotContain("the CA is not running")
    }

    @Test
    fun `the root has an encrypted key, is a CA that may sign one more level, and is not made over another`() {
        val offline = root()

        assertThat(Files.readString(offline.resolve("root.key"))).startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----")
        val text = openssl("x509", "-in", offline.resolve("root.crt").toString(), "-noout", "-text").second
        assertThat(text).contains("CA:TRUE, pathlen:1", "Certificate Sign, CRL Sign", "id-ecPublicKey", "ASN1 OID: prime256v1", "CN = Shortener Root CA")
        assertThat(openssl("x509", "-in", offline.resolve("root.crt").toString(), "-noout", "-checkend", "${3649L * 86400}").first).describedAs("valid for ten years").isZero()
        listOf("root.key", "root.pass").forEach {
            assertThat(Files.getPosixFilePermissions(offline.resolve(it))).describedAs("$it is private to its owner").hasSize(2)
        }

        val again = run("root", offline.toString())
        assertThat(again.first).isNotZero()
        assertThat(again.second).contains("nothing to do")
    }

    @Test
    fun `the issuing CA is signed for a year, as a CA that cannot sign another, for the names of the stack and no others`() {
        val offline = root()
        val (_, csr) = request("ca")
        val cert = dir.resolve("ca.pem")

        val signed = run("sign-intermediate", offline.toString(), csr.toString(), cert.toString())

        assertThat(signed.first).describedAs(signed.second).isZero()
        val text = openssl("x509", "-in", cert.toString(), "-noout", "-text").second
        assertThat(text).contains("CA:TRUE, pathlen:0", "Name Constraints: critical", "DNS:shortener", "DNS:postgres", "DNS:edge", "DNS:stepca", "URI:shortener.internal", "IP:0.0.0.0/0.0.0.0")
        assertThat(openssl("x509", "-in", cert.toString(), "-noout", "-checkend", "${364L * 86400}").first).describedAs("valid for a year").isZero()
        assertThat(openssl("x509", "-in", cert.toString(), "-noout", "-checkend", "${366L * 86400}").first).describedAs("and not for more").isEqualTo(1)
        assertThat(openssl("verify", "-x509_strict", "-CAfile", offline.resolve("root.crt").toString(), cert.toString()).first).isZero()
    }

    @Test
    fun `a request its own key did not sign is not signed`() {
        val offline = root()
        val (_, csr) = request("ca")
        val broken = dir.resolve("broken.csr").also { Files.writeString(it, Files.readString(csr).take(200)) }

        val refused = run("sign-intermediate", offline.toString(), broken.toString(), dir.resolve("broken.pem").toString())

        assertThat(refused.first).isNotZero()
        assertThat(Files.exists(dir.resolve("broken.pem"))).isFalse
    }

    @Test
    fun `the node installs what the root signed for its own key, and refuses what it did not`() {
        val offline = root()
        val secrets = Files.createDirectories(dir.resolve("secrets"))
        Files.writeString(secrets.resolve("pki_ca_pass"), "a password")
        assertThat(openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-aes-256-cbc", "-pass", "file:${secrets.resolve("pki_ca_pass")}", "-out", secrets.resolve("pki_ca_key").toString()).first).isZero()
        val csr = dir.resolve("ca.csr")
        assertThat(openssl("req", "-new", "-key", secrets.resolve("pki_ca_key").toString(), "-passin", "file:${secrets.resolve("pki_ca_pass")}", "-subj", "/O=Shortener/OU=PKI/CN=CA", "-out", csr.toString()).first).isZero()
        val cert = dir.resolve("ca.pem")
        assertThat(run("sign-intermediate", offline.toString(), csr.toString(), cert.toString()).first).isZero()

        val foreign = root("another")
        val signedByAnother = run("install-intermediate", cert.toString(), foreign.resolve("root.crt").toString())
        assertThat(signedByAnother.second).contains("is not signed by")
        assertThat(Files.exists(secrets.resolve("pki_ca_cert"))).describedAs("nothing is installed from a root that did not sign it").isFalse

        val (_, otherCsr) = request("other", "/O=Shortener/OU=PKI/CN=Another")
        val otherCert = dir.resolve("other.pem")
        assertThat(run("sign-intermediate", offline.toString(), otherCsr.toString(), otherCert.toString()).first).isZero()
        assertThat(run("install-intermediate", otherCert.toString(), offline.resolve("root.crt").toString()).second).contains("another key than the one init made")

        val installed = run("install-intermediate", cert.toString(), offline.resolve("root.crt").toString())
        assertThat(installed.first).describedAs(installed.second).isZero()
        assertThat(Files.readString(secrets.resolve("pki_chain")).split("-----BEGIN CERTIFICATE-----").size - 1).describedAs("the issuing CA, then the root").isEqualTo(2)
        assertThat(Files.readString(secrets.resolve("pki_root"))).isEqualTo(Files.readString(offline.resolve("root.crt")))
    }

    @Test
    fun `env names a hash for each file and the labels of each certificate, and skips what is not a secret`() {
        val offline = root()
        val secrets = Files.createDirectories(dir.resolve("secrets"))
        Files.copy(offline.resolve("root.crt"), secrets.resolve("pki_root"))
        Files.writeString(secrets.resolve("pki_edge_key"), "not a certificate")
        Files.writeString(secrets.resolve("pki_ca.csr"), "a request, which is not a secret")
        Files.writeString(secrets.resolve("pki_offline_ca.pem"), "a file of a rehearsal, whose name is not a variable's")

        val env = run("env")

        assertThat(env.first).describedAs(env.second).isZero()
        val variables = env.second.lines().filter { it.isNotBlank() }.associate { it.substringBefore("=") to it.substringAfter("=") }
        assertThat(variables.keys).allMatch { it.matches(Regex("PKI_[A-Z0-9_]+")) }
        assertThat(variables.keys).contains("PKI_ROOT_HASH", "PKI_ROOT_SERIAL", "PKI_ROOT_SHA256", "PKI_ROOT_NOT_AFTER", "PKI_ROOT_RENEW_AFTER", "PKI_EDGE_KEY_HASH", "PKI_LEAF_TEMPLATE_HASH")
        assertThat(variables.keys).describedAs("a key has a hash and no labels, and a request is no secret").doesNotContain("PKI_EDGE_KEY_SERIAL").noneMatch { it.startsWith("PKI_CA_CSR") || it.startsWith("PKI_OFFLINE") }
        assertThat(variables.getValue("PKI_ROOT_HASH")).hasSize(12)
        assertThat(variables.getValue("PKI_ROOT_SHA256")).matches("[0-9a-f]{64}")
        assertThat(variables.getValue("PKI_ROOT_NOT_AFTER")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")
        assertThat(variables.getValue("PKI_ROOT_RENEW_AFTER")).isLessThan(variables.getValue("PKI_ROOT_NOT_AFTER"))
    }
}
