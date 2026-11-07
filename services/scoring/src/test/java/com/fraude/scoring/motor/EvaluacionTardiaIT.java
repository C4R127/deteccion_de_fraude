package com.fraude.scoring.motor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraude.scoring.TestcontainersConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** ADR 0012: con un límite de 0 ms toda evaluación es tardía y no se publica. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "scoring.tiempo-limite=0ms")
@Import(TestcontainersConfig.class)
class EvaluacionTardiaIT {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void una_evaluacion_tardia_se_guarda_marcada_y_no_publica() {
        UUID tx = UUID.randomUUID();
        String cuerpo = """
                {"transaccionId": "%s", "idExterno": "%s", "tarjetaId": "%s", "clienteId": "%s",
                 "comercioId": "COM-00421", "mcc": "7995", "dispositivoHuella": "dev_nuevo",
                 "monto": 189.90, "moneda": "PEN", "canal": "POS", "pais": "PE",
                 "latitud": -12.0931, "longitud": -77.0465, "fecha": "2026-11-06T14:30:12Z"}
                """.formatted(tx, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);

        var respuesta = http.postForEntity("/internal/v1/evaluaciones", new HttpEntity<>(cuerpo, cabeceras),
                String.class);

        assertThat(respuesta.getBody()).contains("REVISAR");
        assertThat(jdbc.queryForObject("SELECT tardia FROM evaluacion WHERE transaccion_id = ?", Boolean.class, tx))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE payload LIKE ?", Integer.class,
                "%" + tx + "%")).isZero();
    }
}
