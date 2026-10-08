-- ==============================================================================
-- Flyway Migration V2: Enterprise Usage Metering & Stream Ingestion Schema
-- Production-ready PostgreSQL schema for raw usage events and window aggregations
-- ==============================================================================

-- 1. Meter Events Table (Raw ingested usage events with idempotency)
CREATE TABLE IF NOT EXISTS meter_events (
    event_id VARCHAR(128) PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64),
    meter_code VARCHAR(128) NOT NULL,
    event_value NUMERIC(24, 8) NOT NULL,
    event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    properties_json TEXT NOT NULL,
    CONSTRAINT uq_meter_events_tenant_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_me_tenant_meter_ts ON meter_events (tenant_id, meter_code, event_timestamp);
CREATE INDEX IF NOT EXISTS idx_me_tenant_cust_ts ON meter_events (tenant_id, customer_id, event_timestamp);

-- 2. Meter Aggregations Table (Materialized time-window usage aggregations)
CREATE TABLE IF NOT EXISTS meter_aggregations (
    aggregation_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    meter_code VARCHAR(128) NOT NULL,
    aggregation_type VARCHAR(64) NOT NULL,
    window_start TIMESTAMP WITH TIME ZONE NOT NULL,
    window_end TIMESTAMP WITH TIME ZONE NOT NULL,
    aggregated_value NUMERIC(24, 8) NOT NULL,
    event_count BIGINT NOT NULL,
    last_event_time TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_meter_agg_window UNIQUE (tenant_id, customer_id, meter_code, window_start, window_end)
);

CREATE INDEX IF NOT EXISTS idx_ma_tenant_window ON meter_aggregations (tenant_id, window_start, window_end);
