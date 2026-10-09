-- ==============================================================================
-- Flyway Migration V14: subscriptions
--
-- A subscription row holds CURRENT state, unlike every other table here, which is
-- append-only. The history of transitions lives in the outbox, which announces
-- each one.
--
-- This table is not optional infrastructure: a cancelled subscription that is not
-- persisted returns to life when the process restarts, so the customer keeps
-- access they stopped paying for and a credit note already issued can never be
-- reconciled.
--
-- Portable SQL only; PostgreSQL-only guards live in V15.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS subscriptions (
    subscription_id       VARCHAR(128) PRIMARY KEY,

    tenant_id             VARCHAR(64)  NOT NULL,
    customer_id           VARCHAR(64)  NOT NULL,
    plan_code             VARCHAR(64)  NOT NULL,

    status                VARCHAR(32)  NOT NULL,

    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    current_period_start  TIMESTAMP WITH TIME ZONE NOT NULL,
    current_period_end    TIMESTAMP WITH TIME ZONE NOT NULL,

    trial_ends_at         TIMESTAMP WITH TIME ZONE,
    cancel_at_period_end  BOOLEAN      NOT NULL DEFAULT FALSE,
    canceled_at           TIMESTAMP WITH TIME ZONE,
    paused_at             TIMESTAMP WITH TIME ZONE,

    payload_json          TEXT         NOT NULL DEFAULT '{}',
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_subscription_customer UNIQUE (tenant_id, customer_id, subscription_id),

    CONSTRAINT ck_subscription_status
        CHECK (status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'PAUSED', 'CANCELED', 'EXPIRED')),

    CONSTRAINT ck_subscription_period
        CHECK (current_period_end > current_period_start),

    -- A trial with no end date can never become billable, so it is refused at the boundary.
    CONSTRAINT ck_subscription_trial_has_end
        CHECK (status <> 'TRIALING' OR trial_ends_at IS NOT NULL),

    -- Terminal states are absorbing: they must record when they ended.
    CONSTRAINT ck_subscription_terminal_records_end
        CHECK ((status IN ('CANCELED', 'EXPIRED')) = (canceled_at IS NOT NULL)),

    -- ...and a live subscription must not carry one.
    CONSTRAINT ck_subscription_live_has_no_end
        CHECK (status IN ('CANCELED', 'EXPIRED') OR canceled_at IS NULL),

    CONSTRAINT ck_subscription_cancel_flag
        CHECK (NOT cancel_at_period_end OR status NOT IN ('CANCELED', 'EXPIRED')),

    -- A cancellation already taken effect cannot still be queued for a future boundary.
    CONSTRAINT ck_subscription_pause_recorded
        CHECK (status <> 'PAUSED' OR paused_at IS NOT NULL)
);

-- Renewal scans by billing-period end. A plain index, not a partial one: partial indexes are not
-- portable to the H2 build the test suite uses, and the renewal query filters on status itself.
CREATE INDEX IF NOT EXISTS idx_subscriptions_renewal
    ON subscriptions (tenant_id, current_period_end);

CREATE INDEX IF NOT EXISTS idx_subscriptions_customer
    ON subscriptions (tenant_id, customer_id);

-- Trials due to convert into billable subscriptions.
CREATE INDEX IF NOT EXISTS idx_subscriptions_trial_end
    ON subscriptions (tenant_id, trial_ends_at);