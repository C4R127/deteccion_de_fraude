package com.fraude.transacciones.transaccion;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.transacciones.TestcontainersConfig;
import com.fraude.transacciones.TokensDePrueba;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TokensDePrueba.class})
class TransaccionApiIT {

    private static final String RUTA = "/api/v1/transacciones";
    private static final String EVALUACIONES = "/internal/v1/evaluaciones";
    private static final String CLIENTE = "6f1c2d3e-0000-4000-8000-000000000001";
    private static final String TARJETA = "6f1c2d3e-0000-4000-8000-0000000000a1";
    private static final String TARJETA_BLOQUEADA = "6f1c2d3e-0000-4000-8000-0000000000b2";

    static final WireMockServer scoring = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        scoring.start();
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
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    com.fraude.transacciones.scoring.ScoringClient scoringClient;

    private static boolean calentado;

    /**
     * La primera llamada HTTP del proceso (carga de clases, primera conexión) puede superar
     * los 200 ms y caer en respaldo; se hace una vez antes de las pruebas para que el
     * resultado no dependa del orden de ejecución.
     */
    private void calentarClienteScoring() {
        if (calentado) {
            return;
        }
        scoringRevisar();
        for (int i = 0; i < 3; i++) {
            scoringClient.evaluar(new com.fraude.common.evaluacion.EvaluacionSolicitud(UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "COM-00421", "5411", null,
                    java.math.BigDecimal.TEN, "PEN", com.fraude.common.eventos.Canal.POS, "PE",
                    java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.time.Instant.now()));
        }
        calentado = true;
    }

    @BeforeEach
    void preparar() {
        calentarClienteScoring();
        scoring.resetAll();
        jdbc.execute("TRUNCATE transaccion, dispositivo, tarjeta, comercio, cliente CASCADE");
        jdbc.update("INSERT INTO cliente VALUES (?::uuid, 'Juan Perez', 'ESTANDAR', 'Lima', now())", CLIENTE);
        jdbc.update("INSERT INTO tarjeta VALUES (?::uuid, ?::uuid, 'tok_8c1f42a9', '4821', 'ACTIVA', now())",
                TARJETA, CLIENTE);
        jdbc.update("INSERT INTO tarjeta VALUES (?::uuid, ?::uuid, 'tok_bloq0001', '1111', 'BLOQUEADA', now())",
                TARJETA_BLOQUEADA, CLIENTE);
        jdbc.update("INSERT INTO comercio VALUES ('COM-00421', 'Supermercado', '5411', 'PE', 'Lima', "
                + "-12.093100, -77.046500, false)");
        jdbc.update("INSERT INTO comercio VALUES ('COM-00900', 'Tienda en linea', '5969', 'PE', 'Lima', "
                + "null, null, true)");
    }

