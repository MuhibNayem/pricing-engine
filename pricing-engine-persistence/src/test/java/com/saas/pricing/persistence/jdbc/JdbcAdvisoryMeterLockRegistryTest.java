package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.TenantId;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.concurrent.locks.Lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cross-node metering mutual exclusion.
 *
 * <p>Two registries backed by the same PostgreSQL share advisory locks; a striped in-JVM lock does
 * not. This is the property that stops node A re-saving a stale aggregation after node B saved and
 * invalidated the same window.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcAdvisoryMeterLockRegistryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("pricing")
            .withUsername("pricing")
            .withPassword("pricing");

    static DataSource dataSource;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;
    }

    @Test
    @DisplayName("a lock held by one node blocks the same key on another and releases cleanly")
    void advisoryLockIsExclusiveAcrossRegistryInstances() {
        var nodeA = new JdbcAdvisoryMeterLockRegistry(dataSource);
        var nodeB = new JdbcAdvisoryMeterLockRegistry(dataSource);

        Lock held = nodeA.lockFor(TenantId.of("t-lock"), "API_CALLS");
        Lock contender = nodeB.lockFor(TenantId.of("t-lock"), "API_CALLS");
        Lock unrelated = nodeB.lockFor(TenantId.of("t-lock"), "STORAGE");

        held.lock();
        try {
            assertThat(contender.tryLock())
                .as("the same tenant+meter must be exclusive across nodes")
                .isFalse();
            assertThat(unrelated.tryLock())
                .as("a different meter must not be blocked")
                .isTrue();
            unrelated.unlock();
        } finally {
            held.unlock();
        }

        assertThat(contender.tryLock())
            .as("releasing the holder frees the key for the other node")
            .isTrue();
        contender.unlock();
    }

    @Test
    @DisplayName("the same registry produces equal keys for the same tenant+meter")
    void keyDerivationIsStable() {
        assertThat(JdbcAdvisoryMeterLockRegistry.advisoryKey("t1", "API_CALLS"))
            .isEqualTo(JdbcAdvisoryMeterLockRegistry.advisoryKey("t1", "api_calls"));
        assertThat(JdbcAdvisoryMeterLockRegistry.advisoryKey("t1", "API_CALLS"))
            .isNotEqualTo(JdbcAdvisoryMeterLockRegistry.advisoryKey("t2", "API_CALLS"));
    }

    @Test
    @DisplayName("a non-reentrant lock refuses a second acquisition by the same instance")
    void lockIsNotReentrantWithinAnInstance() {
        var registry = new JdbcAdvisoryMeterLockRegistry(dataSource);
        Lock lock = registry.lockFor(TenantId.of("t-reentrant"), "API_CALLS");
        lock.lock();
        try {
            assertThatThrownBy(lock::lock).isInstanceOf(IllegalStateException.class);
        } finally {
            lock.unlock();
        }
    }
}
