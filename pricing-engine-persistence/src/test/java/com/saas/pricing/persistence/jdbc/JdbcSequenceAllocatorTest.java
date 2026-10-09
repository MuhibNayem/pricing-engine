package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.invoice.InvoiceNumberFormat;
import com.saas.pricing.core.model.invoice.InvoiceNumberPolicy;
import com.saas.pricing.core.model.invoice.InvoiceNumberService;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.spi.SequenceAllocator;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JDBC invoice-number sequence.
 *
 * <p>Exists because the in-memory allocator and this one have entirely different atomicity
 * mechanisms — an {@code AtomicLong} versus a row lock — and passing in-memory tests say nothing
 * about whether the SQL is right. In particular {@code SELECT ... FOR UPDATE} is the only thing
 * standing between two concurrent finalizations and one duplicated document number.
 */
class JdbcSequenceAllocatorTest extends BaseJdbcRepositoryTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final TenantId OTHER_TENANT = TenantId.of("t2");
    private static final CustomerId ACME = CustomerId.of("acme");

    private SequenceAllocator allocator;

    @BeforeEach
    void setUp() {
        allocator = new JdbcSequenceAllocator(jdbcTemplate,
            new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
    }

    private InvoiceNumberService service(InvoiceNumberPolicy policy) {
        return new InvoiceNumberService(allocator, policy);
    }

    @Test
    @DisplayName("issues 1, 2, 3 in order")
    void issuesInOrder() {
        var service = service(InvoiceNumberPolicy.accountSequential("INV"));

        assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0001");
        assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0002");
        assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0003");
    }

    @Test
    @DisplayName("the series survives a new allocator, as a database-backed one must")
    void seriesIsDurable() {
        service(InvoiceNumberPolicy.accountSequential("INV")).allocateNext(TENANT, ACME);
        service(InvoiceNumberPolicy.accountSequential("INV")).allocateNext(TENANT, ACME);

        // A restart must not hand out a number that has already been issued.
        var afterRestart = service(InvoiceNumberPolicy.accountSequential("INV"));
        assertThat(afterRestart.allocateNext(TENANT, ACME)).isEqualTo("INV-0003");
    }

    @Test
    @DisplayName("series are scoped per tenant")
    void scopesByTenant() {
        var service = service(InvoiceNumberPolicy.accountSequential("INV"));

        assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0001");
        assertThat(service.allocateNext(OTHER_TENANT, ACME)).isEqualTo("INV-0001");
        assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0002");
    }

    @Test
    @DisplayName("per-customer series are independent")
    void customerSeriesAreIndependent() {
        var globex = CustomerId.of("globex");
        var service = service(InvoiceNumberPolicy.customerSequential(Map.of(ACME, "ACME", globex, "GLOBEX")));

        assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("ACME-0001");
        assertThat(service.allocateNext(TENANT, globex)).isEqualTo("GLOBEX-0001");
    }

    /**
     * Migration continuity.
     *
     * <p>A tenant arriving from another billing system must resume where that system stopped, or it
     * reissues document numbers that already exist in its history.
     */
    @Test
    @DisplayName("a migrated series resumes instead of restarting at 1")
    void resumesMigratedSeries() {
        var service = service(InvoiceNumberPolicy.accountSequential("INV").resumingAt(500));

        assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0500");
        assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0501");
    }

    /** The V19 constraint exists so the counter cannot be positioned before its own start. */
    @Test
    @DisplayName("the schema refuses a counter positioned before its start")
    void schemaRejectsCounterBelowStart() {
        service(InvoiceNumberPolicy.accountSequential("INV").resumingAt(100))
            .allocateNext(TENANT, ACME);

        assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE invoice_number_sequences SET next_value = 1 WHERE tenant_id = ? AND sequence_key = ?",
            TENANT.value(), "ACCOUNT"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a released number is reissued, leaving no gap")
    void releasedNumberIsReissued() {
        var service = service(InvoiceNumberPolicy.accountSequential("INV"));
        String issued = service.allocateNext(TENANT, ACME);

        assertThat(service.release(TENANT, ACME, issued)).isTrue();
        assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0001");
    }

    @Test
    @DisplayName("a number is not reissued once a later one has been handed out")
    void doesNotRewindPastIssuedNumbers() {
        var service = service(InvoiceNumberPolicy.accountSequential("INV"));
        String first = service.allocateNext(TENANT, ACME);
        service.allocateNext(TENANT, ACME);

        assertThat(service.release(TENANT, ACME, first))
            .as("INV-0001 may already be on a customer document")
            .isFalse();
        assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0003");
    }

    @Test
    @DisplayName("peeking does not consume a number")
    void peekDoesNotConsume() {
        var service = service(InvoiceNumberPolicy.accountSequential("INV"));

        assertThat(service.peekNext(TENANT, ACME)).isEqualTo("INV-0001");
        assertThat(service.peekNext(TENANT, ACME)).isEqualTo("INV-0001");
        assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0001");
    }

    /**
     * The property the whole table exists for.
     *
     * <p>A read-then-increment implementation lets two callers both read {@code next = 41} and both
     * write 42. Only the row lock prevents it, and only this test exercises the lock.
     */
    @Test
    @DisplayName("concurrent allocation never repeats a number")
    void concurrentAllocationIsUnique() throws Exception {
        var service = service(InvoiceNumberPolicy.accountSequential("INV"));
        int threads = 16;

        var seen = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                seen.add(service.allocateNext(TENANT, ACME));
                return 0;
            }));
        }
        start.countDown();
        for (var future : futures) {
            future.get();
        }
        pool.shutdown();

        assertThat(seen).as("a duplicated invoice number would be handed to two customers")
            .hasSize(threads);
    }

    @Test
    @DisplayName("concurrent allocation leaves the series contiguous")
    void concurrentAllocationIsContiguous() throws Exception {
        var service = service(InvoiceNumberPolicy.accountSequential("INV"));
        int threads = 12;

        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return Integer.parseInt(service.allocateNext(TENANT, ACME).substring(4));
            }));
        }
        start.countDown();

        Set<Integer> allocated = new TreeSet<>();
        for (var future : futures) {
            allocated.add(future.get());
        }
        pool.shutdown();

        var expected = new TreeSet<Integer>();
        IntStream.rangeClosed(1, threads).forEach(expected::add);
        assertThat(allocated).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("a series cannot start below 1")
    void refusesInvalidStart() {
        assertThatThrownBy(() -> allocator.nextValue(TENANT, "ACCOUNT", 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a custom prefix and padding are honoured")
    void honoursConfiguredFormat() {
        var policy = new InvoiceNumberPolicy(
            com.saas.pricing.core.model.invoice.InvoiceNumberScheme.ACCOUNT_SEQUENTIAL,
            new InvoiceNumberFormat("ACME", "/", 6), Map.of(), 1L);

        assertThat(service(policy).allocateNext(TENANT, ACME)).isEqualTo("ACME/000001");
    }
}