package com.saas.pricing.metering.model;

/**
 * Standard usage aggregation models supported by the metering pipeline.
 */
public enum AggregationType {
    /**
     * Sum of all event values in the window (e.g. total compute seconds, bandwidth GB, tokens).
     */
    SUM,

    /**
     * Total number of events in the window (e.g. API requests count, logins count).
     */
    COUNT,

    /**
     * Maximum single event value encountered in the window (e.g. peak concurrent connections, high-water mark storage).
     */
    MAX,

    /**
     * Gauge model taking the most recent event value in the window based on event timestamp (e.g. end-of-month seat count).
     */
    LAST,

    /**
     * Count of distinct values for a specified property key across all events in the window (e.g. unique active users, unique IPs).
     */
    DISTINCT_COUNT
}
