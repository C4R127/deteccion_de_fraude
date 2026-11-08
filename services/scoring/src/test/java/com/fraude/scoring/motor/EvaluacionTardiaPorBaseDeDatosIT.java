package com.fraude.scoring.motor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraude.scoring.TestcontainersConfig;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR 0012 (revisión de código): la marca tardía cuenta todo lo que hace el scoring antes de
 * guardar, no solo el cálculo de reglas. Con el pool de una conexión ocupado 500 ms, el motor
 * es rápido pero la evaluación ya no llega a tiempo: debe quedar tardía y no publicar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "scoring.tiempo-limite=200ms",
        "spring.datasource.hikari.maximum-pool-size=1",
        "outbox.publicador.habilitado=false",
        "calentamiento.habilitado=false"})
@Import(TestcontainersConfig.class)
class EvaluacionTardiaPorBaseDeDatosIT {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    private static String cuerpo(UUID tx) {
        return """
                {"transaccionId": "%s", "idExterno": "%s", "tarjetaId": "%s", "clienteId": "%s",
                 "comercioId": "COM-00421", "mcc": "7995", "dispositivoHuella": "dev_nuevo",
                 "monto": 189.90, "moneda": "PEN", "canal": "POS", "pais": "PE",
                 "latitud": -12.0931, "longitud": -77.0465, "fecha": "2026-11-06T14:30:12Z"}
                """.formatted(tx, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private Boolean tardia(UUID tx) {
        return jdbc.queryForObject("SELECT tardia FROM evaluacion WHERE transaccion_id = ?", Boolean.class, tx);
    }

    @Test
    void la_espera_por_la_base_de_datos_cuenta_para_la_marca_tardia() throws Exception {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        // Sin calentamiento la primera evaluación es lenta por sí sola: se calienta antes.
        UUID caliente = null;
        for (int i = 0; i < 30; i++) {
            caliente = UUID.randomUUID();
            http.postForEntity("/internal/v1/evaluaciones", new HttpEntity<>(cuerpo(caliente), cabeceras), String.class);
        }
        assertThat(tardia(caliente)).as("una evaluación normal ya caliente no es tardía").isFalse();

        UUID tx = UUID.randomUUID();
        String cuerpo = cuerpo(tx);

        CountDownLatch tomada = new CountDownLatch(1);
        CompletableFuture<Void> ocupada = CompletableFuture.runAsync(() -> jdbc.execute((ConnectionCallback<Object>) c -> {
            tomada.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(tomada.await(10, TimeUnit.SECONDS)).isTrue();

        var respuesta = http.postForEntity("/internal/v1/evaluaciones", new HttpEntity<>(cuerpo, cabeceras),
                String.class);
        ocupada.get(10, TimeUnit.SECONDS);

        assertThat(respuesta.getBody()).contains("REVISAR");
        assertThat(tardia(tx)).isTrue();
        assertThat(jdbc.queryForObject("SELECT latencia_ms FROM evaluacion WHERE transaccion_id = ?", Integer.class, tx))
                .isGreaterThanOrEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE payload LIKE ?", Integer.class,
                "%" + tx + "%")).isZero();
    }
}
