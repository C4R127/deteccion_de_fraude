package com.fraude.scoring.motor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.common.eventos.Topicos;
import com.fraude.scoring.TestcontainersConfig;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class EvaluacionApiIT {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    MeterRegistry metricas;

    @Autowired
    KafkaContainer kafka;

    @BeforeEach
    void limpiar() {
        jdbc.execute("ALTER TABLE evaluacion DISABLE TRIGGER evaluacion_solo_insert");
        jdbc.execute("TRUNCATE evaluacion, outbox, lista_bloqueo");
        jdbc.execute("ALTER TABLE evaluacion ENABLE TRIGGER evaluacion_solo_insert");
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    /** Cuerpo de la evaluación; MCC 7995 (R06, 15) + dispositivo nuevo (R05, 25) = 40 → REVISAR. */
    private static String solicitud(UUID transaccionId, UUID tarjetaId, String mcc, String huella) {
        return """
                {"transaccionId": "%s", "idExterno": "%s", "tarjetaId": "%s", "clienteId": "%s",
                 "comercioId": "COM-00421", "mcc": "%s", "dispositivoHuella": %s,
                 "monto": 189.90, "moneda": "PEN", "canal": "POS", "pais": "PE",
                 "latitud": -12.0931, "longitud": -77.0465, "fecha": "2026-11-06T14:30:12Z"}
                """.formatted(transaccionId, UUID.randomUUID(), tarjetaId, UUID.randomUUID(), mcc,
                huella == null ? "null" : "\"" + huella + "\"");
    }

    private ResponseEntity<String> evaluar(String cuerpo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/internal/v1/evaluaciones", new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    private int outbox(UUID transaccionId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE payload LIKE ?", Integer.class,
                "%" + transaccionId + "%");
    }

    @Test
    void aprobar_guarda_la_evaluacion_y_no_publica() throws Exception {
        UUID tx = UUID.randomUUID();

        var respuesta = evaluar(solicitud(tx, UUID.randomUUID(), "5411", null));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode r = mapper.readTree(respuesta.getBody());
        assertThat(r.get("decision").asText()).isEqualTo("APROBAR");
        assertThat(r.get("score").asInt()).isZero();
        var fila = jdbc.queryForMap("SELECT * FROM evaluacion WHERE transaccion_id = ?", tx);
        assertThat(fila.get("monto").toString()).isEqualTo("189.90");
        assertThat(fila.get("id_externo")).isNotNull();
        assertThat(fila.get("tardia")).isEqualTo(false);
        assertThat(outbox(tx)).isZero();
    }

    @Test
    void revisar_guarda_reglas_y_versiones_y_publica_en_el_outbox() throws Exception {
        UUID tx = UUID.randomUUID();
        UUID tarjeta = UUID.randomUUID();

        var respuesta = evaluar(solicitud(tx, tarjeta, "7995", "dev_nuevo"));

        JsonNode r = mapper.readTree(respuesta.getBody());
        assertThat(r.get("decision").asText()).isEqualTo("REVISAR");
        assertThat(r.get("score").asInt()).isEqualTo(40);
        assertThat(r.get("reglasDisparadas")).extracting(n -> n.get("codigo").asText())
                .containsExactlyInAnyOrder("R05", "R06");
        var fila = jdbc.queryForMap("SELECT reglas_disparadas::text AS reglas, versiones_reglas::text AS versiones "
                + "FROM evaluacion WHERE transaccion_id = ?", tx);
        assertThat((String) fila.get("reglas")).contains("R05").contains("R06");
        assertThat((String) fila.get("versiones")).contains("\"R01\"").contains("\"R07\"");
        var outbox = jdbc.queryForMap("SELECT topico, clave, payload FROM outbox WHERE payload LIKE ?", "%" + tx + "%");
        assertThat(outbox).containsEntry("topico", Topicos.DECISIONES_SOSPECHOSAS)
                .containsEntry("clave", tarjeta.toString());
        JsonNode evento = mapper.readTree((String) outbox.get("payload"));
        assertThat(evento.get("version").asInt()).isEqualTo(1);
        assertThat(evento.get("decision").asText()).isEqualTo("REVISAR");
        assertThat(evento.get("monto").decimalValue()).isEqualByComparingTo("189.90");
    }

    @Test
    void la_decision_sospechosa_llega_a_kafka_con_clave_tarjeta() throws Exception {
        UUID tx = UUID.randomUUID();
        UUID tarjeta = UUID.randomUUID();

        evaluar(solicitud(tx, tarjeta, "7995", "dev_nuevo"));

        List<ConsumerRecord<String, String>> eventos = new ArrayList<>();
        try (var consumidor = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "prueba-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumidor.subscribe(List.of(Topicos.DECISIONES_SOSPECHOSAS));
            Instant limite = Instant.now().plusSeconds(15);
            while (eventos.isEmpty() && Instant.now().isBefore(limite)) {
                consumidor.poll(Duration.ofMillis(300)).forEach(registro -> {
                    if (registro.value().contains(tx.toString())) {
                        eventos.add(registro);
                    }
                });
            }
        }
        assertThat(eventos).hasSize(1);
        assertThat(eventos.get(0).key()).isEqualTo(tarjeta.toString());
    }

    @Test
    void evaluacion_repetida_no_duplica_fila_ni_evento() throws Exception {
        UUID tx = UUID.randomUUID();
        String cuerpo = solicitud(tx, UUID.randomUUID(), "7995", "dev_nuevo");

        var primera = evaluar(cuerpo);
        var segunda = evaluar(cuerpo);

        assertThat(mapper.readTree(segunda.getBody())).isEqualTo(mapper.readTree(primera.getBody()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evaluacion WHERE transaccion_id = ?", Integer.class, tx))
                .isEqualTo(1);
        assertThat(outbox(tx)).isEqualTo(1);
    }

    @Test
    void tarjeta_en_lista_de_bloqueo_rechaza_con_b01() throws Exception {
        UUID tx = UUID.randomUUID();
        UUID tarjeta = UUID.randomUUID();
        jdbc.update("INSERT INTO lista_bloqueo VALUES (gen_random_uuid(), 'TARJETA', ?, 'prueba', 'MANUAL', NULL, "
                + "now(), 'admin')", tarjeta.toString());

        JsonNode r = mapper.readTree(evaluar(solicitud(tx, tarjeta, "5411", null)).getBody());

        assertThat(r.get("decision").asText()).isEqualTo("RECHAZAR");
        assertThat(r.get("score").asInt()).isEqualTo(100);
        assertThat(r.get("reglasDisparadas")).extracting(n -> n.get("codigo").asText()).containsExactly("B01");
        assertThat(outbox(tx)).isEqualTo(1);
    }

    @Test
    void un_bloqueo_vencido_no_rechaza() throws Exception {
        UUID tarjeta = UUID.randomUUID();
        jdbc.update("INSERT INTO lista_bloqueo VALUES (gen_random_uuid(), 'TARJETA', ?, 'prueba', 'MANUAL', "
                + "now() - interval '1 hour', now() - interval '2 hours', 'admin')", tarjeta.toString());

        JsonNode r = mapper.readTree(evaluar(solicitud(UUID.randomUUID(), tarjeta, "5411", null)).getBody());

        assertThat(r.get("decision").asText()).isEqualTo("APROBAR");
    }

    @Test
    void registra_la_latencia_en_el_timer_de_micrometer() {
        evaluar(solicitud(UUID.randomUUID(), UUID.randomUUID(), "5411", null));

        assertThat(metricas.get("scoring.evaluacion.latencia").timer().count()).isPositive();
    }

    @Test
    void solicitud_incompleta_devuelve_422() {
        var respuesta = evaluar("{\"transaccionId\": \"%s\"}".formatted(UUID.randomUUID()));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
