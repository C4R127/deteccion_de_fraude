package com.fraude.transacciones.maestros;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.transacciones.TestcontainersConfig;
import com.fraude.transacciones.TokensDePrueba;
import java.util.UUID;
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

/** {@code GET /api/v1/clientes/{id}}: el nombre va enmascarado para AUDITOR (§10, ADR 0016). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TokensDePrueba.class})
class ClienteApiIT {

    private static final String CLIENTE = "6f1c2d3e-0000-4000-8000-0000000000c1";

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void preparar() {
        jdbc.execute("TRUNCATE transaccion, dispositivo, tarjeta, comercio, cliente CASCADE");
        jdbc.update("INSERT INTO cliente VALUES (?::uuid, 'Juan Perez', 'PREMIUM', 'Cusco', "
                + "'2024-03-01T00:00:00Z')", CLIENTE);
    }

    private ResponseEntity<String> obtener(String id, String token) {
        return http.exchange("/api/v1/clientes/" + id, HttpMethod.GET,
                new HttpEntity<>(TokensDePrueba.cabeceras(token)), String.class);
    }

    private JsonNode json(ResponseEntity<String> r) throws Exception {
        return mapper.readTree(r.getBody());
    }

    @Test
    void el_analista_ve_el_nombre_completo() throws Exception {
        var r = obtener(CLIENTE, TokensDePrueba.ANALISTA);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(r).get("id").asText()).isEqualTo(CLIENTE);
        assertThat(json(r).get("nombre").asText()).isEqualTo("Juan Perez");
        assertThat(json(r).get("segmento").asText()).isEqualTo("PREMIUM");
        assertThat(json(r).get("ciudadResidencia").asText()).isEqualTo("Cusco");
        assertThat(json(r).get("fechaAlta").asText()).isEqualTo("2024-03-01T00:00:00Z");
        assertThat(json(obtener(CLIENTE, TokensDePrueba.SUPERVISOR)).get("nombre").asText()).isEqualTo("Juan Perez");
    }

    @Test
    void el_auditor_ve_el_nombre_enmascarado() throws Exception {
        var r = obtener(CLIENTE, TokensDePrueba.AUDITOR);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(r).get("nombre").asText()).isEqualTo("J*** P***");
        assertThat(r.getBody()).doesNotContain("Juan").doesNotContain("Perez");
    }

    @Test
    void el_cliente_oauth_del_generador_no_lee_clientes() {
        assertThat(obtener(CLIENTE, TokensDePrueba.SISTEMA).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void cliente_inexistente_responde_404() {
        assertThat(obtener(UUID.randomUUID().toString(), TokensDePrueba.ANALISTA).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
