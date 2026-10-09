-- ==============================================================================
-- Flyway Migration V9: entitlement event immutability (PostgreSQL only)
--
-- V8 creates the stream and its column constraints. This closes what those
-- cannot express: that history may only grow.
--
-- WHY A SEPARATE FILE
--
-- It uses PostgreSQL CREATE RULE, which H2 does not implement. V8 is portable
-- and fully exercised by the automated tests; THIS script is verified only
-- against a real PostgreSQL server. It is excluded from the H2 test fixture on
-- purpose rather than silently degraded - see BaseJdbcRepositoryTest, which loads
-- V1-V4, V6 and V8 but not V5, V7 or V9.
--
-- The Java layer (JdbcEntitlementEventRepository) enforces the same rules, so the
-- application path is covered by CI; this is the defence-in-depth layer.
-- ==============================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_rules
        WHERE schemaname = current_schema() AND tablename = 'entitlement_events'
    ) THEN

        -- "Why did this customer lose access on 3 March" is only answerable if March is still here.
        CREATE RULE entitlement_events_no_update AS
            ON UPDATE TO entitlement_events
            DO INSTEAD NOTHING;

        CREATE RULE entitlement_events_no_delete AS
            ON DELETE TO entitlement_events
            DO INSTEAD NOTHING;

    END IF;
END
$$;

-- A feature may not be revoked twice in a row. A duplicate revocation is a redelivery bug that,
-- would otherwise reach the customer as a second, spurious access-revocation notice.
CREATE OR REPLACE FUNCTION entitlement_no_double_revoke() RETURNS trigger AS $$
BEGIN
    IF NEW.change_type <> 'REVOKED' THEN
        RETURN NEW;
    END IF;

    IF EXISTS (
        SELECT 1 FROM entitlement_events
        WHERE tenant_id = NEW.tenant_id
          AND customer_id = NEW.customer_id
          AND feature_key = NEW.feature_key
          AND change_type = 'REVOKED'
    ) THEN
        RAISE EXCEPTION
            'Feature % for customer %/% has already been revoked',
            NEW.feature_key, NEW.tenant_id, NEW.customer_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_entitlement_no_double_revoke ON entitlement_events;

CREATE CONSTRAINT TRIGGER trg_entitlement_no_double_revoke
    AFTER INSERT ON entitlement_events
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION entitlement_no_double_revoke();