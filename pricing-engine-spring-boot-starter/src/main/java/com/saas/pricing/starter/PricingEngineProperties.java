package com.saas.pricing.starter;

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
     * Usage metering configuration properties.
     */
    private MeteringProperties metering = new MeteringProperties();

    /**
     * Streaming and event ingestion properties.
     */
    private StreamingProperties streaming = new StreamingProperties();

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

    public void setWebEnabled(boolean webEnabled) {
        this.webEnabled = webEnabled;
    }

    public MeteringProperties getMetering() {
        return metering;
    }

    public void setMetering(MeteringProperties metering) {
        this.metering = metering;
    }

    public StreamingProperties getStreaming() {
        return streaming;
    }

    public void setStreaming(StreamingProperties streaming) {
        this.streaming = streaming;
    }
}
