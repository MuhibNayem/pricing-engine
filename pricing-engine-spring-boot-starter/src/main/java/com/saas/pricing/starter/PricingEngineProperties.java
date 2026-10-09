package com.saas.pricing.starter;

import com.saas.pricing.core.model.invoice.InvoiceNumberScheme;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.RoundingMode;

/**
 * Configuration properties for the SaaS Pricing Engine starter.
 */
@ConfigurationProperties(prefix = "pricing.engine")
public class PricingEngineProperties {

    /**
     * Whether the pricing engine auto-configuration is enabled.
     */
    private boolean enabled = true;

    /**
     * Default ISO-4217 currency code for rate calculations.
     */
    private String defaultCurrency = "USD";

    /**
     * Whether calculation audit traces are recorded to the AuditSink.
     */
    private boolean enableAudit = true;

    /**
     * Rounding mode for financial arithmetic (default HALF_EVEN / Banker's rounding).
     */
    private RoundingMode roundingMode = RoundingMode.HALF_EVEN;

    /**
     * Audit sink implementation type.
     */
    private AuditSinkType auditSinkType = AuditSinkType.IN_MEMORY;

    /**
     * Whether high-performance in-memory caching is enabled for rate cards and FX rates.
     */
    private boolean enableCaching = true;

    /**
     * Persistence layer type: IN_MEMORY or JDBC (PostgreSQL).
     */
    private PersistenceType persistenceType = PersistenceType.IN_MEMORY;

    /**
     * Whether standalone REST endpoints are enabled.
     */
    private boolean webEnabled = true;

    /**
     * Whether a caller may submit discounts in the request body.
     *
     * <p><strong>Defaults to {@code false}.</strong> Discount authoring is a catalog and contract
     * concern, not a rating-input concern: a caller that can name its own discount can price its
     * own usage. Even with the engine's own caps (percentage is bounded to 100% and a discount is
     * clamped to the line balance), an authorised caller can zero out its own invoice through an
     * endpoint that is not meant to be a discount channel.
     *
     * <p>Enable only for trusted internal callers, for example an internal quote-preview service.
     */
    private boolean allowRequestDiscounts = false;

    /**
     * Usage metering configuration properties.
     */
    private MeteringProperties metering = new MeteringProperties();

    /**
     * Streaming and event ingestion properties.
     */
    private StreamingProperties streaming = new StreamingProperties();

    /**
     * Invoice document numbering. Legal configuration, not cosmetic: EU and UK rules require
     * sequential account-wide numbering, and other markets commonly prefer per-customer series.
     */
    private InvoiceNumberProperties invoiceNumbering = new InvoiceNumberProperties();

    public enum AuditSinkType {
        IN_MEMORY,
        VIRTUAL_THREAD,
        LOGGING,
        JDBC,
        NO_OP
    }

    public enum PersistenceType {
        IN_MEMORY,
        JDBC
    }

    public static class MeteringProperties {
        private boolean enabled = true;
        private Long allowedLatenessSeconds = null;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Long getAllowedLatenessSeconds() {
            return allowedLatenessSeconds;
        }

        public void setAllowedLatenessSeconds(Long allowedLatenessSeconds) {
            this.allowedLatenessSeconds = allowedLatenessSeconds;
        }
    }

    /** How invoice document numbers are generated. */
    public static class InvoiceNumberProperties {

        /**
         * ACCOUNT_SEQUENTIAL gives one series per tenant (INV-0001); CUSTOMER_SEQUENTIAL gives one
         * per customer behind that customer's own prefix (ACME-0001).
         */
        private InvoiceNumberScheme scheme = InvoiceNumberScheme.ACCOUNT_SEQUENTIAL;

        /** Prefix for account-level numbering. 1-12 uppercase letters or digits. */
        private String prefix = "INV";

        /** Digits in the sequence, at least 1. */
        private int padding = 4;

        /**
         * The first number a series may use. Raise this to resume a sequence another system was
         * using during a migration; never lower it below a number already issued.
         */
        private long startAt = 1L;

        public InvoiceNumberScheme getScheme() {
            return scheme;
        }

        public void setScheme(InvoiceNumberScheme scheme) {
            this.scheme = scheme;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public int getPadding() {
            return padding;
        }

        public void setPadding(int padding) {
            this.padding = padding;
        }

        public long getStartAt() {
            return startAt;
        }

        public void setStartAt(long startAt) {
            this.startAt = startAt;
        }
    }

    public static class StreamingProperties {
        private boolean enabled = true;
        private boolean asyncRatingEnabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAsyncRatingEnabled() {
            return asyncRatingEnabled;
        }

        public void setAsyncRatingEnabled(boolean asyncRatingEnabled) {
            this.asyncRatingEnabled = asyncRatingEnabled;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public void setDefaultCurrency(String defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
    }

    public boolean isEnableAudit() {
        return enableAudit;
    }

    public void setEnableAudit(boolean enableAudit) {
        this.enableAudit = enableAudit;
    }

    public RoundingMode getRoundingMode() {
        return roundingMode;
    }

    public void setRoundingMode(RoundingMode roundingMode) {
        this.roundingMode = roundingMode;
    }

    public AuditSinkType getAuditSinkType() {
        return auditSinkType;
    }

    public void setAuditSinkType(AuditSinkType auditSinkType) {
        this.auditSinkType = auditSinkType;
    }

    public boolean isEnableCaching() {
        return enableCaching;
    }

    public void setEnableCaching(boolean enableCaching) {
        this.enableCaching = enableCaching;
    }

    public PersistenceType getPersistenceType() {
        return persistenceType;
    }

    public void setPersistenceType(PersistenceType persistenceType) {
        this.persistenceType = persistenceType;
    }

    public boolean isWebEnabled() {
        return webEnabled;
    }

    public boolean isAllowRequestDiscounts() {
        return allowRequestDiscounts;
    }

    public void setAllowRequestDiscounts(boolean allowRequestDiscounts) {
        this.allowRequestDiscounts = allowRequestDiscounts;
    }

    public void setWebEnabled(boolean webEnabled) {
        this.webEnabled = webEnabled;
    }

    public MeteringProperties getMetering() {
        return metering;
    }

    public void setMetering(MeteringProperties metering) {
        this.metering = metering;
    }

    public InvoiceNumberProperties getInvoiceNumbering() {
        return invoiceNumbering;
    }

    public void setInvoiceNumbering(InvoiceNumberProperties invoiceNumbering) {
        this.invoiceNumbering = invoiceNumbering;
    }

    public StreamingProperties getStreaming() {
        return streaming;
    }

    public void setStreaming(StreamingProperties streaming) {
        this.streaming = streaming;
    }
}
