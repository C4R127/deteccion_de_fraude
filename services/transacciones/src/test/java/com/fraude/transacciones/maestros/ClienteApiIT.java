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

    private void transaccion(String fecha, String monto, String decision, Integer score) {
        jdbc.update("""
                INSERT INTO transaccion (id, id_externo, tarjeta_id, cliente_id, comercio_id, monto, moneda, canal,
                                         pais, latitud, longitud, fecha, decision, score, respaldo)
                VALUES (gen_random_uuid(), gen_random_uuid(), '6f1c2d3e-0000-4000-8000-0000000000a1'::uuid, ?::uuid,
                        'COM-00421', ?::numeric, 'PEN', 'POS', 'PE', -12.093100, -77.046500, ?::timestamptz, ?, ?, false)
                """, CLIENTE, monto, fecha, decision, score);
    }

    @Test
    void ultimas_transacciones_del_cliente_de_la_mas_reciente_a_la_mas_antigua() throws Exception {
        jdbc.update("INSERT INTO tarjeta VALUES ('6f1c2d3e-0000-4000-8000-0000000000a1'::uuid, ?::uuid, "
                + "'tok_8c1f42a9', '4821', 'ACTIVA', now())", CLIENTE);
        jdbc.update("INSERT INTO comercio VALUES ('COM-00421', 'Supermercado Lima 1', '5411', 'PE', 'Lima', "
                + "-12.093100, -77.046500, false)");
        for (int i = 1; i <= 25; i++) {
            transaccion("2026-10-%02dT15:00:00Z".formatted(i), i + ".50", i == 25 ? "REVISAR" : "APROBAR", i == 25 ? 55 : 0);
        }

        var r = http.exchange("/api/v1/clientes/" + CLIENTE + "/transacciones?limite=20", HttpMethod.GET,
                new HttpEntity<>(TokensDePrueba.cabeceras(TokensDePrueba.AUDITOR)), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode lista = json(r);
        assertThat(lista).hasSize(20);
        JsonNode primera = lista.get(0);
        assertThat(primera.get("fecha").asText()).isEqualTo("2026-10-25T15:00:00Z");
        assertThat(primera.get("monto").decimalValue()).isEqualByComparingTo("25.50");
        assertThat(primera.get("decision").asText()).isEqualTo("REVISAR");
        assertThat(primera.get("score").asInt()).isEqualTo(55);
        assertThat(primera.get("ultimos4").asText()).isEqualTo("4821");
        assertThat(primera.get("comercioNombre").asText()).isEqualTo("Supermercado Lima 1");
        assertThat(primera.get("ciudad").asText()).isEqualTo("Lima");
        assertThat(primera.get("latitud").decimalValue()).isEqualByComparingTo("-12.0931");
        assertThat(primera.has("tarjetaToken")).isFalse();
        assertThat(lista.get(19).get("fecha").asText()).isEqualTo("2026-10-06T15:00:00Z");

        var porDefecto = http.exchange("/api/v1/clientes/" + CLIENTE + "/transacciones", HttpMethod.GET,
                new HttpEntity<>(TokensDePrueba.cabeceras(TokensDePrueba.ANALISTA)), String.class);
        assertThat(json(porDefecto)).hasSize(20);
        var fueraDeRango = http.exchange("/api/v1/clientes/" + CLIENTE + "/transacciones?limite=500", HttpMethod.GET,
                new HttpEntity<>(TokensDePrueba.cabeceras(TokensDePrueba.ANALISTA)), String.class);
        assertThat(fueraDeRango.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void transacciones_de_un_cliente_inexistente_responde_404() {
        var r = http.exchange("/api/v1/clientes/" + UUID.randomUUID() + "/transacciones", HttpMethod.GET,
                new HttpEntity<>(TokensDePrueba.cabeceras(TokensDePrueba.ANALISTA)), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void publica_la_documentacion_openapi_sin_token() throws Exception {
        var r = http.getForEntity("/v3/api-docs", String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(r.getBody()).get("paths").has("/api/v1/clientes/{id}")).isTrue();
        assertThat(http.getForEntity("/swagger-ui/index.html", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void cliente_inexistente_responde_404() {
        assertThat(obtener(UUID.randomUUID().toString(), TokensDePrueba.ANALISTA).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
