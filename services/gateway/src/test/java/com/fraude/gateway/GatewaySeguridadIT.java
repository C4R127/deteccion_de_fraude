package com.fraude.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewaySeguridadIT.Infra.class)
class GatewaySeguridadIT {

    private static final String SISTEMA = "rol-SISTEMA-generador";
    private static final String ANALISTA = "rol-ANALISTA-frontend";
    private static final String CUERPO = "{\"idExterno\": \"7f3c2a1e-0b9d-4c55-9e1a-2d6f8b4e1c70\"}";

    static final WireMockServer transacciones = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        transacciones.start();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("rutas.transacciones", transacciones::baseUrl);
        // Límite pequeño para poder superarlo en la prueba.
        registro.add("gateway.limite.por-segundo", () -> "1");
        registro.add("gateway.limite.rafaga", () -> "3");
    }

    @AfterAll
    static void detener() {
        transacciones.stop();
    }

    /**
     * Valkey real para el limitador y un decodificador de tokens de prueba:
     * {@code "rol-<ROL>-<cliente>"} → JWT con ese rol del realm y {@code azp = cliente}.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Infra {

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> valkey() {
            return new GenericContainer<>("valkey/valkey:8.1.11-alpine").withExposedPorts(6379);
        }

        @Bean
        ReactiveJwtDecoder jwtDecoderDePrueba() {
            return token -> {
                String[] partes = token.split("-");
                if (partes.length != 3 || !partes[0].equals("rol")) {
                    return Mono.error(new BadJwtException("token de prueba inválido"));
                }
                return Mono.just(Jwt.withTokenValue(token).header("alg", "none").subject("prueba")
                        .claim("azp", partes[2]).audience(List.of("servicios"))
                        .claim("realm_access", Map.of("roles", List.of(partes[1])))
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
            };
        }
    }

    @Autowired
    WebTestClient cliente;

    @BeforeEach
    void preparar() {
        transacciones.resetAll();
        transacciones.stubFor(WireMock.post("/api/v1/transacciones").willReturn(aResponse()
                .withStatus(201).withHeader("Content-Type", "application/json")
                .withBody("{\"decision\": \"APROBAR\"}")));
        transacciones.stubFor(WireMock.post("/internal/v1/siembra").willReturn(aResponse().withStatus(200)));
    }

    private WebTestClient.ResponseSpec crear(String token) {
        var solicitud = cliente.post().uri("/api/v1/transacciones").contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            solicitud = solicitud.headers(h -> h.setBearerAuth(token));
        }
        return solicitud.bodyValue(CUERPO).exchange();
    }

    @Test
    void sin_token_devuelve_401() {
        crear(null).expectStatus().isUnauthorized();

        transacciones.verify(0, postRequestedFor(urlEqualTo("/api/v1/transacciones")));
    }

    @Test
    void token_invalido_devuelve_401() {
        crear("no-es-un-jwt-valido").expectStatus().isUnauthorized();
    }

    @Test
    void rol_analista_en_crear_transacciones_devuelve_403() {
        crear(ANALISTA).expectStatus().isForbidden();

        transacciones.verify(0, postRequestedFor(urlEqualTo("/api/v1/transacciones")));
    }

    @Test
    void rol_sistema_llega_a_transacciones_con_el_token() {
        crear("rol-SISTEMA-otrocliente").expectStatus().isCreated();

        transacciones.verify(1, postRequestedFor(urlEqualTo("/api/v1/transacciones"))
                .withHeader("Authorization", equalTo("Bearer rol-SISTEMA-otrocliente")));
    }

    @Test
    void rutas_internas_no_son_accesibles_por_el_gateway() {
        cliente.post().uri("/internal/v1/siembra").headers(h -> h.setBearerAuth("rol-SISTEMA-interno"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isNotFound();
        cliente.post().uri("/internal/v1/evaluaciones").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isNotFound();

        transacciones.verify(0, postRequestedFor(urlEqualTo("/internal/v1/siembra")));
    }

    @Test
    void superar_el_limite_devuelve_429_por_cliente() {
        List<HttpStatusCode> estados = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            estados.add(crear(SISTEMA).returnResult(String.class).getStatus());
        }

        assertThat(estados).contains(HttpStatus.CREATED, HttpStatus.TOO_MANY_REQUESTS);
        // Otro cliente OAuth tiene su propio cupo.
        crear("rol-SISTEMA-otro2").expectStatus().isCreated();
    }

    @Test
    void health_es_publico() {
        cliente.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
