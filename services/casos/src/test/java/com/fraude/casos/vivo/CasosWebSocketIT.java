package com.fraude.casos.vivo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.casos.TestcontainersConfig;
import com.fraude.casos.TokensDePrueba;
import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.DecisionSospechosaEvento;
import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.common.eventos.Topicos;
import com.fraude.common.json.JsonFraude;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.kafka.KafkaContainer;

/** Tarea 5.2: casos nuevos por STOMP en {@code /topic/casos}, con el token validado en el CONNECT. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TokensDePrueba.class})
class CasosWebSocketIT {

    @LocalServerPort
    int puerto;

    @Autowired
    KafkaContainer kafka;

    private final ObjectMapper mapper = JsonFraude.objectMapper();
    private final WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
    private StompSession sesion;

    CasosWebSocketIT() {
        MappingJackson2MessageConverter convertidor = new MappingJackson2MessageConverter();
        convertidor.setObjectMapper(mapper);
        stomp.setMessageConverter(convertidor);
    }

    @AfterEach
    void cerrar() {
        if (sesion != null && sesion.isConnected()) {
            sesion.disconnect();
        }
        stomp.stop();
    }

    private StompSession conectar(String token) throws Exception {
        StompHeaders connect = new StompHeaders();
        if (token != null) {
            connect.add("Authorization", "Bearer " + token);
        }
        return stomp.connectAsync("ws://localhost:" + puerto + "/ws", new WebSocketHttpHeaders(), connect,
                new StompSessionHandlerAdapter() {
                }).get(10, TimeUnit.SECONDS);
    }

    private BlockingQueue<JsonNode> suscribir(StompSession s) throws InterruptedException {
        BlockingQueue<JsonNode> recibidos = new LinkedBlockingQueue<>();
        s.subscribe("/topic/casos", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                recibidos.add((JsonNode) payload);
            }
        });
        // La suscripción viaja en un frame aparte: se le da tiempo a registrarse en el broker.
        Thread.sleep(500);
        return recibidos;
    }

    private DecisionSospechosaEvento publicarDecision() throws Exception {
        var evento = new DecisionSospechosaEvento(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, new BigDecimal("980.50"), 85, Decision.RECHAZAR,
                List.of(new ReglaDisparada("R03", 50, "viaje imposible")), Instant.now());
        try (var productor = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            productor.send(new ProducerRecord<>(Topicos.DECISIONES_SOSPECHOSAS, evento.tarjetaId().toString(),
                    mapper.writeValueAsString(evento))).get();
        }
        return evento;
    }

    /** Espera el caso de esa transacción (otros casos de pruebas anteriores pueden llegar antes). */
    private JsonNode esperarCaso(BlockingQueue<JsonNode> recibidos, UUID transaccionId) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < limite) {
            JsonNode caso = recibidos.poll(500, TimeUnit.MILLISECONDS);
            if (caso != null) {
                if (caso.get("transaccionId").asText().equals(transaccionId.toString())) {
                    return caso;
                }
            }
        }
        return null;
    }

    @Test
    void un_cliente_autenticado_recibe_el_caso_nuevo() throws Exception {
        sesion = conectar(TokensDePrueba.ANALISTA);
        BlockingQueue<JsonNode> recibidos = suscribir(sesion);

        var evento = publicarDecision();
        JsonNode caso = esperarCaso(recibidos, evento.transaccionId());

        assertThat(caso).as("caso recibido por STOMP").isNotNull();
        assertThat(caso.get("estado").asText()).isEqualTo("NUEVO");
        assertThat(caso.get("prioridad").asText()).isEqualTo("ALTA");
        assertThat(caso.get("monto").decimalValue()).isEqualByComparingTo("980.50");
        assertThat(caso.get("creadoEn").isTextual()).isTrue();
        assertThat(caso.get("reglasDisparadas").get(0).get("codigo").asText()).isEqualTo("R03");
    }

    @Test
    void el_auditor_tambien_recibe_casos() throws Exception {
        sesion = conectar(TokensDePrueba.AUDITOR);
        BlockingQueue<JsonNode> recibidos = suscribir(sesion);

        var evento = publicarDecision();

        assertThat(esperarCaso(recibidos, evento.transaccionId())).isNotNull();
    }

    @Test
    void sin_token_se_rechaza_la_conexion() {
        assertThatThrownBy(() -> conectar(null)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void token_invalido_se_rechaza() {
        assertThatThrownBy(() -> conectar("no-es-un-token")).isInstanceOf(ExecutionException.class);
    }

    @Test
    void un_rol_sin_lectura_de_casos_se_rechaza() {
        assertThatThrownBy(() -> conectar("rol-SISTEMA-generador")).isInstanceOf(ExecutionException.class);
    }

    @Test
    void no_se_puede_suscribir_a_otros_destinos() throws Exception {
        sesion = conectar(TokensDePrueba.ANALISTA);
        sesion.subscribe("/topic/otro", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
            }
        });
        // El servidor responde ERROR y cierra la sesión.
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (sesion.isConnected() && System.nanoTime() < limite) {
            Thread.sleep(100);
        }
        assertThat(sesion.isConnected()).isFalse();
    }
}
