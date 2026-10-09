package com.saas.pricing.starter.web;

import com.saas.pricing.core.spi.AdmissionController.Shedding;
import com.saas.pricing.core.spi.LoadShedException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the 429 / 503 split.
 *
 * <p>This is the one mapping an operator will be judged on during an incident. Getting it backwards
 * — answering 503 because the engine happened to be busy when one tenant exceeded its quota —
 * tells every healthy caller the platform is down, and buries the actual problem.</p>
 */
@DisplayName("Load shedding maps to the status that tells the caller what to do")
class LoadShedHttpMappingTest {

    private final PricingEngineExceptionHandler handler = new PricingEngineExceptionHandler();

    @Test
    @DisplayName("a tenant over its own quota gets 429, not 503")
    void rateLimitedIs429() {
        ResponseEntity<org.springframework.http.ProblemDetail> response =
                handler.handleLoadShed(new LoadShedException(Shedding.RATE_LIMITED, Duration.ofSeconds(30)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties().get("code")).isEqualTo("RATE_LIMITED");
        assertThat(response.getBody().getProperties().get("retryAfterSeconds")).isEqualTo(30L);
    }

    @Test
    @DisplayName("a saturated engine gets 503, not 429")
    void overloadedIs503() {
        ResponseEntity<org.springframework.http.ProblemDetail> response =
                handler.handleLoadShed(new LoadShedException(Shedding.OVERLOADED, Duration.ofSeconds(2)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties().get("code")).isEqualTo("ENGINE_OVERLOADED");
    }

    @Test
    @DisplayName("Retry-After never advertises zero, even for a sub-second floor")
    void retryAfterIsAtLeastOneSecond() {
        // A 50ms concurrency floor is real, but advertising "retry in 0 seconds" produces an
        // immediate resynchronised retry storm against the thing that just shed the caller.
        ResponseEntity<org.springframework.http.ProblemDetail> response = handler.handleLoadShed(
                new LoadShedException(Shedding.OVERLOADED, Duration.ofMillis(50)));

        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties().get("retryAfterSeconds")).isEqualTo(0L);
    }

    @Test
    @DisplayName("an admitted result cannot be turned into a shed error")
    void admittedResultCannotBecomeAnException() {
        var admitted = com.saas.pricing.core.spi.AdmissionController.UNBOUNDED
                .admit(new com.saas.pricing.core.model.TenantId("acme"));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> LoadShedException.from(admitted));
    }
}