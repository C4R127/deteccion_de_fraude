package com.fraude.common.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Configuración JSON común a eventos y APIs: {@code Instant} en ISO-8601 UTC
 * ({@code "2026-11-06T14:30:12Z"}), {@code BigDecimal} como número sin perder la escala y
 * tolerancia a campos nuevos de versiones futuras de un evento.
 */
public final class JsonFraude {

    private JsonFraude() {
    }

    public static ObjectMapper objectMapper() {
        return configurar(JsonMapper.builder()).build();
    }

    /** Aplica la configuración común a un builder (por ejemplo, el de Spring Boot). */
    public static JsonMapper.Builder configurar(JsonMapper.Builder builder) {
        return builder
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
    }
}
