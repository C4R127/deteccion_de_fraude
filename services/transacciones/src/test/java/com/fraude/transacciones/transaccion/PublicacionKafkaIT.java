package com.fraude.transacciones.transaccion;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.common.eventos.Topicos;
import com.fraude.transacciones.TestcontainersConfig;
import com.fraude.transacciones.TokensDePrueba;
import com.fraude.transacciones.outbox.OutboxStore;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TokensDePrueba.class})
class PublicacionKafkaIT {

    private static final String CLIENTE = "6f1c2d3e-0000-4000-8000-000000000001";
    private static final String TARJETA = "6f1c2d3e-0000-4000-8000-0000000000a1";
    private static final String TARJETA_BLOQUEADA = "6f1c2d3e-0000-4000-8000-0000000000b2";
    /** Tiempo de espera para confirmar que un evento NO llegó. */
    private static final Duration ESPERA_AUSENCIA = Duration.ofSeconds(3);

    static final WireMockServer scoring = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        scoring.start();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("scoring.url", scoring::baseUrl);
        // Alto para que la decisión llegue del scoring simulado y no del respaldo.
        registro.add("scoring.tiempo-limite", () -> "2s");
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
    KafkaContainer kafka;

    @MockitoSpyBean
    OutboxStore outbox;

    @BeforeEach
    void preparar() {
        scoring.resetAll();
        scoring.stubFor(WireMock.post("/internal/v1/evaluaciones").willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"score\": 55, \"decision\": \"REVISAR\", \"reglasDisparadas\": []}")));
        jdbc.execute("TRUNCATE outbox, transaccion, dispositivo, tarjeta, comercio, cliente CASCADE");
        jdbc.update("INSERT INTO cliente VALUES (?::uuid, 'Juan Perez', 'ESTANDAR', 'Lima', now())", CLIENTE);
        jdbc.update("INSERT INTO tarjeta VALUES (?::uuid, ?::uuid, 'tok_8c1f42a9', '4821', 'ACTIVA', now())",
                TARJETA, CLIENTE);
        jdbc.update("INSERT INTO tarjeta VALUES (?::uuid, ?::uuid, 'tok_bloq0001', '1111', 'BLOQUEADA', now())",
                TARJETA_BLOQUEADA, CLIENTE);
        jdbc.update("INSERT INTO comercio VALUES ('COM-00421', 'Supermercado', '5411', 'PE', 'Lima', "
                + "-12.093100, -77.046500, false)");
    }

    private ResponseEntity<String> enviar(String idExterno, String token) {
        String cuerpo = """
                {"idExterno": "%s", "tarjetaToken": "%s", "comercioId": "COM-00421",
                 "dispositivoHuella": null, "monto": 189.90, "moneda": "PEN", "canal": "POS",
                 "pais": "PE", "latitud": -12.0931, "longitud": -77.0465,
                 "fecha": "2026-11-06T14:30:12Z"}
                """.formatted(idExterno, token);
        HttpHeaders cabeceras = TokensDePrueba.cabeceras(TokensDePrueba.SISTEMA);
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/api/v1/transacciones", new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    /** Lee el tópico desde el inicio y devuelve los eventos de ese idExterno. */
    private List<ConsumerRecord<String, String>> eventosDe(String idExterno, Duration espera) throws Exception {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "prueba-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> encontrados = new ArrayList<>();
        try (var consumidor = new KafkaConsumer<String, String>(props)) {
            consumidor.subscribe(List.of(Topicos.TRANSACCIONES_CREADAS));
            Instant limite = Instant.now().plus(espera);
            while (Instant.now().isBefore(limite)) {
                for (var registro : consumidor.poll(Duration.ofMillis(200))) {
                    if (mapper.readTree(registro.value()).get("idExterno").asText().equals(idExterno)) {
                        encontrados.add(registro);
                    }
                }
            }
        }
        return encontrados;
    }

    @Test
    void publica_un_evento_por_transaccion_nueva_con_clave_tarjeta() throws Exception {
        String idExterno = UUID.randomUUID().toString();

        var respuesta = enviar(idExterno, "tok_8c1f42a9");

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var eventos = eventosDe(idExterno, Duration.ofSeconds(10));
        assertThat(eventos).hasSize(1);
        var registro = eventos.get(0);
        assertThat(registro.key()).isEqualTo(TARJETA);
        JsonNode evento = mapper.readTree(registro.value());
        assertThat(evento.get("version").asInt()).isEqualTo(1);
        assertThat(evento.get("transaccionId").asText())
                .isEqualTo(mapper.readTree(respuesta.getBody()).get("transaccionId").asText());
        assertThat(evento.get("tarjetaId").asText()).isEqualTo(TARJETA);
        assertThat(evento.get("clienteId").asText()).isEqualTo(CLIENTE);
        assertThat(evento.get("mcc").asText()).isEqualTo("5411");
        assertThat(evento.get("monto").decimalValue()).isEqualByComparingTo("189.90");
        assertThat(evento.get("fecha").asText()).isEqualTo("2026-11-06T14:30:12Z");
        assertThat(evento.get("decision").asText()).isEqualTo("REVISAR");
        assertThat(evento.get("score").asInt()).isEqualTo(55);
        assertThat(evento.get("respaldo").asBoolean()).isFalse();
        assertThat(registro.headers().lastHeader("__TypeId__")).as("sin cabeceras de tipo Java").isNull();
    }

    @Test
    void transaccion_repetida_no_se_publica_de_nuevo() throws Exception {
        String idExterno = UUID.randomUUID().toString();

        assertThat(enviar(idExterno, "tok_8c1f42a9").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(enviar(idExterno, "tok_8c1f42a9").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(eventosDe(idExterno, Duration.ofSeconds(10).plus(ESPERA_AUSENCIA))).hasSize(1);
    }

    @Test
    void tarjeta_bloqueada_tambien_se_publica() throws Exception {
        String idExterno = UUID.randomUUID().toString();

        enviar(idExterno, "tok_bloq0001");

        var eventos = eventosDe(idExterno, Duration.ofSeconds(10));
        assertThat(eventos).hasSize(1);
        JsonNode evento = mapper.readTree(eventos.get(0).value());
        assertThat(evento.get("decision").asText()).isEqualTo("RECHAZAR");
        assertThat(evento.get("score").isNull()).isTrue();
    }

    @Test
    void si_falla_el_guardado_del_outbox_no_queda_ni_la_transaccion_ni_el_evento() throws Exception {
        String idExterno = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("fallo simulado al guardar el outbox"))
                .when(outbox).guardar(any(), any(), contains(idExterno));

        var respuesta = enviar(idExterno, "tok_8c1f42a9");

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaccion", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE payload::text LIKE ?", Integer.class,
                "%" + idExterno + "%")).isZero();
        assertThat(eventosDe(idExterno, ESPERA_AUSENCIA)).isEmpty();
    }

    @Test
    void el_evento_se_guarda_en_el_outbox_con_la_transaccion_y_se_marca_enviado() throws Exception {
        String idExterno = UUID.randomUUID().toString();

        enviar(idExterno, "tok_8c1f42a9");

        var fila = jdbc.queryForMap("SELECT topico, clave FROM outbox WHERE payload::text LIKE ?",
                "%" + idExterno + "%");
        assertThat(fila).containsEntry("topico", "transacciones.creadas").containsEntry("clave", TARJETA);
        assertThat(eventosDe(idExterno, Duration.ofSeconds(10))).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT enviado_en IS NOT NULL FROM outbox WHERE payload::text LIKE ?",
                Boolean.class, "%" + idExterno + "%")).isTrue();
    }
}
