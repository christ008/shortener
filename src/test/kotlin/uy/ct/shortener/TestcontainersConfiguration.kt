package uy.ct.shortener

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile

/**
 * Starts a throwaway Postgres (same version as `compose.yaml`), initialised with the same
 * `deploy/postgres/bootstrap.sql` as every other environment so the roles exist, and wires it in
 * as the datasource. The application still connects as the container's superuser, as before; the
 * roles are exercised by `DatabaseRolesTest`.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer {
        return PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))
            .withCopyFileToContainer(MountableFile.forHostPath("deploy/postgres/bootstrap.sql"), "/docker-entrypoint-initdb.d/10-bootstrap.sql")
    }

}
