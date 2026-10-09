-- ==============================================================================
-- Flyway Migration V8: entitlement lifecycle events
--
-- Entitlement state is DERIVED: current state is a replay of this stream, not a
-- column someone remembers to update. That is what makes reconciliation, rebuild
-- after a bad deploy, and "why did this customer lose access on 3 March"
-- answerable at all.
--
-- Portable SQL only; the append-only enforcement lives in V9 because H2 cannot
-- execute the PostgreSQL syntax, and silently degrading it to a no-op would
-- make the migration look like it worked.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS entitlement_events (
    event_id            VARCHAR(128) PRIMARY KEY,

    tenant_id           VARCHAR(64)  NOT NULL,
    customer_id         VARCHAR(64)  NOT NULL,
    feature_key         VARCHAR(128) NOT NULL,

    change_type         VARCHAR(32)  NOT NULL,
    feature_type        VARCHAR(32)  NOT NULL,

    -- A QUOTA_CHANGED carries the new limit; other types carry the limit at that moment.
    quota_limit         NUMERIC(24, 8),

    -- Valid time: when the change takes effect for the customer.
    effective_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    -- System time: when the change was recorded.
    recorded_at         TIMESTAMP WITH TIME ZONE NOT NULL,

    -- The state before this change, so a redelivery is identifiable as "no change".
    previous_state_json TEXT,

    reason              VARCHAR(512) NOT NULL DEFAULT '',
    payload_json        TEXT         NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_entitlement_event_type
        CHECK (change_type IN ('GRANTED', 'REVOKED', 'QUOTA_CHANGED')),

    CONSTRAINT ck_entitlement_feature_type
        CHECK (feature_type IN ('BOOLEAN', 'METERED_RECURRING', 'METERED_STATIC')),

    -- A quota change must say what the new limit is, or a replay cannot apply it.
    CONSTRAINT ck_entitlement_quota_present
        CHECK (change_type <> 'QUOTA_CHANGED' OR quota_limit IS NOT NULL),

    -- A revocation with no cause cannot be defended to a customer or an auditor.
    CONSTRAINT ck_entitlement_revocation_reason
        CHECK (change_type <> 'REVOKED' OR LENGTH(TRIM(reason)) > 0),

    -- A limit cannot be negative.
    CONSTRAINT ck_entitlement_quota_non_negative
        CHECK (quota_limit IS NULL OR quota_limit >= 0),

    -- Valid time can precede system time - that is precisely what a backdated grant is - but a
    -- record cannot be timestamped before the change it records took effect by more than the
    -- clock skew allowance, which is deliberately absent here because backdating is legitimate.
    CONSTRAINT ck_entitlement_time_present
        CHECK (effective_at IS NOT NULL AND recorded_at IS NOT NULL)
);

-- Replay reads one customer's history in (effective_at, recorded_at, event_id) order. This index
-- IS the projection's access path, so it is not merely a convenience.
CREATE INDEX IF NOT EXISTS idx_entitlement_events_replay
    ON entitlement_events (tenant_id, customer_id, effective_at, recorded_at, event_id);

-- Per-feature lookups for "when did this feature last change".
CREATE INDEX IF NOT EXISTS idx_entitlement_events_feature
    ON entitlement_events (tenant_id, customer_id, feature_key, effective_at DESC);