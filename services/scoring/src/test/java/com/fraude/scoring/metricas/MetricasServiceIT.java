package com.fraude.scoring.metricas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.scoring.TestcontainersConfig;
import com.fraude.scoring.TokensDePrueba;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Métricas de detección (§9, ADR 0017) con datos fijos. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TokensDePrueba.class})
class MetricasServiceIT {

    private static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");
    private static final Instant T1 = T0.plusSeconds(100);

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MetricasService metricas;

    private int latencia;

    @BeforeEach
    void limpiar() {
        jdbc.execute("ALTER TABLE evaluacion DISABLE TRIGGER evaluacion_solo_insert");
        jdbc.execute("TRUNCATE evaluacion, etiqueta_verdad, outbox, lista_bloqueo");
        jdbc.execute("ALTER TABLE evaluacion ENABLE TRIGGER evaluacion_solo_insert");
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        latencia = 0;
    }

    /** Inserta una evaluación con latencias 1, 2, 3… en orden de inserción. */
    private UUID evaluacion(String decision, String monto, boolean tardia, Instant creada) {
        UUID idExterno = UUID.randomUUID();
        latencia++;
        int score = switch (decision) {
            case "RECHAZAR" -> 80;
            case "REVISAR" -> 50;
            default -> 10;
        };
        jdbc.update("""
                INSERT INTO evaluacion (id, transaccion_id, id_externo, monto, score, decision, reglas_disparadas,
                                        versiones_reglas, latencia_ms, creada_en, tardia)
                VALUES (gen_random_uuid(), gen_random_uuid(), ?, ?::numeric, ?, ?, '[]'::jsonb, '{}'::jsonb, ?, ?, ?)
                """, idExterno, monto, score, decision, latencia, Timestamp.from(creada), tardia);
        return idExterno;
    }

    private void fraude(String escenario, String decision, String monto, boolean tardia) {
        UUID id = evaluacion(decision, monto, tardia, T0.plusSeconds(latencia % 90));
        jdbc.update("INSERT INTO etiqueta_verdad VALUES (?, ?)", id, escenario);
    }

    /** 10 fraudes (8 marcados) y 100 legítimas (2 marcadas) dentro de [T0, T1). */
    private void datosFijos() {
        // CARD_TESTING: 6 fraudes, 5 marcados.
        for (int i = 0; i < 3; i++) {
            fraude("CARD_TESTING", "REVISAR", "5.00", false);
        }
        fraude("CARD_TESTING", "RECHAZAR", "8.00", false);
        fraude("CARD_TESTING", "RECHAZAR", "8.00", false);
        fraude("CARD_TESTING", "APROBAR", "3.00", false);
        // TOMA_DE_CUENTA: 4 fraudes, 3 marcados; el tardío cuenta como respaldo (ADR 0012).
        fraude("TOMA_DE_CUENTA", "RECHAZAR", "1000.00", false);
        fraude("TOMA_DE_CUENTA", "RECHAZAR", "1500.00", false);
        fraude("TOMA_DE_CUENTA", "REVISAR", "900.00", false);
        fraude("TOMA_DE_CUENTA", "RECHAZAR", "1200.00", true);
        for (int i = 0; i < 100; i++) {
            evaluacion(i < 2 ? "REVISAR" : "APROBAR", "120.00", false, T0.plusSeconds(i % 99));
        }
        // Fuera de la ventana: no debe contar.
        UUID afuera = evaluacion("APROBAR", "999.00", false, T0.minusSeconds(3600));
        jdbc.update("INSERT INTO etiqueta_verdad VALUES (?, 'COMPRA_ATIPICA')", afuera);
    }

