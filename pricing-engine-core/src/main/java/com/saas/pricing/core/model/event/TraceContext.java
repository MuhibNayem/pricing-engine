package com.saas.pricing.core.model.event;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * The W3C Trace Context propagation fields captured when an outbox event was queued.
 *
 * <h2>Why an outbox event needs this at all</h2>
 *
 * <p>The outbox is an asynchronous boundary, and trace context does not cross one on its own. The
 * HTTP request that finalises an invoice opens a trace; the event announcing that invoice is written
 * to a table and picked up later by a dispatcher, possibly in a different process, possibly minutes
 * later. Without the context stored <em>on the row</em>, the trace stops dead at the database commit
 * and the consumer's span is orphaned. That is the worst place to lose a trace: the interesting
 * question is almost always "why did this subscriber receive this event", which is precisely the
 * question the two halves of an unbroken trace answer.</p>
 *
 * <p>Every mature outbox implementation does this — a {@code traceparent} column lifted into the
 * broker message header by the relay, or an event-routing SMT doing the same. This is the piece that
 * makes those implementations possible from here, and it costs nothing at the point of use.</p>
 *
 * <h2>No OpenTelemetry dependency, deliberately</h2>
 *
 * <p>{@code pricing-engine-core} ships zero runtime dependencies, and that is a claim the SBOM
 * proves rather than a README asserts. The OTel guidance for instrumentation libraries is to depend
 * only on {@code opentelemetry-api} — but the outbox does not need the API at all. It never creates
 * a span and never starts a context; it only needs to <em>carry</em> two strings from one moment to
 * another and hand them back at dispatch time so the host's propagator can inject them.</p>
 *
 * <p>So the seam is the header value, not the SDK. A host with the OTel agent, Micrometer Tracing,
 * Brave or plain HTTP supplies a {@link TraceContextProvider} that reads whatever it already has;
 * this class has no idea any of them exist. That also keeps the wire format open: a deployment
 * standardising on B3 maps it here without a schema migration.</p>
 *
 * <h2>Validation is against the specification, not against a round trip</h2>
 *
 * <p>{@code traceparent} is validated here against the normative grammar in
 * <a href="https://www.w3.org/TR/trace-context/">W3C Trace Context</a> §3.2.2, so a malformed value
 * is refused at the point it is created instead of being persisted and silently failing to correlate
 * at the far end of a broker. The rules actually enforced:</p>
 * <ul>
 *   <li>version is 2 lowercase hex digits and must not be {@code ff} (§3.2.2.2 reserves it)</li>
 *   <li>{@code trace-id} is 32 lowercase hex and must not be all zeros (§3.2.2.3)</li>
 *   <li>{@code parent-id} is 16 lowercase hex and must not be all zeros (§3.2.2.4)</li>
 *   <li>{@code trace-flags} is 2 lowercase hex (§3.2.2.5)</li>
 *   <li>version {@code 00} is exactly 55 characters; a future version may append further
 *       {@code -}-delimited fields, and the first four are parsed as this specification defines</li>
 * </ul>
 *
 * <p>{@code tracestate} is deliberately <strong>not</strong> parsed. The specification makes
 * validating it optional ("MAY validate", "MAY discard") and leaves ordering and key rules to the
 * propagator that owns the format. Its length is bounded anyway, because §7 asks every consumer of
 * these headers to check header length before acting on the value.</p>
 *
 * @param traceparent the {@code traceparent} header value, empty when no trace was active
 * @param tracestate  the optional {@code tracestate} header value
 */
public record TraceContext(Optional<String> traceparent, Optional<String> tracestate) implements Serializable {

    /** The worked example from W3C Trace Context §3.1, used by the tests as the known-good value. */
    public static final String EXAMPLE_TRACEPARENT =
        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    /** Length of a version {@code 00} traceparent: 2 + 1 + 32 + 1 + 16 + 1 + 2. */
    private static final int VERSION_00_LENGTH = 55;

    /** §3.3.1.5: a tracestate header longer than this is invalid, and the spec asks for a bound. */
    private static final int MAX_TRACESTATE_LENGTH = 512;

