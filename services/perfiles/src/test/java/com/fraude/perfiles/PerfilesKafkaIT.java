package com.fraude.perfiles;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.common.eventos.Canal;
import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.Topicos;
import com.fraude.common.eventos.TransaccionCreadaEvento;
import com.fraude.common.json.JsonFraude;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Servicio completo contra Kafka y Valkey reales: mensajes ilegibles o inválidos van a la
 * DLT sin detener la partición, el válido llega a Valkey, con exactly_once_v2 activo.
 */
@SpringBootTest(classes = PerfilesApplication.class)
@Import(PerfilesKafkaIT.Infra.class)
class PerfilesKafkaIT {

    private static final UUID CLIENTE = UUID.randomUUID();
    private static final UUID TARJETA = UUID.randomUUID();

    @TestConfiguration(proxyBeanMethods = false)
    static class Infra {

        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer("apache/kafka:4.3.2");
        }

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> valkey() {
            return new GenericContainer<>("valkey/valkey:8.1.11-alpine").withExposedPorts(6379);
        }

        @Bean
        NewTopic transacciones() {
            return new NewTopic(Topicos.TRANSACCIONES_CREADAS, 6, (short) 1);
        }

        @Bean
        NewTopic transaccionesDlt() {
            return new NewTopic(Topicos.TRANSACCIONES_CREADAS + ".dlt", 6, (short) 1);
        }
    }

    @Autowired
    KafkaContainer kafka;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    StreamsBuilderFactoryBean streams;

    @Autowired
    MeterRegistry metricas;

    private final ObjectMapper mapper = JsonFraude.objectMapper();

    private void producir(byte[] valor) throws Exception {
        try (var productor = new KafkaProducer<byte[], byte[]>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            productor.send(new ProducerRecord<>(Topicos.TRANSACCIONES_CREADAS,
                    TARJETA.toString().getBytes(StandardCharsets.UTF_8), valor)).get();
        }
    }

    private List<ConsumerRecord<String, byte[]>> leerDlt(int esperados, Duration espera) {
        List<ConsumerRecord<String, byte[]>> leidos = new ArrayList<>();
        try (var consumidor = new KafkaConsumer<String, byte[]>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "prueba-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            consumidor.subscribe(List.of(Topicos.TRANSACCIONES_CREADAS + ".dlt"));
            Instant limite = Instant.now().plus(espera);
            while (leidos.size() < esperados && Instant.now().isBefore(limite)) {
                consumidor.poll(Duration.ofMillis(300)).forEach(leidos::add);
            }
        }
        return leidos;
    }

    private static String cabecera(ConsumerRecord<?, ?> registro, String nombre) {
        var cabecera = registro.headers().lastHeader(nombre);
        return cabecera == null ? null : new String(cabecera.value(), StandardCharsets.UTF_8);
    }

    @Test
    void mensajes_venenosos_van_a_la_dlt_y_el_valido_llega_a_valkey() throws Exception {
        var invalido = new TransaccionCreadaEvento(1, UUID.randomUUID(), UUID.randomUUID(), TARJETA, null,
                "COM-00001", "5411", null, new BigDecimal("10.00"), "PEN", Canal.POS, "PE",
                new BigDecimal("-12.046400"), new BigDecimal("-77.042800"), Instant.parse("2025-03-01T15:00:00Z"),
                Decision.APROBAR, 0, false);
        var valido = new TransaccionCreadaEvento(1, UUID.randomUUID(), UUID.randomUUID(), TARJETA, CLIENTE,
                "COM-00001", "5411", null, new BigDecimal("150.00"), "PEN", Canal.POS, "PE",
                new BigDecimal("-12.046400"), new BigDecimal("-77.042800"), Instant.parse("2025-03-01T16:00:00Z"),
                Decision.APROBAR, 0, false);

        producir("esto no es json".getBytes(StandardCharsets.UTF_8));
        producir(mapper.writeValueAsBytes(invalido));
        producir(mapper.writeValueAsBytes(valido));

        var dlt = leerDlt(2, Duration.ofSeconds(60));
        assertThat(dlt).hasSize(2);
        assertThat(new String(dlt.get(0).value(), StandardCharsets.UTF_8)).isEqualTo("esto no es json");
        assertThat(cabecera(dlt.get(0), "kafka_dlt-exception-message")).isNotBlank();
        assertThat(cabecera(dlt.get(1), "x-error")).contains("clienteId");

        Instant limite = Instant.now().plusSeconds(30);
        while (!Boolean.TRUE.equals(redis.hasKey("perfil:" + CLIENTE)) && Instant.now().isBefore(limite)) {
            Thread.sleep(200);
        }
        assertThat(redis.opsForHash().get("perfil:" + CLIENTE, "n30d")).isEqualTo("1");
        assertThat(streams.getKafkaStreams().state().isRunningOrRebalancing()).isTrue();
        assertThat(streams.getStreamsConfiguration().get("processing.guarantee")).isEqualTo("exactly_once_v2");
        assertThat(metricas.find("kafka.consumer.fetch.manager.records.lag.max").meters())
                .as("lag del consumidor expuesto en Micrometer").isNotEmpty();
    }
}
