package com.saas.pricing.persistence.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class BaseJdbcRepositoryTest {

    private static final Pattern MIGRATION_NAME =
        Pattern.compile("^V(\\d+)__.*\\.sql$");

    protected DataSource dataSource;
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void initDatabase() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:pricing_test_" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        this.dataSource = ds;
        this.jdbcTemplate = new JdbcTemplate(ds);

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        portableMigrations().forEach(populator::addScript);
        populator.execute(ds);
    }

    /**
     * Every migration except the {@code *_postgres_only.sql} scripts, in Flyway version order.
     *
     * <p>Discovered rather than hard-coded so a new migration cannot silently miss this fixture,
     * and missing a PostgreSQL-only script cannot silently pass: those scripts are exercised by
     * {@link PostgresMigrationTest} against a real PostgreSQL, which is the only place
     * {@code CREATE RULE} and plpgsql actually run.
     */
    private static List<Resource> portableMigrations() {
        Resource[] found;
        try {
            found = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot enumerate db/migration scripts", e);
        }
        return Arrays.stream(found)
            .filter(resource -> {
                String name = resource.getFilename();
                return name != null && !name.contains("postgres_only");
            })
            .sorted(Comparator.comparingInt(BaseJdbcRepositoryTest::versionOf))
            .toList();
    }

    private static int versionOf(Resource resource) {
        String name = resource.getFilename();
        Matcher matcher = name == null ? null : MIGRATION_NAME.matcher(name);
        if (matcher == null || !matcher.matches()) {
            throw new IllegalArgumentException("Unrecognised migration file name: " + name);
        }
        return Integer.parseInt(matcher.group(1));
    }
}
