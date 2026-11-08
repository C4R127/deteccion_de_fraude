package com.fraude.casos.metricas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.casos.TestcontainersConfig;
import com.fraude.casos.TokensDePrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Casos abiertos y tiempo de resolución (§9, ADR 0017). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TokensDePrueba.class})
class MetricasCasosIT {

    private static final String RUTA = "/api/v1/metricas/casos?desde=2026-11-01T00:00:00Z&hasta=2026-11-02T00:00:00Z";

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void limpiar() {
        jdbc.execute("TRUNCATE caso, nota_caso, historial_caso, outbox CASCADE");
    }

    private void caso(String prioridad, String estado, String analista, String creado, String cerrado,
            String resolucion) {
        jdbc.update("""
                INSERT INTO caso (id, transaccion_id, tarjeta_id, cliente_id, monto, score, decision, reglas_disparadas,
                                  prioridad, estado, analista_id, creado_en, cerrado_en, resolucion)
                VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 100, 50,
                        'REVISAR', '[]'::jsonb, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?)
                """, prioridad, estado, analista, creado, cerrado, resolucion);
    }

    private ResponseEntity<String> obtener(String ruta, String token) {
        return http.exchange(ruta, HttpMethod.GET, new HttpEntity<>(TokensDePrueba.cabeceras(token)), String.class);
    }

    @Test
    void abiertos_por_estado_prioridad_y_analista_y_tiempo_de_resolucion() throws Exception {
        caso("ALTA", "NUEVO", null, "2026-11-01T10:00:00Z", null, null);
        caso("ALTA", "ASIGNADO", "analista1", "2026-11-01T10:00:00Z", null, null);
        caso("MEDIA", "EN_INVESTIGACION", "analista1", "2026-11-01T10:00:00Z", null, null);
        caso("MEDIA", "ASIGNADO", "analista2", "2026-11-01T10:00:00Z", null, null);
        // Cerrados en la ventana: 30 y 90 minutos → promedio 60.
        caso("ALTA", "CERRADO", "analista1", "2026-11-01T10:00:00Z", "2026-11-01T10:30:00Z", "FRAUDE_CONFIRMADO");
        caso("MEDIA", "CERRADO", "analista2", "2026-11-01T11:00:00Z", "2026-11-01T12:30:00Z", "FALSO_POSITIVO");
        // Cerrado fuera de la ventana: no entra en el promedio.
        caso("MEDIA", "CERRADO", "analista2", "2026-10-01T10:00:00Z", "2026-10-03T10:00:00Z", "FALSO_POSITIVO");

        var r = obtener(RUTA, TokensDePrueba.AUDITOR);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode m = mapper.readTree(r.getBody());
        assertThat(m.get("abiertos").asInt()).isEqualTo(4);
        assertThat(m.get("abiertosPorEstado").get("NUEVO").asInt()).isEqualTo(1);
        assertThat(m.get("abiertosPorEstado").get("ASIGNADO").asInt()).isEqualTo(2);
        assertThat(m.get("abiertosPorEstado").get("EN_INVESTIGACION").asInt()).isEqualTo(1);
        assertThat(m.get("abiertosPorPrioridad").get("ALTA").asInt()).isEqualTo(2);
        assertThat(m.get("abiertosPorPrioridad").get("MEDIA").asInt()).isEqualTo(2);
        JsonNode porAnalista = m.get("abiertosPorAnalista");
        assertThat(porAnalista).hasSize(3);
        assertThat(porAnalista.get(0).get("analista").asText()).isEqualTo("analista1");
        assertThat(porAnalista.get(0).get("abiertos").asInt()).isEqualTo(2);
        assertThat(porAnalista.get(2).get("analista").isNull()).isTrue(); // sin asignar
        assertThat(m.get("cerrados").asInt()).isEqualTo(2);
        assertThat(m.get("cerradosPorResolucion").get("FRAUDE_CONFIRMADO").asInt()).isEqualTo(1);
        assertThat(m.get("tiempoMedioResolucionMinutos").decimalValue()).isEqualByComparingTo("60.0");
    }

    @Test
    void sin_cierres_el_tiempo_medio_es_nulo() throws Exception {
        JsonNode m = mapper.readTree(obtener(RUTA, TokensDePrueba.ANALISTA).getBody());

        assertThat(m.get("abiertos").asInt()).isZero();
        assertThat(m.get("tiempoMedioResolucionMinutos").isNull()).isTrue();
    }
}