    private static final String ZERO_TRACE_ID = "0".repeat(32);
    private static final String ZERO_PARENT_ID = "0".repeat(16);

    /** No trace was active when the event was queued. Not null, so callers never branch on absence. */
    public static final TraceContext NONE = new TraceContext(Optional.empty(), Optional.empty());

    public TraceContext {
        Objects.requireNonNull(traceparent, "traceparent cannot be null");
        Objects.requireNonNull(tracestate, "tracestate cannot be null");
        traceparent.ifPresent(TraceContext::requireValidTraceparent);
        if (tracestate.isPresent()) {
            String value = tracestate.get();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("tracestate must be absent rather than empty");
            }
            if (value.length() > MAX_TRACESTATE_LENGTH) {
                throw new IllegalArgumentException(
                    "tracestate is " + value.length() + " characters, over the "
                        + MAX_TRACESTATE_LENGTH + " the specification allows");
            }
        }
        // tracestate is vendor data that only means something next to a traceparent. Carrying it
        // alone would be a state no producer could sensibly create and no consumer could interpret.
        if (traceparent.isEmpty() && tracestate.isPresent()) {
            throw new IllegalArgumentException("tracestate cannot be carried without a traceparent");
        }
    }

    /** A trace context carrying only {@code traceparent}, which is the common case. */
    public static TraceContext of(String traceparent) {
        return new TraceContext(Optional.of(traceparent), Optional.empty());
    }

    /** True when there is a trace to stitch — the dispatcher skips header injection when false. */
    public boolean isPresent() {
        return traceparent.isPresent();
    }

    /** The header name/value pairs a propagator should inject when delivering the event. */
    public java.util.Map<String, String> asCarrierHeaders() {
        if (!isPresent()) {
            return java.util.Map.of();
        }
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("traceparent", traceparent.get());
        tracestate.ifPresent(value -> headers.put("tracestate", value));
        return java.util.Collections.unmodifiableMap(headers);
    }

    private static void requireValidTraceparent(String value) {
        if (value.length() < VERSION_00_LENGTH) {
            throw new IllegalArgumentException(
                "traceparent is " + value.length() + " characters, the shortest legal form is " + VERSION_00_LENGTH);
        }
        // Offsets, from "00-<32>-<16>-<02>": the three delimiters sit at 2, 35 and 52. Index 54 is
        // the final trace-flags character, not a fourth delimiter.
        if (value.charAt(2) != '-' || value.charAt(35) != '-' || value.charAt(52) != '-') {
            throw new IllegalArgumentException(
                "traceparent must be delimited by '-' at offsets 2, 35 and 52: " + value);
        }
        requireLowercaseHex(value, 0, 2, "version");
        String version = value.substring(0, 2);
        if (version.equals("ff")) {
            throw new IllegalArgumentException("traceparent version 'ff' is forbidden by the specification");
        }
        String traceId = value.substring(3, 35);
        requireLowercaseHex(value, 3, 35, "trace-id");
        if (traceId.equals(ZERO_TRACE_ID)) {
            throw new IllegalArgumentException("traceparent trace-id must not be all zeros");
        }
        requireLowercaseHex(value, 36, 52, "parent-id");
        if (value.substring(36, 52).equals(ZERO_PARENT_ID)) {
            throw new IllegalArgumentException("traceparent parent-id must not be all zeros");
        }
        requireLowercaseHex(value, 53, 55, "trace-flags");
        if (version.equals("00") && value.length() != VERSION_00_LENGTH) {
            throw new IllegalArgumentException(
                "a version '00' traceparent is exactly " + VERSION_00_LENGTH
                    + " characters; trailing fields are only legal for a future version: " + value);
        }
    }

    private static void requireLowercaseHex(String value, int from, int to, String field) {
        for (int i = from; i < to; i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                throw new IllegalArgumentException(
                    "traceparent " + field + " must be lowercase hex at offset " + i + ": " + value);
            }
        }
    }
}