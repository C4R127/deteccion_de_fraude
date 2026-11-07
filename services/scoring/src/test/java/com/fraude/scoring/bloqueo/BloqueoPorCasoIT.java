package com.fraude.scoring.bloqueo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.common.eventos.CasoCerradoEvento;
import com.fraude.common.eventos.Resolucion;
import com.fraude.common.eventos.Topicos;
import com.fraude.common.json.JsonFraude;
import com.fraude.scoring.TestcontainersConfig;
import com.fraude.scoring.TokensDePrueba;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;

/** §8.2: el cierre de un caso como fraude confirmado bloquea tarjeta y dispositivo. */
@SpringBootTest
@Import({TestcontainersConfig.class, TokensDePrueba.class})
class BloqueoPorCasoIT {

    @Autowired
    KafkaContainer kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ListaBloqueoService bloqueos;

    private final ObjectMapper mapper = JsonFraude.objectMapper();

    private void producir(String clave, String valor) throws Exception {
        try (var productor = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            productor.send(new ProducerRecord<>(Topicos.CASOS_EVENTOS, clave, valor)).get();
        }
    }

    private CasoCerradoEvento cierre(UUID tarjeta, String huella, Resolucion resolucion) {
        return new CasoCerradoEvento(1, UUID.randomUUID(), UUID.randomUUID(), tarjeta, huella, resolucion,
                Instant.now());
    }

    private void publicar(CasoCerradoEvento e) throws Exception {
        producir(e.tarjetaId().toString(), mapper.writeValueAsString(e));
    }

    private int filas(String tipo, String valor) {
        return jdbc.queryForObject("SELECT count(*) FROM lista_bloqueo WHERE tipo = ? AND valor = ?", Integer.class,
                tipo, valor);
    }

    private static void esperar(BooleanSupplier condicion) throws InterruptedException {
        Instant limite = Instant.now().plusSeconds(30);
        while (!condicion.getAsBoolean() && Instant.now().isBefore(limite)) {
            Thread.sleep(200);
        }
    }

    @Test
    void fraude_confirmado_bloquea_tarjeta_y_dispositivo_con_origen_caso() throws Exception {
        UUID tarjeta = UUID.randomUUID();
        var evento = cierre(tarjeta, "dev_fraude", Resolucion.FRAUDE_CONFIRMADO);

        publicar(evento);
        esperar(() -> filas("DISPOSITIVO", "dev_fraude") == 1);

        var fila = jdbc.queryForMap("SELECT * FROM lista_bloqueo WHERE tipo = 'TARJETA' AND valor = ?",
                tarjeta.toString());
        assertThat(fila).containsEntry("origen", "CASO").containsEntry("vigente_hasta", null);
        assertThat((String) fila.get("motivo")).contains(evento.casoId().toString());
        assertThat(filas("DISPOSITIVO", "dev_fraude")).isEqualTo(1);
        assertThat(bloqueos.buscar(tarjeta, null, "COM-1", Instant.now())).isPresent();
    }

    @Test
    void fraude_confirmado_sin_dispositivo_solo_bloquea_la_tarjeta() throws Exception {
        UUID tarjeta = UUID.randomUUID();

        publicar(cierre(tarjeta, null, Resolucion.FRAUDE_CONFIRMADO));
        esperar(() -> filas("TARJETA", tarjeta.toString()) == 1);

        assertThat(filas("TARJETA", tarjeta.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lista_bloqueo WHERE tipo = 'DISPOSITIVO' "
                + "AND motivo LIKE ?", Integer.class, "%" + tarjeta + "%")).isZero();
    }

    @Test
    void falso_positivo_no_bloquea() throws Exception {
        UUID tarjeta = UUID.randomUUID();
        var evento = cierre(tarjeta, "dev_ok", Resolucion.FALSO_POSITIVO);

        publicar(evento);
        esperar(() -> jdbc.queryForObject("SELECT count(*) FROM mensaje_procesado WHERE evento_id = ?",
                Integer.class, evento.casoId().toString()) == 1);

        assertThat(filas("TARJETA", tarjeta.toString())).isZero();
        assertThat(filas("DISPOSITIVO", "dev_ok")).isZero();
    }

    @Test
    void el_mismo_evento_dos_veces_deja_un_solo_bloqueo() throws Exception {
        UUID tarjeta = UUID.randomUUID();
        var evento = cierre(tarjeta, null, Resolucion.FRAUDE_CONFIRMADO);

        publicar(evento);
        publicar(evento);
        esperar(() -> filas("TARJETA", tarjeta.toString()) == 1);
        Thread.sleep(2000);

        assertThat(filas("TARJETA", tarjeta.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mensaje_procesado WHERE evento_id = ?", Integer.class,
                evento.casoId().toString())).isEqualTo(1);
    }

    @Test
    void un_caso_reactiva_un_bloqueo_vencido() throws Exception {
        UUID tarjeta = UUID.randomUUID();
        jdbc.update("INSERT INTO lista_bloqueo VALUES (gen_random_uuid(), 'TARJETA', ?, 'manual', 'MANUAL', "
                + "now() - interval '1 day', now() - interval '2 days', 'admin')", tarjeta.toString());

        publicar(cierre(tarjeta, null, Resolucion.FRAUDE_CONFIRMADO));
        esperar(() -> bloqueos.buscar(tarjeta, null, "COM-1", Instant.now()).isPresent());

        assertThat(bloqueos.buscar(tarjeta, null, "COM-1", Instant.now())).isPresent();
        assertThat(filas("TARJETA", tarjeta.toString())).isEqualTo(1);
    }

    @Test
    void mensajes_ilegibles_o_invalidos_van_a_la_dlt_y_el_siguiente_se_procesa() throws Exception {
        UUID tarjeta = UUID.randomUUID();
        String sinTarjeta = mapper.writeValueAsString(new CasoCerradoEvento(1, UUID.randomUUID(), UUID.randomUUID(),
                null, null, Resolucion.FRAUDE_CONFIRMADO, Instant.now()));

        producir("x", "esto no es json");
        producir("x", sinTarjeta);
        publicar(cierre(tarjeta, null, Resolucion.FRAUDE_CONFIRMADO));
        esperar(() -> filas("TARJETA", tarjeta.toString()) == 1);

        assertThat(filas("TARJETA", tarjeta.toString())).isEqualTo(1);
        List<ConsumerRecord<String, String>> dlt = new ArrayList<>();
        try (var consumidor = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "prueba-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumidor.subscribe(List.of(Topicos.CASOS_EVENTOS + ".dlt"));
            Instant limite = Instant.now().plusSeconds(30);
            while (dlt.size() < 2 && Instant.now().isBefore(limite)) {
                consumidor.poll(Duration.ofMillis(300)).forEach(dlt::add);
            }
        }
        assertThat(dlt).extracting(ConsumerRecord::value).contains("esto no es json", sinTarjeta);
        assertThat(dlt).allSatisfy(r -> assertThat(r.headers().lastHeader("kafka_dlt-exception-message")).isNotNull());
        assertThat(new String(dlt.get(dlt.size() - 1).headers().lastHeader("kafka_dlt-exception-message").value(),
                StandardCharsets.UTF_8)).isNotBlank();
    }
}
