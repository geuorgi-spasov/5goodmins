package com.fivegoodmins.fivegoodmins;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL container for the whole test suite (ADR-014).
 *
 * public, not package-private: repository tests live in their feature packages
 * (action, user), and @Import cannot reach a package-private class from there.
 *
 * The container is a static singleton started once per JVM, not a fresh
 * container per @Bean call. Spring caches one application context per distinct
 * test configuration -- @SpringBootTest and @DataJpaTest are two -- so a
 * per-context container means starting Postgres twice and paying its startup
 * cost twice in CI. Nothing stops it: there is no close() anywhere, and
 * Testcontainers' Ryuk sidecar removes the container when the JVM exits.
 *
 * PostgreSQLContainer is not generic in Testcontainers 2.x -- the <SELF> self
 * type that every 1.x example writes as PostgreSQLContainer<?> was removed.
 *
 * The image tag must match the deployed major version (ADR-004/018). Railway's
 * official Postgres is 16/17; verify with SELECT version() and change both this
 * line and compose.yaml together.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

    static {
        POSTGRES.start();
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }
}
