package com.fraude.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Riesgo "arranque en frío": el scoring se calienta al arrancar sin efectos visibles y la
 * primera evaluación real no queda tardía.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "calentamiento.iteraciones-minimas=25")
@Import(TestcontainersConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CalentamientoScoringIT {

    @Autowired
    CalentamientoScoring calentamiento;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry metricas;

    @Autowired
    TestRestTemplate http;

    /** Primero: comprueba el estado justo después del arranque, antes de cualquier evaluación real. */
    @Test
    @Order(1)
    void se_calienta_al_arrancar_sin_guardar_evaluaciones_ni_eventos_ni_metricas() {
        assertThat(calentamiento.iteracionesHechas()).isGreaterThanOrEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evaluacion", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isZero();
        assertThat(metricas.get("scoring.evaluacion.latencia").timer().count()).isZero();
    }

    @Test
    @Order(2)
    void la_primera_evaluacion_real_no_es_tardia() {
        UUID tx = UUID.randomUUID();
        String cuerpo = """
                {"transaccionId": "%s", "idExterno": "%s", "tarjetaId": "%s", "clienteId": "%s",
                 "comercioId": "COM-00421", "mcc": "5411", "dispositivoHuella": null,
                 "monto": 189.90, "moneda": "PEN", "canal": "POS", "pais": "PE",
                 "latitud": -12.0931, "longitud": -77.0465, "fecha": "2026-11-06T14:30:12Z"}
                """.formatted(tx, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);

        http.postForEntity("/internal/v1/evaluaciones", new HttpEntity<>(cuerpo, cabeceras), String.class);

        assertThat(jdbc.queryForObject("SELECT tardia FROM evaluacion WHERE transaccion_id = ?", Boolean.class, tx))
                .isFalse();
    }
}
