package com.saas.pricing.persistence.json;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.saas.pricing.core.model.PricingModel;

/**
 * Enterprise JSON (de)serializer tailored for pricing domain entities,
 * supporting polymorphically sealed PricingModel records, Java 25 records, and ISO-8601 timestamps.
 */
public final class PricingJsonMapper {

    private static final ObjectMapper MAPPER = createObjectMapper();

    private PricingJsonMapper() {}

    public static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Jdk8Module());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        mapper.setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD, com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY);

        // Register Polymorphic Mixin for PricingModel sealed interface
        mapper.addMixIn(PricingModel.class, PricingModelMixin.class);
        mapper.addMixIn(com.saas.pricing.core.model.EvaluationTrace.class, EvaluationTraceMixin.class);

        return mapper;
    }

    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    public static String toJson(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize object to JSON: " + object.getClass().getName(), e);
        }
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize JSON into " + clazz.getName(), e);
        }
    }

    abstract static class EvaluationTraceMixin {
        @com.fasterxml.jackson.annotation.JsonCreator
        EvaluationTraceMixin(
            @com.fasterxml.jackson.annotation.JsonProperty("traceId") String traceId,
            @com.fasterxml.jackson.annotation.JsonProperty("timestamp") java.time.Instant timestamp,
            @com.fasterxml.jackson.annotation.JsonProperty("steps") java.util.List<com.saas.pricing.core.model.TraceStep> steps
        ) {}

        @com.fasterxml.jackson.annotation.JsonProperty("traceId") abstract String traceId();
        @com.fasterxml.jackson.annotation.JsonProperty("timestamp") abstract java.time.Instant timestamp();
        @com.fasterxml.jackson.annotation.JsonProperty("steps") abstract java.util.List<com.saas.pricing.core.model.TraceStep> steps();
    }

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
        visible = true
    )
    @JsonSubTypes({
        @JsonSubTypes.Type(value = PricingModel.FlatFeeModel.class, name = "FLAT_FEE"),
        @JsonSubTypes.Type(value = PricingModel.PerUnitModel.class, name = "PER_UNIT"),
        @JsonSubTypes.Type(value = PricingModel.GraduatedTierModel.class, name = "GRADUATED_TIER"),
        @JsonSubTypes.Type(value = PricingModel.VolumeTierModel.class, name = "VOLUME_TIER"),
        @JsonSubTypes.Type(value = PricingModel.StairStepModel.class, name = "STAIR_STEP"),
        @JsonSubTypes.Type(value = PricingModel.DimensionalMatrixModel.class, name = "DIMENSIONAL_MATRIX"),
        @JsonSubTypes.Type(value = PricingModel.DynamicFormulaModel.class, name = "DYNAMIC_FORMULA"),
        @JsonSubTypes.Type(value = PricingModel.CompositePricingModel.class, name = "COMPOSITE"),
        @JsonSubTypes.Type(value = PricingModel.HybridModel.class, name = "HYBRID")
    })
    private interface PricingModelMixin {}
}
