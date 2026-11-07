package com.fraude.transacciones.scoring;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fraude.common.evaluacion.EvaluacionSolicitud;
import com.fraude.common.eventos.Canal;
import com.fraude.transacciones.TestcontainersConfig;
import com.fraude.transacciones.TokensDePrueba;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Riesgo "arranque en frío": transacciones calienta el cliente del scoring al arrancar con
 * solicitudes inválidas que el scoring rechaza sin efectos, y la primera evaluación real no cae en respaldo.
 */
@SpringBootTest(properties = {"calentamiento.iteraciones-minimas=20", "calentamiento.objetivo=1h"})
@Import({TestcontainersConfig.class, TokensDePrueba.class})
class CalentamientoTransaccionesIT {

    static final WireMockServer scoring = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        scoring.start();
        // El scoring real rechaza la solicitud de calentamiento (sin transaccionId) con 422.
        scoring.stubFor(WireMock.post("/internal/v1/evaluaciones")
                .withRequestBody(WireMock.containing(ScoringClient.COMERCIO_CALENTAMIENTO))
                .atPriority(1)
                .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\": 422}")));
        scoring.stubFor(WireMock.post("/internal/v1/evaluaciones").willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"score\": 55, \"decision\": \"REVISAR\", \"reglasDisparadas\": []}")));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("scoring.url", scoring::baseUrl);
        registro.add("scoring.tiempo-limite", () -> "200ms");
    }

    @AfterAll
    static void detener() {
        scoring.stop();
    }

    @Autowired
    ScoringClient cliente;

    @Test
    void al_arrancar_hace_las_llamadas_de_calentamiento_sin_evaluar() {
        // 20 en paralelo (fase 1) + 10 de a una (fase 2: con objetivo de 1 h la primera tanda basta),
        // todas inválidas (sin transaccionId) para que el scoring no evalúe nada.
        scoring.verify(30, postRequestedFor(urlEqualTo("/internal/v1/evaluaciones"))
                .withRequestBody(WireMock.matchingJsonPath("$[?(@.transaccionId == null)]"))
                .withRequestBody(WireMock.containing(ScoringClient.COMERCIO_CALENTAMIENTO)));
        scoring.verify(30, postRequestedFor(urlEqualTo("/internal/v1/evaluaciones")));
    }

    @Test
    void la_primera_evaluacion_real_no_cae_en_respaldo() {
        var resultado = cliente.evaluar(new EvaluacionSolicitud(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "COM-00421", "5411", null, new BigDecimal("10.00"), "PEN",
                Canal.POS, "PE", BigDecimal.ONE, BigDecimal.ONE, Instant.now()));

        assertThat(resultado).isPresent();
    }
}
