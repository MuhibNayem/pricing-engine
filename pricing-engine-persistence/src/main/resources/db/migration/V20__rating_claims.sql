-- ==============================================================================
-- Flyway Migration V20: rating claims
--
-- A window's billing position. `amount` is the total rated for the window that has
-- already been drawn down; a later rating of the same window (after a late event
-- changed the aggregation) charges only the difference. A retry after a restart
-- finds the claim and charges nothing.
--
-- Without this, the in-JVM completion cache was the only memory that a window had
-- been charged: a late event produced a larger recomputation that the idempotency
-- key discarded, and a restart re-charged window from zero.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS rating_claims (
    tenant_id  VARCHAR(64)  NOT NULL,
    claim_key  VARCHAR(512) NOT NULL,
    amount     NUMERIC(24, 8) NOT NULL,
    currency   VARCHAR(16)  NOT NULL,
    charged_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_rating_claims PRIMARY KEY (tenant_id, claim_key),
    CONSTRAINT ck_rating_claim_amount CHECK (amount >= 0)
);

-- Cleanup of abandoned claims (windows that will never be rated again) reads by age.
CREATE INDEX IF NOT EXISTS idx_rating_claims_charged_at ON rating_claims (charged_at);
