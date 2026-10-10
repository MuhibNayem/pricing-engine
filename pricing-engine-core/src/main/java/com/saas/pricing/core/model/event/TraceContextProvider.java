package com.saas.pricing.core.model.event;

/**
 * Supplies the trace context that gets stamped onto queued outbox events.
 *
 * <p>A host implements this once; every outbox event enqueued afterwards carries whatever it
 * returns. The outbox never calls it during delivery, because re-reading the trace then would
 * overwrite the link to the request that caused the event with the link to the dispatcher's own
 * poll — which is exactly the association the field exists to preserve.</p>
 *
 * <h2>What to implement</h2>
 *
 * <p>Read whatever propagation context is already current in the calling thread and map it to
 * {@link TraceContext}. With OpenTelemetry that is roughly:</p>
 *
 * <pre>{@code
 * SpanContext span = Span.current().getSpanContext();
 * if (!span.isValid()) return TraceContext.NONE;
 * StringBuilder traceparent = new StringBuilder("00-")
 *     .append(span.getTraceId()).append('-')
 *     .append(span.getSpanId()).append('-')
 *     .append(span.getTraceFlags().asHex());
 * String tracestate = W3CTraceContextPropagator.getInstance()
 *     .getTraceState(Span.current()).toString();
 * return new TraceContext(Optional.of(traceparent.toString()), Optional.of(tracestate));
 * }</pre>
 *
 * <p>That snippet imports {@code opentelemetry-api}, which is why it lives here in a comment and not
 * in a class file: {@code pricing-engine-core} has no runtime dependencies and does not intend to
 * acquire one for the convenience of a header. The same shape works for Micrometer Tracing or a
 * hand-rolled B3 carrier.</p>
 *
 * <p>Implementations are called on the thread that is writing the event, so they may read
 * thread-bound state, and must be side-effect free.</p>
 */
@FunctionalInterface
public interface TraceContextProvider {

    /** The default: no trace is stamped and events carry {@link TraceContext#NONE}. */
    TraceContextProvider NONE = () -> TraceContext.NONE;

    /**
     * The trace context current at the moment of the call, or {@link TraceContext#NONE} when the
     * call is not part of a trace.
     */
    TraceContext current();
}