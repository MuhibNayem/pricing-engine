-- ==============================================================================
-- Flyway Migration V1: Enterprise SaaS Pricing Engine Core Schema
-- Production-ready PostgreSQL schema with index optimization & JSONB payloads
-- ==============================================================================

-- 1. Rate Cards Table (Bi-temporal versioned catalog)
CREATE TABLE IF NOT EXISTS rate_cards (
    rate_card_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    plan_code VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    currency VARCHAR(16) NOT NULL,
    effective_from TIMESTAMP WITH TIME ZONE NOT NULL,
    effective_to TIMESTAMP WITH TIME ZONE,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    superseded_at TIMESTAMP WITH TIME ZONE,
    hierarchy_level VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    CONSTRAINT uq_rate_cards_tenant_plan_version UNIQUE (tenant_id, plan_code, version)
);

CREATE INDEX IF NOT EXISTS idx_rc_tenant_plan_eff ON rate_cards (tenant_id, plan_code, effective_from, effective_to);
CREATE INDEX IF NOT EXISTS idx_rc_global_eff ON rate_cards (plan_code, effective_from, effective_to);

-- 2. Contract Overrides Table (Negotiated enterprise contracts)
CREATE TABLE IF NOT EXISTS contract_overrides (
    contract_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    plan_code VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    effective_from TIMESTAMP WITH TIME ZONE NOT NULL,
    effective_to TIMESTAMP WITH TIME ZONE,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    superseded_at TIMESTAMP WITH TIME ZONE,
    payload_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_co_tenant_cust_plan ON contract_overrides (tenant_id, customer_id, plan_code, effective_from, effective_to);

-- 3. Customer Wallets Table (Prepaid credits wallet)
CREATE TABLE IF NOT EXISTS wallets (
    wallet_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    currency VARCHAR(16) NOT NULL,
    payload_json TEXT NOT NULL,
    CONSTRAINT uq_wallets_tenant_customer UNIQUE (tenant_id, customer_id)
);

-- 4. Wallet Transactions Table (Immutable drawdown ledger)
CREATE TABLE IF NOT EXISTS wallet_transactions (
    transaction_id VARCHAR(128) PRIMARY KEY,
    wallet_id VARCHAR(128) NOT NULL,
    grant_id VARCHAR(128) NOT NULL,
    grant_name VARCHAR(128) NOT NULL,
    calculation_id VARCHAR(128) NOT NULL,
    line_item_code VARCHAR(128),
    credits_drawn NUMERIC(24, 8) NOT NULL,
    money_amount NUMERIC(24, 8) NOT NULL,
    currency VARCHAR(16) NOT NULL,
    remaining_credits NUMERIC(24, 8) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_wt_wallet_created ON wallet_transactions (wallet_id, created_at);
CREATE INDEX IF NOT EXISTS idx_wt_calculation_id ON wallet_transactions (calculation_id);

-- 5. Customer Entitlements Table (Feature quotas & limits)
CREATE TABLE IF NOT EXISTS entitlements (
    entitlement_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    plan_code VARCHAR(64) NOT NULL,
    feature_key VARCHAR(128) NOT NULL,
    feature_type VARCHAR(64) NOT NULL,
    boolean_value BOOLEAN NOT NULL,
    quota_limit NUMERIC(24, 8),
    current_usage NUMERIC(24, 8) NOT NULL,
    is_hard_limit BOOLEAN NOT NULL,
    effective_from TIMESTAMP WITH TIME ZONE NOT NULL,
    effective_to TIMESTAMP WITH TIME ZONE,
    payload_json TEXT NOT NULL,
    CONSTRAINT uq_entitlements_tenant_cust_feature UNIQUE (tenant_id, customer_id, feature_key)
);

CREATE INDEX IF NOT EXISTS idx_ent_tenant_cust_feature ON entitlements (tenant_id, customer_id, feature_key);

-- 6. Pricing Audit Ledger Table (Evaluation audit trails & traces)
CREATE TABLE IF NOT EXISTS pricing_audit_ledger (
    calculation_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64),
    plan_code VARCHAR(64) NOT NULL,
    evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    currency VARCHAR(16) NOT NULL,
    gross_amount NUMERIC(24, 8) NOT NULL,
    discount_amount NUMERIC(24, 8) NOT NULL,
    net_amount NUMERIC(24, 8) NOT NULL,
    tax_amount NUMERIC(24, 8) NOT NULL,
    final_total NUMERIC(24, 8) NOT NULL,
    payload_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_tenant_eval ON pricing_audit_ledger (tenant_id, evaluated_at);