    @Test
    void calcula_deteccion_precision_y_falsos_positivos() {
        datosFijos();

        var m = metricas.calcular(T0, T1);

        assertThat(m.evaluaciones()).isEqualTo(110);
        assertThat(m.evaluacionesTardias()).isEqualTo(1);
        assertThat(m.transaccionesPorSegundo()).isEqualByComparingTo("1.10");
        var d = m.deteccion();
        assertThat(d.fraudes()).isEqualTo(10);
        assertThat(d.fraudesMarcados()).isEqualTo(8);
        assertThat(d.legitimas()).isEqualTo(100);
        assertThat(d.legitimasMarcadas()).isEqualTo(2);
        assertThat(d.marcadas()).isEqualTo(10);
        assertThat(d.deteccion()).isEqualByComparingTo("80.00");
        assertThat(d.precision()).isEqualByComparingTo("80.00");
        assertThat(d.falsosPositivos()).isEqualByComparingTo("2.00");
        assertThat(d.montoFraudeEvitado()).isEqualByComparingTo("2516.00");
        assertThat(d.montoFraudeNoDetectado()).isEqualByComparingTo("1203.00");
    }

    @Test
    void desglose_por_escenario() {
        datosFijos();

        var porEscenario = metricas.calcular(T0, T1).porEscenario();

        assertThat(porEscenario).extracting(MetricasService.Escenario::escenario)
                .containsExactly("CARD_TESTING", "TOMA_DE_CUENTA");
        var ct = porEscenario.get(0);
        assertThat(ct.fraudes()).isEqualTo(6);
        assertThat(ct.fraudesMarcados()).isEqualTo(5);
        assertThat(ct.deteccion()).isEqualByComparingTo("83.33");
        assertThat(ct.montoFraudeEvitado()).isEqualByComparingTo("16.00");
        assertThat(ct.montoFraudeNoDetectado()).isEqualByComparingTo("3.00");
        var toma = porEscenario.get(1);
        assertThat(toma.fraudes()).isEqualTo(4);
        assertThat(toma.fraudesMarcados()).isEqualTo(3);
        assertThat(toma.deteccion()).isEqualByComparingTo("75.00");
        assertThat(toma.montoFraudeEvitado()).isEqualByComparingTo("2500.00");
        assertThat(toma.montoFraudeNoDetectado()).isEqualByComparingTo("1200.00");
    }

    @Test
    void latencia_por_percentiles() {
        datosFijos();

        var l = metricas.calcular(T0, T1).latenciaMs();

        // Latencias 1..110 (la 111, fuera de la ventana, no cuenta).
        assertThat(l.p50()).isEqualByComparingTo("55.5");
        assertThat(l.p95()).isEqualByComparingTo("104.55");
        assertThat(l.p99()).isEqualByComparingTo("108.91");
    }

    @Test
    void sin_datos_los_porcentajes_son_nulos() {
        var m = metricas.calcular(T0, T1);

        assertThat(m.evaluaciones()).isZero();
        assertThat(m.deteccion().deteccion()).isNull();
        assertThat(m.deteccion().precision()).isNull();
        assertThat(m.deteccion().falsosPositivos()).isNull();
        assertThat(m.latenciaMs().p95()).isNull();
        assertThat(m.porEscenario()).isEmpty();
    }

    // --- API ---

