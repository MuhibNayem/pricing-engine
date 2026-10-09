package com.saas.pricing.core.model.invoice;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.SequenceAllocator;
import com.saas.pricing.core.spi.impl.InMemorySequenceAllocator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invoice document numbering.
 *
 * <p>Every EU member state and the UK requires invoices to be numbered sequentially across the
 * business. That makes this the subsystem where two properties matter above all others: numbers are
 * never <em>repeated</em> (a duplicate document number is the most audit-visible defect billing
 * produces), and never <em>skipped</em> (a gap is what sequential numbering exists to rule out).
 */
class InvoiceNumberingTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final TenantId OTHER_TENANT = TenantId.of("t2");
    private static final CustomerId ACME = CustomerId.of("acme");
    private static final CustomerId GLOBEX = CustomerId.of("globex");

    private SequenceAllocator allocator;

    private InvoiceNumberService accountSequential() {
        allocator = new InMemorySequenceAllocator();
        return new InvoiceNumberService(allocator, InvoiceNumberPolicy.accountSequential("INV"));
    }

    private InvoiceNumberService customerSequential(Map<CustomerId, String> prefixes) {
        allocator = new InMemorySequenceAllocator();
        return new InvoiceNumberService(allocator, InvoiceNumberPolicy.customerSequential(prefixes));
    }

    @Nested
    @DisplayName("Format")
    class Format {

        @Test
        @DisplayName("renders a padded sequence")
        void rendersPaddedSequence() {
            var format = InvoiceNumberFormat.of("INV");

            assertThat(format.render(1)).isEqualTo("INV-0001");
            assertThat(format.render(42)).isEqualTo("INV-0042");
            assertThat(format.render(1234)).isEqualTo("INV-1234");
        }

        @Test
        @DisplayName("a prefix is uppercased rather than rejected")
        void uppercasesPrefix() {
            assertThat(new InvoiceNumberFormat("inv", "-", 4).render(7)).isEqualTo("INV-0007");
        }

        /**
         * A prefix is typed by people and read aloud on the phone.
         *
         * <p>Lowercase, spaces and punctuation all generate support tickets, so they are refused at
         * configuration time rather than at invoice time.
         */
        @Test
        @DisplayName("a prefix with spaces, punctuation or lowercase is refused")
        void refusesUnusablePrefixes() {
            assertThatThrownBy(() -> new InvoiceNumberFormat("AC ME", "-", 4))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new InvoiceNumberFormat("AC-ME", "-", 4))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new InvoiceNumberFormat("", "-", 4))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a prefix longer than 12 characters is refused")
        void refusesOverlongPrefix() {
            assertThatThrownBy(() -> new InvoiceNumberFormat("ABCDEFGHIJKLM", "-", 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-12");
        }

        /**
         * A number wider than the padding is still unique, so it must render.
         *
         * <p>Refusing to render it would strand a tenant with no invoice at exactly the moment they
         * exceed their own format — the worst possible time to discover the limit.
         */
        @Test
        @DisplayName("a sequence wider than the padding still renders")
        void overflowsPaddingWithoutTruncating() {
            assertThat(InvoiceNumberFormat.of("INV").render(10_000)).isEqualTo("INV-10000");
        }

        @Test
        @DisplayName("the sequence ceiling is enforced rather than wrapping")
        void refusesExhaustedSequence() {
            assertThatThrownBy(() -> InvoiceNumberFormat.of("INV")
                .render(InvoiceNumberFormat.MAX_SEQUENCE + 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exhausted");
        }

        @Test
        @DisplayName("sequences start at 1")
        void refusesZeroSequence() {
            assertThatThrownBy(() -> InvoiceNumberFormat.of("INV").render(0))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Account-sequential numbering")
    class AccountSequential {

        @Test
        @DisplayName("issues 1, 2, 3 with no repeats")
        void issuesInOrder() {
            var service = accountSequential();

            assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0001");
            assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0002");
            assertThat(service.allocateNext(TENANT, GLOBEX))
                .as("one series for the whole tenant, so every customer draws from it")
                .isEqualTo("INV-0003");
        }

        @Test
        @DisplayName("peeking does not consume a number")
        void peekDoesNotConsume() {
            var service = accountSequential();

            assertThat(service.peekNext(TENANT, ACME)).isEqualTo("INV-0001");
            assertThat(service.peekNext(TENANT, ACME)).isEqualTo("INV-0001");
            assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0001");
            assertThat(service.peekNext(TENANT, ACME)).isEqualTo("INV-0002");
        }

        /** Migration continuity: a tenant arriving from another system must not restart at 1. */
        @Test
        @DisplayName("resumes an existing series instead of restarting it")
        void resumesExistingSeries() {
            allocator = new InMemorySequenceAllocator();
            var policy = InvoiceNumberPolicy.accountSequential("INV").resumingAt(500);
            var service = new InvoiceNumberService(allocator, policy);

            assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0500");
        }

        /**
         * Tenants must never consume each other's numbers.
         *
         * <p>A shared counter would mean one tenant's roll-back or activity moves another's series,
         * and the resulting duplicate would surface as a tax-authority question.
         */
        @Test
        @DisplayName("series are scoped per tenant")
        void scopesByTenant() {
            var service = accountSequential();

            assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0001");
            assertThat(service.allocateNext(OTHER_TENANT, ACME)).isEqualTo("INV-0001");
            assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0002");
        }
    }

    @Nested
    @DisplayName("Customer-sequential numbering")
    class CustomerSequential {

        @Test
        @DisplayName("each customer draws from their own series")
        void eachCustomerHasItsOwnSeries() {
            var service = customerSequential(Map.of(ACME, "ACME", GLOBEX, "GLOBEX"));

            assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("ACME-0001");
            assertThat(service.allocateNext(TENANT, GLOBEX)).isEqualTo("GLOBEX-0001");
            assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("ACME-0002");
            assertThat(service.allocateNext(TENANT, GLOBEX)).isEqualTo("GLOBEX-0002");
        }

        /**
         * Falling back to the account prefix would merge two customers into one series.
         *
         * <p>The numbers would still be unique, so nothing would flag it — and each customer would
         * then see gaps and volume from the other. Refusing is the only safe answer.
         */
        @Test
        @DisplayName("a customer with no prefix is refused rather than given the account prefix")
        void refusesCustomerWithoutPrefix() {
            var service = customerSequential(Map.of(ACME, "ACME"));

            assertThatThrownBy(() -> service.allocateNext(TENANT, GLOBEX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no invoice number prefix");
        }

        @Test
        @DisplayName("two customers cannot share a prefix")
        void refusesSharedPrefix() {
            assertThatThrownBy(() -> InvoiceNumberPolicy.customerSequential(
                Map.of(ACME, "SAME", GLOBEX, "SAME")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shared by customers");
        }
    }

    @Nested
    @DisplayName("Gaplessness")
    class Gaplessness {

        /**
         * A finalization that issued nothing must not consume a number.
         *
         * <p>Allocating and keeping would turn every transient database error into a permanent hole
         * in the tenant's tax series, which is unrecoverable after the fact.
         */
        @Test
        @DisplayName("a released number is reissued, so a failure leaves no gap")
        void releasedNumberIsReissued() {
            var service = accountSequential();

            String issued = service.allocateNext(TENANT, ACME);
            assertThat(issued).isEqualTo("INV-0001");

            assertThat(service.release(TENANT, ACME, issued)).isTrue();

            assertThat(service.allocateNext(TENANT, ACME))
                .as("the series must be contiguous after a failed finalization")
                .isEqualTo("INV-0001");
        }

        @Test
        @DisplayName("a number is not reissued once a later one has been handed out")
        void doesNotRewindPastIssuedNumbers() {
            var service = accountSequential();
            String first = service.allocateNext(TENANT, ACME);
            service.allocateNext(TENANT, ACME);

            assertThat(service.release(TENANT, ACME, first))
                .as("INV-0001 may already be on a customer document; reissuing it is worse than a gap")
                .isFalse();
            assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("INV-0003");
        }

        @Test
        @DisplayName("releasing a foreign number leaves the series alone")
        void ignoresForeignNumbers() {
            var service = customerSequential(Map.of(ACME, "ACME"));

            assertThat(service.release(TENANT, ACME, "IMPORTED-99")).isFalse();
            assertThat(service.release(TENANT, ACME, "ACME-abc")).isFalse();
            assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("ACME-0001");
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        /**
         * Concurrent finalizations must produce distinct numbers.
         *
         * <p>This is the property the whole subsystem rests on, and it is exactly what a
         * read-then-increment implementation gets wrong: two callers both read "next = 41" and both
         * write 42.
         */
        @Test
        @DisplayName("concurrent allocation never repeats a number")
        void concurrentAllocationIsUnique() throws Exception {
            var service = accountSequential();
            int threads = 32;

            var results = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
            var start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Callable<Integer>> tasks = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    start.await();
                    results.add(service.allocateNext(TENANT, ACME));
                    return 0;
                });
            }
            List<Future<Integer>> futures = new ArrayList<>();
            for (var task : tasks) {
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (var future : futures) {
                future.get();
            }
            pool.shutdown();

            assertThat(results)
                .as("a duplicate invoice number would be handed to two customers")
                .hasSize(threads);
            assertThat(results.stream().map(n -> n.substring(4)).sorted().count()).isEqualTo(threads);
        }

        @Test
        @DisplayName("concurrent allocation leaves no gaps")
        void concurrentAllocationIsContiguous() throws Exception {
            var service = accountSequential();
            int threads = 16;

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

            var seen = new java.util.TreeSet<Integer>();
            for (var future : futures) {
                seen.add(future.get());
            }
            pool.shutdown();

            var expected = new java.util.TreeSet<Integer>();
            for (int i = 1; i <= threads; i++) {
                expected.add(i);
            }
            assertThat(seen)
                .as("sequential numbering must be provably contiguous")
                .containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("separate customer series do not block one another")
        void customerSeriesAreIndependent() {
            var service = customerSequential(Map.of(ACME, "ACME", GLOBEX, "GLOBEX"));

            assertThat(service.allocateNext(TENANT, ACME)).isEqualTo("ACME-0001");
            assertThat(service.allocateNext(TENANT, GLOBEX)).isEqualTo("GLOBEX-0001");
        }
    }

    @Nested
    @DisplayName("Allocator contract")
    class AllocatorContract {

        @Test
        @DisplayName("a series cannot start below 1")
        void refusesInvalidStart() {
            allocator = new InMemorySequenceAllocator();

            assertThatThrownBy(() -> allocator.nextValue(TENANT, "ACCOUNT", 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("peeking an unknown series reports the value before its start")
        void peekUnknownSeries() {
            allocator = new InMemorySequenceAllocator();

            assertThat(allocator.peek(TENANT, "ACCOUNT", 10L)).isEqualTo(9L);
            allocator.nextValue(TENANT, "ACCOUNT", 10L);
            assertThat(allocator.peek(TENANT, "ACCOUNT", 10L)).isEqualTo(10L);
        }

        @Test
        @DisplayName("restoring an unknown series is a no-op")
        void restoreUnknownSeries() {
            allocator = new InMemorySequenceAllocator();

            assertThat(allocator.restore(TENANT, "ACCOUNT", 5L)).isFalse();
        }

        @Test
        @DisplayName("a series key is scoped to its tenant")
        void sequenceKeysAreTenantScoped() {
            allocator = new InMemorySequenceAllocator();

            assertThat(allocator.nextValue(TENANT, "ACCOUNT", 1L)).isEqualTo(1L);
            assertThat(allocator.nextValue(OTHER_TENANT, "ACCOUNT", 1L)).isEqualTo(1L);
        }
    }
}