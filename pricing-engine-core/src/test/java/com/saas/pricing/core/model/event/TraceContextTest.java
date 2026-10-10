package com.saas.pricing.core.model.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conformance of {@link TraceContext} to W3C Trace Context §3.2.
 *
 * <p>These assertions are written against the specification's own worked example and its stated
 * invalid cases, not against this implementation's encoder. A round-trip test would pass with the
 * grammar entirely wrong, because a parser and a validator that agree on the same wrong rule cancel
 * each other out — the one thing that catches it is a value the specification itself prints.</p>
 *
 * @see <a href="https://www.w3.org/TR/trace-context/">W3C Trace Context</a>
 */
@DisplayName("TraceContext conforms to W3C Trace Context")
class TraceContextTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN_ID = "b7ad6b7169203331";

    @Test
    @DisplayName("the specification's worked example parses")
    void specificationExampleParses() {
        TraceContext context = TraceContext.of(TraceContext.EXAMPLE_TRACEPARENT);

        assertThat(context.isPresent()).isTrue();
        assertThat(context.traceparent()).contains(TraceContext.EXAMPLE_TRACEPARENT);
        assertThat(TraceContext.EXAMPLE_TRACEPARENT).isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
        assertThat(TraceContext.EXAMPLE_TRACEPARENT).hasSize(55);
    }

    @Test
    @DisplayName("NONE is a value, not an absence, so callers never branch on null")
    void noneIsAlwaysUsable() {
        assertThat(TraceContext.NONE.isPresent()).isFalse();
        assertThat(TraceContext.NONE.traceparent()).isEmpty();
        assertThat(TraceContext.NONE.tracestate()).isEmpty();
        assertThat(TraceContext.NONE.asCarrierHeaders()).isEmpty();
    }

    @ParameterizedTest(name = "rejects: {0}")
    @DisplayName("rejects every form the specification forbids")
    @ValueSource(strings = {
        // All-zero identifiers: invalid trace-id and invalid parent-id (§3.2.2.3, §3.2.2.4).
        "00-00000000000000000000000000000000-b7ad6b7169203331-01",
        "00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01",
        // 'ff' is reserved (§3.2.2.2).
        "ff-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
        // Uppercase hex is not lowercase hex, everywhere.
        "00-0AF7651916CD43DD8448EB211C80319C-b7ad6b7169203331-01",
        "00-0af7651916cd43dd8448eb211c80319c-B7AD6B7169203331-01",
        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-0A",
        // Non-hex characters in each field.
        "00-0af7651916cd43dd8448eb211c80319g-b7ad6b7169203331-01",
        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b716920333z-01",
        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-0x",
        // Wrong delimiters: a separator moved makes a shorter or differently shaped id.
        "00_0af7651916cd43dd8448eb211c80319c_b7ad6b7169203331_01",
        "00-0af7651916cd43dd8448eb211c80319cb7ad6b7169203331-01",
        // Short of the minimum 55.
        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-0",
        "0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
        "",
    })
    void rejectsIllegalTraceparent(String malformed) {
        assertThatThrownBy(() -> TraceContext.of(malformed))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a version '00' traceparent may not carry trailing fields")
    void versionZeroMustBeExactlyFiftyFiveCharacters() {
        String withExtra = "00-" + TRACE_ID + "-" + SPAN_ID + "-01-extra";

        assertThatThrownBy(() -> TraceContext.of(withExtra))
            .as("trailing fields are only defined for a future version; accepting them on version "
                + "00 would let a producer invent a field no specification version explains")
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a future version is parsed, and its trailing fields tolerated")
    void futureVersionsAreForwardCompatible() {
        // §3.2.2.1: a vendor that does not understand a higher version still parses trace-id and
        // parent-id. Refusing to store one would break the event outright for every current reader.
        String future = "01-" + TRACE_ID + "-" + SPAN_ID + "-01-some-vendor-field";

        assertThatCode(() -> TraceContext.of(future)).doesNotThrowAnyException();
        assertThat(TraceContext.of(future).traceparent()).contains(future);
    }

    @Test
    @DisplayName("tracestate cannot be carried without a traceparent")
    void tracestateAloneIsRejected() {
        assertThatThrownBy(() -> new TraceContext(
            Optional.empty(), Optional.of("vendor=value")))
            .as("vendor state only means something next to a trace, and no producer should be "
                + "able to create a row no consumer can interpret")
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an empty tracestate must be absent rather than blank")
    void emptyTracestateIsRejected() {
        assertThatThrownBy(() -> new TraceContext(
            Optional.of(TraceContext.EXAMPLE_TRACEPARENT), Optional.of("")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tracestate is length-bounded but not otherwise interpreted")
    void tracestateIsBoundedNotParsed() {
        // §3.3.1.5 caps a tracestate header at 512 characters, and §7 asks consumers to check
        // length before acting. The key/value grammar is the propagator's to own, so it is carried
        // through verbatim - including things this implementation could not have validated.
        String withinLimit = "vendor=" + "v".repeat(512 - 7);
        assertThatCode(() -> new TraceContext(
            Optional.of(TraceContext.EXAMPLE_TRACEPARENT), Optional.of(withinLimit)))
            .doesNotThrowAnyException();

        assertThatThrownBy(() -> new TraceContext(
            Optional.of(TraceContext.EXAMPLE_TRACEPARENT), Optional.of("v".repeat(513))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("carrier headers are exactly the pair a propagator injects")
    void carrierHeadersCarryBothFields() {
        TraceContext withState = new TraceContext(
            Optional.of(TraceContext.EXAMPLE_TRACEPARENT), Optional.of("vendor=abc,other=def"));

        Map<String, String> headers = withState.asCarrierHeaders();

        assertThat(headers)
            .containsExactly(
                Map.entry("traceparent", TraceContext.EXAMPLE_TRACEPARENT),
                Map.entry("tracestate", "vendor=abc,other=def"));

        assertThat(TraceContext.of(TraceContext.EXAMPLE_TRACEPARENT).asCarrierHeaders())
            .as("tracestate is optional and must not be emitted empty")
            .containsOnlyKeys("traceparent");
    }
}