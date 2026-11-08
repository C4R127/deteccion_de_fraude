package com.fraude.transacciones.metricas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.transacciones.TestcontainersConfig;
import com.fraude.transacciones.TokensDePrueba;
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

/** Volumen, decisiones y respaldos de transacciones (§9, ADR 0017). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TokensDePrueba.class})
class MetricasTransaccionesIT {

    private static final String CLIENTE = "6f1c2d3e-0000-4000-8000-0000000000c1";
    private static final String TARJETA = "6f1c2d3e-0000-4000-8000-0000000000a1";
    private static final String RUTA = "/api/v1/metricas/transacciones?desde=2026-11-01T12:00:00Z&hasta=2026-11-01T12:03:00Z";

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void preparar() {
        jdbc.execute("TRUNCATE transaccion, dispositivo, tarjeta, comercio, cliente CASCADE");
        jdbc.update("INSERT INTO cliente VALUES (?::uuid, 'Juan Perez', 'ESTANDAR', 'Lima', now())", CLIENTE);
        jdbc.update("INSERT INTO tarjeta VALUES (?::uuid, ?::uuid, 'tok_8c1f42a9', '4821', 'ACTIVA', now())",
                TARJETA, CLIENTE);
        jdbc.update("INSERT INTO comercio VALUES ('COM-00421', 'Supermercado', '5411', 'PE', 'Lima', "
                + "-12.093100, -77.046500, false)");
    }

    /** La ventana usa la hora de registro ({@code creada_en}), no la fecha de la transacción. */
    private void transaccion(String creada, String decision, Integer score, boolean respaldo) {
        jdbc.update("""
                INSERT INTO transaccion (id, id_externo, tarjeta_id, cliente_id, comercio_id, monto, moneda, canal,
                                         pais, fecha, decision, score, respaldo, creada_en)
                VALUES (gen_random_uuid(), gen_random_uuid(), ?::uuid, ?::uuid, 'COM-00421', 10, 'PEN', 'POS', 'PE',
                        '2026-10-01T00:00:00Z', ?, ?, ?, ?::timestamptz)
                """, TARJETA, CLIENTE, decision, score, respaldo, creada);
    }

    private ResponseEntity<String> obtener(String ruta, String token) {
        return http.exchange(ruta, HttpMethod.GET, new HttpEntity<>(TokensDePrueba.cabeceras(token)), String.class);
    }

    @Test
    void volumen_por_minuto_decisiones_y_respaldos() throws Exception {
        transaccion("2026-11-01T12:00:10Z", "APROBAR", 0, false);
        transaccion("2026-11-01T12:00:50Z", "APROBAR", null, true);
        transaccion("2026-11-01T12:00:59Z", "REVISAR", 55, false);
        transaccion("2026-11-01T12:02:30Z", "RECHAZAR", 80, false);
        transaccion("2026-11-01T12:02:31Z", "RECHAZAR", null, false); // tarjeta bloqueada (ADR 0007)
        transaccion("2026-11-01T12:02:40Z", null, null, false);        // sin decisión todavía
        transaccion("2026-11-01T12:05:00Z", "APROBAR", 0, false);      // fuera de la ventana

        var r = obtener(RUTA, TokensDePrueba.AUDITOR);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode m = mapper.readTree(r.getBody());
        assertThat(m.get("total").asInt()).isEqualTo(6);
        assertThat(m.get("respaldos").asInt()).isEqualTo(1);
        assertThat(m.get("tarjetaBloqueada").asInt()).isEqualTo(1);
        assertThat(m.get("pendientes").asInt()).isEqualTo(1);
        assertThat(m.get("porDecision").get("APROBAR").asInt()).isEqualTo(2);
        assertThat(m.get("porDecision").get("REVISAR").asInt()).isEqualTo(1);
        assertThat(m.get("porDecision").get("RECHAZAR").asInt()).isEqualTo(2);
        JsonNode minutos = m.get("porMinuto");
        assertThat(minutos).hasSize(2);
        assertThat(minutos.get(0).get("minuto").asText()).isEqualTo("2026-11-01T12:00:00Z");
        assertThat(minutos.get(0).get("total").asInt()).isEqualTo(3);
        assertThat(minutos.get(0).get("aprobar").asInt()).isEqualTo(2);
        assertThat(minutos.get(0).get("revisar").asInt()).isEqualTo(1);
        assertThat(minutos.get(0).get("respaldos").asInt()).isEqualTo(1);
        assertThat(minutos.get(1).get("minuto").asText()).isEqualTo("2026-11-01T12:02:00Z");
        assertThat(minutos.get(1).get("rechazar").asInt()).isEqualTo(2);
        assertThat(minutos.get(1).get("total").asInt()).isEqualTo(3);
    }

    @Test
    void solo_roles_de_lectura_y_ventana_valida() {
        assertThat(obtener(RUTA, TokensDePrueba.SISTEMA).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(obtener("/api/v1/metricas/transacciones?desde=2026-11-02T00:00:00Z&hasta=2026-11-01T00:00:00Z",
                TokensDePrueba.ANALISTA).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(obtener("/api/v1/metricas/transacciones", TokensDePrueba.ANALISTA).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
