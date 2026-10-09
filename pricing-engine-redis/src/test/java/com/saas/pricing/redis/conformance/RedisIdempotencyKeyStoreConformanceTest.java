package com.saas.pricing.redis.conformance;

import com.saas.pricing.core.spi.IdempotencyKeyStore;
import com.saas.pricing.redis.RedisIdempotencyKeyStore;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;


/** Runs the shared contract against the Redis adapter, against a real Redis 7. */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("IdempotencyKeyStore conformance — Redis")
class RedisIdempotencyKeyStoreConformanceTest extends IdempotencyKeyStoreConformance {

    @Container
    static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static RedisClient client;
    static StatefulRedisConnection<String, String> connection;

    @BeforeAll
    static void connect() {
        client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        connection = client.connect();
    }

    @AfterAll
    static void disconnect() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    @Override
    protected IdempotencyKeyStore store() {
        connection.sync().flushall();
        // Unique key per store so a leaked key from one test cannot decide another.
        return new RedisIdempotencyKeyStore(connection);
    }
}