    private ResponseEntity<String> llamar(HttpMethod metodo, String ruta, String token, String cuerpo) {
        HttpHeaders cabeceras = TokensDePrueba.cabeceras(token);
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(ruta, metodo, new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    @Test
    void la_api_entrega_las_metricas_de_la_ventana() throws Exception {
        datosFijos();

        var r = llamar(HttpMethod.GET, "/api/v1/metricas?desde=" + T0 + "&hasta=" + T1, TokensDePrueba.AUDITOR, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode m = mapper.readTree(r.getBody());
        assertThat(m.get("desde").asText()).isEqualTo(T0.toString());
        assertThat(m.get("deteccion").get("deteccion").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(m.get("porEscenario").get(0).get("escenario").asText()).isEqualTo("CARD_TESTING");
        assertThat(llamar(HttpMethod.GET, "/api/v1/metricas?desde=" + T1 + "&hasta=" + T0, TokensDePrueba.AUDITOR, null)
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void subir_la_verdad_es_idempotente_y_solo_para_admin_o_sistema() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String cuerpo = """
                [{"idExterno": "%s", "escenario": "CARD_TESTING"}, {"idExterno": "%s", "escenario": "USO_NOCTURNO"}]
                """.formatted(a, b);

        var primera = llamar(HttpMethod.POST, "/api/v1/metricas/verdad", TokensDePrueba.ADMIN, cuerpo);
        var segunda = llamar(HttpMethod.POST, "/api/v1/metricas/verdad", "rol-SISTEMA-generador", cuerpo);

        assertThat(primera.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(primera.getBody()).get("recibidas").asInt()).isEqualTo(2);
        assertThat(mapper.readTree(primera.getBody()).get("nuevas").asInt()).isEqualTo(2);
        assertThat(mapper.readTree(segunda.getBody()).get("nuevas").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM etiqueta_verdad", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT escenario FROM etiqueta_verdad WHERE transaccion_id_externo = ?",
                String.class, b)).isEqualTo("USO_NOCTURNO");

        assertThat(llamar(HttpMethod.POST, "/api/v1/metricas/verdad", TokensDePrueba.ANALISTA, cuerpo).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(llamar(HttpMethod.POST, "/api/v1/metricas/verdad", TokensDePrueba.ADMIN,
                "[{\"idExterno\": \"no-es-uuid\", \"escenario\": \"X\"}]").getStatusCode().is4xxClientError()).isTrue();
        assertThat(llamar(HttpMethod.POST, "/api/v1/metricas/verdad", TokensDePrueba.ADMIN,
                "[{\"idExterno\": \"%s\", \"escenario\": \" \"}]".formatted(UUID.randomUUID())).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void publica_la_documentacion_openapi_sin_token() throws Exception {
        var r = http.getForEntity("/v3/api-docs", String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(r.getBody()).get("paths").has("/api/v1/metricas")).isTrue();
        assertThat(http.getForEntity("/swagger-ui/index.html", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- el motor no usa la verdad ---

    private static String solicitud(UUID idExterno) {
        // MCC 7995 (R06, 15) + dispositivo nuevo (R05, 25) = 40 → REVISAR.
        return """
                {"transaccionId": "%s", "idExterno": "%s", "tarjetaId": "%s", "clienteId": "%s",
                 "comercioId": "COM-00421", "mcc": "7995", "dispositivoHuella": "dev_x",
                 "monto": 189.90, "moneda": "PEN", "canal": "POS", "pais": "PE",
                 "latitud": -12.0931, "longitud": -77.0465, "fecha": "2026-11-06T14:30:12Z"}
                """.formatted(UUID.randomUUID(), idExterno, UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void el_motor_no_consulta_etiqueta_verdad() throws Exception {
        UUID etiquetada = UUID.randomUUID();
        jdbc.update("INSERT INTO etiqueta_verdad VALUES (?, 'TOMA_DE_CUENTA')", etiquetada);
        JsonNode conVerdad = mapper.readTree(llamar(HttpMethod.POST, "/internal/v1/evaluaciones", null,
                solicitud(etiquetada)).getBody());

        jdbc.execute("TRUNCATE etiqueta_verdad");
        JsonNode sinVerdad = mapper.readTree(llamar(HttpMethod.POST, "/internal/v1/evaluaciones", null,
                solicitud(UUID.randomUUID())).getBody());

        assertThat(conVerdad.get("decision").asText()).isEqualTo("REVISAR");
        assertThat(sinVerdad.get("decision")).isEqualTo(conVerdad.get("decision"));
        assertThat(sinVerdad.get("score")).isEqualTo(conVerdad.get("score"));
        assertThat(sinVerdad.get("reglasDisparadas")).isEqualTo(conVerdad.get("reglasDisparadas"));
    }
}
