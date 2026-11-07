package com.fraude.casos.caso;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.casos.TestcontainersConfig;
import com.fraude.casos.TokensDePrueba;
import com.fraude.common.eventos.CasoCerradoEvento;
import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.DecisionSospechosaEvento;
import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.common.eventos.Resolucion;
import com.fraude.common.eventos.Topicos;
import com.fraude.common.json.JsonFraude;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;

/** Ciclo de vida de casos (§8.1, §8.2, §8.3) por la API y desde Kafka. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TokensDePrueba.class})
class CasosApiIT {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaContainer kafka;

    private final ObjectMapper mapper = JsonFraude.objectMapper();

    @BeforeEach
    void limpiar() {
        jdbc.execute("TRUNCATE caso, nota_caso, historial_caso, outbox CASCADE");
    }

    // --- utilidades ---

    private void producir(String clave, String valor) throws Exception {
        try (var productor = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            productor.send(new ProducerRecord<>(Topicos.DECISIONES_SOSPECHOSAS, clave, valor)).get();
        }
    }

    private DecisionSospechosaEvento decision(int score, Decision decision) {
        return new DecisionSospechosaEvento(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "dev_abc", new BigDecimal("2450.00"), score, decision,
                List.of(new ReglaDisparada("R01", 40, "monto 2450.00 PEN, promedio 180.00 PEN")), Instant.now());
    }

    private void publicar(DecisionSospechosaEvento e) throws Exception {
        producir(e.tarjetaId().toString(), mapper.writeValueAsString(e));
    }

    /** Publica la decisión y espera a que exista su caso; devuelve el id del caso. */
    private UUID crearCaso(DecisionSospechosaEvento e) throws Exception {
        publicar(e);
        esperar(() -> casoDe(e.transaccionId()) != null);
        return casoDe(e.transaccionId());
    }

    private UUID casoDe(UUID transaccionId) {
        return jdbc.query("SELECT id FROM caso WHERE transaccion_id = ?", (rs, n) -> rs.getObject(1, UUID.class),
                transaccionId).stream().findFirst().orElse(null);
    }

    private static void esperar(BooleanSupplier condicion) throws InterruptedException {
        Instant limite = Instant.now().plusSeconds(30);
        while (!condicion.getAsBoolean() && Instant.now().isBefore(limite)) {
            Thread.sleep(100);
        }
    }

    private ResponseEntity<String> llamar(HttpMethod metodo, String ruta, String token, String cuerpo) {
        var cabeceras = TokensDePrueba.cabeceras(token);
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(ruta, metodo, new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    private ResponseEntity<String> accion(UUID caso, String accion, String token, String cuerpo) {
        return llamar(HttpMethod.POST, "/api/v1/casos/" + caso + "/" + accion, token, cuerpo);
    }

    private JsonNode json(ResponseEntity<String> r) throws Exception {
        return mapper.readTree(r.getBody());
    }

    private int historial(UUID caso) {
        return jdbc.queryForObject("SELECT count(*) FROM historial_caso WHERE caso_id = ?", Integer.class, caso);
    }

    /** Lleva un caso nuevo a EN_INVESTIGACION con analista1. */
    private UUID enInvestigacion() throws Exception {
        UUID caso = crearCaso(decision(55, Decision.REVISAR));
        assertThat(accion(caso, "tomar", TokensDePrueba.ANALISTA, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accion(caso, "investigar", TokensDePrueba.ANALISTA, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        return caso;
    }

    // --- creación desde Kafka ---

    @Test
    void una_decision_sospechosa_crea_un_caso_nuevo_con_su_prioridad() throws Exception {
        var revisar = decision(55, Decision.REVISAR);
        var rechazar = decision(85, Decision.RECHAZAR);
        UUID media = crearCaso(revisar);
        UUID alta = crearCaso(rechazar);

        var r = llamar(HttpMethod.GET, "/api/v1/casos/" + media, TokensDePrueba.AUDITOR, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode caso = json(r);
        assertThat(caso.get("estado").asText()).isEqualTo("NUEVO");
        assertThat(caso.get("prioridad").asText()).isEqualTo("MEDIA");
        assertThat(caso.get("transaccionId").asText()).isEqualTo(revisar.transaccionId().toString());
        assertThat(caso.get("monto").decimalValue()).isEqualByComparingTo("2450.00");
        assertThat(caso.get("reglasDisparadas").get(0).get("codigo").asText()).isEqualTo("R01");
        assertThat(caso.get("historial").get(0).get("estadoNuevo").asText()).isEqualTo("NUEVO");
        assertThat(caso.get("historial").get(0).get("usuarioId").asText()).isEqualTo("sistema");
        assertThat(json(llamar(HttpMethod.GET, "/api/v1/casos/" + alta, TokensDePrueba.AUDITOR, null))
                .get("prioridad").asText()).isEqualTo("ALTA");
    }

    @Test
    void el_mismo_evento_dos_veces_crea_un_solo_caso() throws Exception {
        var evento = decision(55, Decision.REVISAR);
        publicar(evento);
        publicar(evento);
        esperar(() -> casoDe(evento.transaccionId()) != null);
        Thread.sleep(2000);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM caso WHERE transaccion_id = ?", Integer.class,
                evento.transaccionId())).isEqualTo(1);
        assertThat(historial(casoDe(evento.transaccionId()))).isEqualTo(1);
    }

    @Test
    void eventos_ilegibles_o_invalidos_van_a_la_dlt_y_el_siguiente_se_procesa() throws Exception {
        String aprobada = mapper.writeValueAsString(decision(10, Decision.APROBAR));
        String sinTransaccion = mapper.writeValueAsString(decision(55, Decision.REVISAR)).replaceFirst(
                "\"transaccionId\":\"[^\"]+\",", "");
        producir("x", "esto no es json");
        producir("x", aprobada);
        producir("x", sinTransaccion);
        UUID siguiente = crearCaso(decision(55, Decision.REVISAR));
        assertThat(siguiente).isNotNull();

        List<ConsumerRecord<String, String>> dlt = new ArrayList<>();
        try (var consumidor = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "prueba-dlt-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumidor.subscribe(List.of(Topicos.DECISIONES_SOSPECHOSAS + ".dlt"));
            Instant limite = Instant.now().plusSeconds(30);
            while (dlt.size() < 3 && Instant.now().isBefore(limite)) {
                consumidor.poll(Duration.ofMillis(300)).forEach(dlt::add);
            }
        }
        assertThat(dlt).extracting(ConsumerRecord::value).contains("esto no es json", aprobada, sinTransaccion);
        assertThat(dlt).allSatisfy(r -> assertThat(r.headers().lastHeader("kafka_dlt-exception-message")).isNotNull());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM caso", Integer.class)).isEqualTo(1);
    }

    // --- transiciones ---

    @Test
    void cada_transicion_valida_inserta_historial() throws Exception {
        UUID caso = crearCaso(decision(55, Decision.REVISAR));

        var tomado = accion(caso, "tomar", TokensDePrueba.ANALISTA, null);
        assertThat(tomado.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(tomado).get("estado").asText()).isEqualTo("ASIGNADO");
        assertThat(json(tomado).get("analistaId").asText()).isEqualTo("analista1");

        var reasignado = accion(caso, "asignar", TokensDePrueba.SUPERVISOR, "{\"analistaId\":\"analista2\"}");
        assertThat(reasignado.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(reasignado).get("analistaId").asText()).isEqualTo("analista2");

        assertThat(accion(caso, "investigar", TokensDePrueba.ANALISTA2, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        var cerrado = accion(caso, "cerrar", TokensDePrueba.ANALISTA2,
                "{\"resolucion\":\"FALSO_POSITIVO\",\"comentario\":\"cliente confirmó la compra\"}");
        assertThat(cerrado.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(cerrado).get("estado").asText()).isEqualTo("CERRADO");
        assertThat(json(cerrado).get("resolucion").asText()).isEqualTo("FALSO_POSITIVO");
        assertThat(json(cerrado).get("cerradoEn").isNull()).isFalse();

        JsonNode detalle = json(llamar(HttpMethod.GET, "/api/v1/casos/" + caso, TokensDePrueba.AUDITOR, null));
        assertThat(detalle.get("historial")).extracting(h -> h.get("estadoNuevo").asText())
                .containsExactly("NUEVO", "ASIGNADO", "ASIGNADO", "EN_INVESTIGACION", "CERRADO");
        assertThat(detalle.get("historial")).extracting(h -> h.get("usuarioId").asText())
                .containsExactly("sistema", "analista1", "supervisor", "analista2", "analista2");
        assertThat(detalle.get("historial").get(4).get("comentario").asText()).isEqualTo("cliente confirmó la compra");
        assertThat(detalle.get("historial").get(4).get("estadoAnterior").asText()).isEqualTo("EN_INVESTIGACION");
    }

    @Test
    void transicion_invalida_responde_409_sin_historial() throws Exception {
        UUID caso = crearCaso(decision(55, Decision.REVISAR));

        var r = accion(caso, "investigar", TokensDePrueba.ANALISTA, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(json(r).get("status").asInt()).isEqualTo(409);
        assertThat(historial(caso)).isEqualTo(1);
    }

    @Test
    void cerrar_sin_resolucion_responde_422() throws Exception {
        UUID caso = enInvestigacion();

        var r = accion(caso, "cerrar", TokensDePrueba.ANALISTA, "{\"comentario\":\"sin resolución\"}");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json(r).get("status").asInt()).isEqualTo(422);
        assertThat(historial(caso)).isEqualTo(3);
    }

    @Test
    void un_analista_no_asignado_no_puede_cerrar() throws Exception {
        UUID caso = enInvestigacion();

        var r = accion(caso, "cerrar", TokensDePrueba.ANALISTA2, "{\"resolucion\":\"FRAUDE_CONFIRMADO\"}");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(r).get("status").asInt()).isEqualTo(403);
        assertThat(historial(caso)).isEqualTo(3);
    }

    @Test
    void roles_de_las_acciones() throws Exception {
        UUID caso = crearCaso(decision(55, Decision.REVISAR));

        assertThat(accion(caso, "tomar", TokensDePrueba.AUDITOR, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(accion(caso, "asignar", TokensDePrueba.ANALISTA, "{\"analistaId\":\"analista1\"}").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(accion(caso, "notas", TokensDePrueba.AUDITOR, "{\"texto\":\"hola\"}").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(llamar(HttpMethod.GET, "/api/v1/casos", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(llamar(HttpMethod.GET, "/api/v1/casos/" + UUID.randomUUID(), TokensDePrueba.ANALISTA, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(historial(caso)).isEqualTo(1);
    }

    // --- cierre ---

    @Test
    void el_cierre_publica_caso_cerrado_evento() throws Exception {
        var origen = decision(85, Decision.RECHAZAR);
        UUID caso = crearCaso(origen);
        accion(caso, "tomar", TokensDePrueba.ANALISTA, null);
        accion(caso, "investigar", TokensDePrueba.ANALISTA, null);
        assertThat(accion(caso, "cerrar", TokensDePrueba.ANALISTA, "{\"resolucion\":\"FRAUDE_CONFIRMADO\"}")
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ConsumerRecord<String, String> publicado = null;
        try (var consumidor = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "prueba-cierre-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumidor.subscribe(List.of(Topicos.CASOS_EVENTOS));
            Instant limite = Instant.now().plusSeconds(30);
            while (publicado == null && Instant.now().isBefore(limite)) {
                for (var r : consumidor.poll(Duration.ofMillis(300))) {
                    if (r.value().contains(caso.toString())) {
                        publicado = r;
                    }
                }
            }
        }
        assertThat(publicado).isNotNull();
        assertThat(publicado.key()).isEqualTo(origen.tarjetaId().toString());
        CasoCerradoEvento evento = mapper.readValue(publicado.value().getBytes(StandardCharsets.UTF_8),
                CasoCerradoEvento.class);
        assertThat(evento.version()).isEqualTo(CasoCerradoEvento.VERSION_ACTUAL);
        assertThat(evento.casoId()).isEqualTo(caso);
        assertThat(evento.transaccionId()).isEqualTo(origen.transaccionId());
        assertThat(evento.tarjetaId()).isEqualTo(origen.tarjetaId());
        assertThat(evento.dispositivoHuella()).isEqualTo("dev_abc");
        assertThat(evento.resolucion()).isEqualTo(Resolucion.FRAUDE_CONFIRMADO);
        esperar(() -> jdbc.queryForObject("SELECT count(*) FROM outbox WHERE enviado_en IS NULL", Integer.class) == 0);
    }

    // --- bandeja y notas ---

    @Test
    void la_bandeja_ordena_por_prioridad_y_antiguedad_y_filtra() throws Exception {
        UUID mediaVieja = crearCaso(decision(45, Decision.REVISAR));
        UUID altaNueva = crearCaso(decision(90, Decision.RECHAZAR));
        UUID mediaNueva = crearCaso(decision(50, Decision.REVISAR));
        UUID altaMasNueva = crearCaso(decision(75, Decision.RECHAZAR));
        accion(mediaNueva, "tomar", TokensDePrueba.ANALISTA, null);

        JsonNode bandeja = json(llamar(HttpMethod.GET, "/api/v1/casos", TokensDePrueba.AUDITOR, null));
        assertThat(bandeja.get("total").asInt()).isEqualTo(4);
        assertThat(bandeja.get("contenido")).extracting(c -> c.get("id").asText())
                .containsExactly(altaNueva.toString(), altaMasNueva.toString(), mediaVieja.toString(),
                        mediaNueva.toString());

        assertThat(json(llamar(HttpMethod.GET, "/api/v1/casos?prioridad=MEDIA&estado=NUEVO", TokensDePrueba.AUDITOR,
                null)).get("contenido")).extracting(c -> c.get("id").asText()).containsExactly(mediaVieja.toString());
        assertThat(json(llamar(HttpMethod.GET, "/api/v1/casos?analista=analista1", TokensDePrueba.AUDITOR, null))
                .get("contenido")).extracting(c -> c.get("id").asText()).containsExactly(mediaNueva.toString());
        assertThat(json(llamar(HttpMethod.GET, "/api/v1/casos?desde=" + Instant.now().plusSeconds(60),
                TokensDePrueba.AUDITOR, null)).get("total").asInt()).isZero();
        JsonNode pagina = json(llamar(HttpMethod.GET, "/api/v1/casos?pagina=1&tamano=3", TokensDePrueba.AUDITOR, null));
        assertThat(pagina.get("contenido")).extracting(c -> c.get("id").asText()).containsExactly(mediaNueva.toString());
        assertThat(pagina.get("total").asInt()).isEqualTo(4);
        assertThat(llamar(HttpMethod.GET, "/api/v1/casos?tamano=500", TokensDePrueba.AUDITOR, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void las_notas_quedan_en_el_detalle() throws Exception {
        UUID caso = crearCaso(decision(55, Decision.REVISAR));

        var r = accion(caso, "notas", TokensDePrueba.ANALISTA, "{\"texto\":\"llamé al cliente\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(r).get("autorId").asText()).isEqualTo("analista1");
        assertThat(accion(caso, "notas", TokensDePrueba.ANALISTA, "{\"texto\":\" \"}").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        JsonNode detalle = json(llamar(HttpMethod.GET, "/api/v1/casos/" + caso, TokensDePrueba.AUDITOR, null));
        assertThat(detalle.get("notas")).hasSize(1);
        assertThat(detalle.get("notas").get(0).get("texto").asText()).isEqualTo("llamé al cliente");
    }

    @Test
    void el_historial_solo_admite_insert() throws Exception {
        UUID caso = crearCaso(decision(55, Decision.REVISAR));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.update("UPDATE historial_caso SET comentario = 'x' WHERE caso_id = ?", caso))
                .hasMessageContaining("solo admite INSERT");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.update("DELETE FROM historial_caso WHERE caso_id = ?", caso))
                .hasMessageContaining("solo admite INSERT");
    }
}