    private static void scoringResponde(String cuerpo, int retrasoMs) {
        scoring.stubFor(WireMock.post(EVALUACIONES).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withFixedDelay(retrasoMs)
                .withBody(cuerpo)));
    }

    private static void scoringRevisar() {
        scoringResponde("""
                {"score": 55, "decision": "REVISAR",
                 "reglasDisparadas": [{"codigo": "R01", "puntos": 40, "detalle": "monto alto"},
                                      {"codigo": "R06", "puntos": 15, "detalle": "rubro de riesgo"}]}
                """, 0);
    }

    private static String cuerpoPos(String idExterno) {
        return """
                {"idExterno": "%s", "tarjetaToken": "tok_8c1f42a9", "comercioId": "COM-00421",
                 "dispositivoHuella": null, "monto": 189.90, "moneda": "PEN", "canal": "POS",
                 "pais": "PE", "latitud": -12.0931, "longitud": -77.0465,
                 "fecha": "2026-11-06T14:30:12Z"}
                """.formatted(idExterno);
    }

    private ResponseEntity<String> enviar(String cuerpo) {
        HttpHeaders cabeceras = TokensDePrueba.cabeceras(TokensDePrueba.SISTEMA);
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(RUTA, new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    private JsonNode json(ResponseEntity<String> respuesta) throws Exception {
        return mapper.readTree(respuesta.getBody());
    }

    /** Llamadas al scoring de esta transacción (ignora respuestas tardías de otras pruebas). */
    private static RequestPatternBuilder evaluacionDe(String idExterno) {
        return postRequestedFor(urlEqualTo(EVALUACIONES))
                .withRequestBody(matchingJsonPath("$.idExterno", equalTo(idExterno)));
    }

    private int filas() {
        return jdbc.queryForObject("SELECT count(*) FROM transaccion", Integer.class);
    }

    @Test
    void crea_transaccion_y_devuelve_decision_del_scoring() throws Exception {
        scoringRevisar();
        String idExterno = UUID.randomUUID().toString();

        var respuesta = enviar(cuerpoPos(idExterno));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode cuerpo = json(respuesta);
        assertThat(cuerpo.get("decision").asText()).isEqualTo("REVISAR");
        assertThat(cuerpo.get("score").asInt()).isEqualTo(55);
        assertThat(cuerpo.get("respaldo").asBoolean()).isFalse();
        String transaccionId = cuerpo.get("transaccionId").asText();

        var fila = jdbc.queryForMap("SELECT * FROM transaccion WHERE id = ?::uuid", transaccionId);
        assertThat(fila.get("decision")).isEqualTo("REVISAR");
        assertThat(fila.get("score")).isEqualTo(55);
        assertThat(fila.get("respaldo")).isEqualTo(false);
        assertThat(fila.get("cliente_id").toString()).isEqualTo(CLIENTE);
        assertThat(fila.get("monto").toString()).isEqualTo("189.90");

        scoring.verify(1, evaluacionDe(idExterno).withRequestBody(equalToJson("""
                {"transaccionId": "%s", "idExterno": "%s", "tarjetaId": "%s", "clienteId": "%s",
                 "comercioId": "COM-00421", "mcc": "5411", "dispositivoHuella": null,
                 "monto": 189.90, "moneda": "PEN", "canal": "POS", "pais": "PE",
                 "latitud": -12.0931, "longitud": -77.0465, "fecha": "2026-11-06T14:30:12Z"}
                """.formatted(transaccionId, idExterno, TARJETA, CLIENTE))));
    }

    @Test
    void id_externo_repetido_devuelve_la_misma_respuesta() throws Exception {
        scoringRevisar();
        String idExterno = UUID.randomUUID().toString();
        String cuerpo = cuerpoPos(idExterno);

        var primera = enviar(cuerpo);
        var segunda = enviar(cuerpo);

        assertThat(primera.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(segunda.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(segunda)).isEqualTo(json(primera));
        assertThat(filas()).isEqualTo(1);
        scoring.verify(1, evaluacionDe(idExterno));
    }

    @Test
    void id_externo_concurrente_crea_una_sola_transaccion() throws Exception {
        scoringResponde("""
                {"score": 55, "decision": "REVISAR", "reglasDisparadas": []}
                """, 50);
        String idExterno = UUID.randomUUID().toString();
        String cuerpo = cuerpoPos(idExterno);
        int hilos = 10;
        ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futuros = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            Callable<ResponseEntity<String>> tarea = () -> {
                salida.await();
                return enviar(cuerpo);
            };
            futuros.add(ejecutor.submit(tarea));
        }

        salida.countDown();
        List<ResponseEntity<String>> respuestas = new ArrayList<>();
        for (var futuro : futuros) {
            respuestas.add(futuro.get());
        }
        ejecutor.shutdown();

        assertThat(filas()).isEqualTo(1);
        assertThat(respuestas).extracting(ResponseEntity::getStatusCode)
                .containsOnly(HttpStatus.CREATED, HttpStatus.OK)
                .filteredOn(HttpStatus.CREATED::equals).hasSize(1);
        JsonNode primera = json(respuestas.get(0));
        assertThat(primera.get("decision").asText()).isEqualTo("REVISAR");
        assertThat(primera.get("respaldo").asBoolean()).isFalse();
        for (var respuesta : respuestas) {
            assertThat(json(respuesta)).isEqualTo(primera);
        }
        scoring.verify(1, evaluacionDe(idExterno));
    }

    @Test
    void scoring_lento_aplica_respaldo() throws Exception {
        scoringResponde("""
                {"score": 90, "decision": "RECHAZAR", "reglasDisparadas": []}
                """, 1000);

        long inicio = System.nanoTime();
        var respuesta = enviar(cuerpoPos(UUID.randomUUID().toString()));
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ms).isLessThan(500);
        JsonNode cuerpo = json(respuesta);
        assertThat(cuerpo.get("decision").asText()).isEqualTo("APROBAR");
        assertThat(cuerpo.get("respaldo").asBoolean()).isTrue();
        assertThat(cuerpo.get("score").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT respaldo FROM transaccion", Boolean.class)).isTrue();
    }

    @Test
    void scoring_caido_aplica_respaldo() throws Exception {
        scoring.stubFor(WireMock.post(EVALUACIONES).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        var respuesta = enviar(cuerpoPos(UUID.randomUUID().toString()));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode cuerpo = json(respuesta);
        assertThat(cuerpo.get("decision").asText()).isEqualTo("APROBAR");
        assertThat(cuerpo.get("respaldo").asBoolean()).isTrue();
        assertThat(cuerpo.get("score").isNull()).isTrue();
    }

    @Test
    void scoring_con_error_http_aplica_respaldo() throws Exception {
        scoring.stubFor(WireMock.post(EVALUACIONES).willReturn(aResponse().withStatus(503)));

        var respuesta = enviar(cuerpoPos(UUID.randomUUID().toString()));

        assertThat(json(respuesta).get("respaldo").asBoolean()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "monto cero          | \"monto\": 189.90             | \"monto\": 0",
        "monto negativo      | \"monto\": 189.90             | \"monto\": -5.00",
        "tres decimales      | \"monto\": 189.90             | \"monto\": 189.901",
        "moneda USD          | \"moneda\": \"PEN\"           | \"moneda\": \"USD\"",
        "canal desconocido   | \"canal\": \"POS\"            | \"canal\": \"WEB\"",
        "POS sin coordenadas | \"latitud\": -12.0931         | \"latitud\": null",
        "sin idExterno       | \"idExterno\": \"7f3c2a1e-0b9d-4c55-9e1a-2d6f8b4e1c70\" | \"idExterno\": null",
    })
    void validaciones_devuelven_422(String caso, String original, String reemplazo) throws Exception {
        String idExterno = "7f3c2a1e-0b9d-4c55-9e1a-2d6f8b4e1c70";
        String cuerpo = cuerpoPos(idExterno);
        String invalido = cuerpo.replace(original, reemplazo);
        assertThat(invalido).as("el reemplazo debe aplicarse").isNotEqualTo(cuerpo);

        var respuesta = enviar(invalido);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(respuesta.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(filas()).isZero();
        scoring.verify(0, evaluacionDe(idExterno));
    }

    @Test
    void ecommerce_con_coordenadas_devuelve_422() throws Exception {
        String cuerpo = cuerpoPos(UUID.randomUUID().toString())
                .replace("\"COM-00421\"", "\"COM-00900\"")
                .replace("\"canal\": \"POS\"", "\"canal\": \"ECOMMERCE\"");

        var respuesta = enviar(cuerpo);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void ecommerce_sin_coordenadas_es_valida() throws Exception {
        scoringRevisar();
        String cuerpo = cuerpoPos(UUID.randomUUID().toString())
                .replace("\"COM-00421\"", "\"COM-00900\"")
                .replace("\"canal\": \"POS\"", "\"canal\": \"ECOMMERCE\"")
                .replace("\"latitud\": -12.0931", "\"latitud\": null")
                .replace("\"longitud\": -77.0465", "\"longitud\": null")
                .replace("\"dispositivoHuella\": null", "\"dispositivoHuella\": \"dev_0a1b2c3d4e5f\"");

        var respuesta = enviar(cuerpo);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void tarjeta_o_comercio_inexistente_devuelve_404() throws Exception {
        var sinTarjeta = enviar(cuerpoPos(UUID.randomUUID().toString()).replace("tok_8c1f42a9", "tok_noexiste"));
        var sinComercio = enviar(cuerpoPos(UUID.randomUUID().toString()).replace("COM-00421", "COM-99999"));

        assertThat(sinTarjeta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(sinComercio.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(filas()).isZero();
    }

    @Test
    void tarjeta_bloqueada_se_rechaza_sin_llamar_al_scoring() throws Exception {
        scoringRevisar();
        String idExterno = UUID.randomUUID().toString();
        String cuerpo = cuerpoPos(idExterno).replace("tok_8c1f42a9", "tok_bloq0001");

        var respuesta = enviar(cuerpo);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode json = json(respuesta);
        assertThat(json.get("decision").asText()).isEqualTo("RECHAZAR");
        assertThat(json.get("score").isNull()).isTrue();
        assertThat(json.get("respaldo").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT decision FROM transaccion", String.class)).isEqualTo("RECHAZAR");
        scoring.verify(0, evaluacionDe(idExterno));
    }

    private ResponseEntity<String> enviarCon(HttpHeaders cabeceras, String cuerpo) {
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(RUTA, new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    @Test
    void sin_token_devuelve_401() {
        var respuesta = enviarCon(new HttpHeaders(), cuerpoPos(UUID.randomUUID().toString()));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(filas()).isZero();
    }

    @Test
    void rol_analista_no_puede_crear_transacciones() {
        var respuesta = enviarCon(TokensDePrueba.cabeceras(TokensDePrueba.ANALISTA),
                cuerpoPos(UUID.randomUUID().toString()));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(filas()).isZero();
    }

    @Test
    void las_metricas_del_outbox_se_leen_sin_token() {
        var respuesta = http.getForEntity("/actuator/metrics/outbox.pendientes", String.class);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).contains("outbox.pendientes");
    }
}
