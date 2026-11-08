package com.fraude.transacciones.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraude.common.outbox.EnviadorEventos;
import com.fraude.common.outbox.OutboxStore;
import com.fraude.common.outbox.PublicadorOutbox;
import com.fraude.transacciones.TestcontainersConfig;
import com.fraude.transacciones.TokensDePrueba;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** Publicador del outbox con un enviador falso controlable (sin la tarea programada). */
@SpringBootTest(properties = "outbox.publicador.habilitado=false")
@Import({TestcontainersConfig.class, TokensDePrueba.class, OutboxPublicadorIT.Enviador.class})
class OutboxPublicadorIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class Enviador {
        @Bean
        @Primary
        EnviadorFalso enviadorFalso() {
            return new EnviadorFalso();
        }
    }

    /** Registra lo enviado; falla para las claves indicadas, como un broker caído. */
    static class EnviadorFalso implements EnviadorEventos {
        final List<String> enviados = new ArrayList<>();
        final Set<String> clavesQueFallan = new HashSet<>();

        @Override
        public void enviar(String topico, String clave, String payload) {
            if (clavesQueFallan.contains(clave)) {
                throw new IllegalStateException("broker no disponible");
            }
            enviados.add(clave + "|" + payload);
        }
    }

    @Autowired
    OutboxStore outbox;

    @Autowired
    PublicadorOutbox publicador;

    @Autowired
    EnviadorFalso enviador;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry metricas;

    @BeforeEach
    void limpiar() {
        jdbc.execute("TRUNCATE outbox");
        enviador.enviados.clear();
        enviador.clavesQueFallan.clear();
    }

    private void guardar(String clave, String payload) {
        outbox.guardar("transacciones.creadas", clave, payload);
    }

    private int pendientes() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE enviado_en IS NULL", Integer.class);
    }

    @Test
    void envia_los_pendientes_en_orden_y_los_marca_enviados() {
        guardar("tarjeta-1", "{\"n\":1}");
        guardar("tarjeta-2", "{\"n\":2}");
        guardar("tarjeta-1", "{\"n\":3}");

        int enviados = publicador.publicarPendientes();

        assertThat(enviados).isEqualTo(3);
        assertThat(enviador.enviados).containsExactly(
                "tarjeta-1|{\"n\":1}", "tarjeta-2|{\"n\":2}", "tarjeta-1|{\"n\":3}");
        assertThat(pendientes()).isZero();
        assertThat(publicador.publicarPendientes()).as("no reenvía lo ya enviado").isZero();
    }

    @Test
    void si_el_envio_falla_queda_pendiente_con_reintento_programado() {
        enviador.clavesQueFallan.add("tarjeta-1");
        double fallosAntes = metricas.counter("outbox.fallos").count();
        guardar("tarjeta-1", "{\"n\":1}");

        assertThat(publicador.publicarPendientes()).isZero();

        var fila = jdbc.queryForMap("SELECT * FROM outbox");
        assertThat(fila.get("enviado_en")).isNull();
        assertThat(fila.get("intentos")).isEqualTo(1);
        assertThat((String) fila.get("ultimo_error")).contains("broker no disponible");
        assertThat(((Timestamp) fila.get("proximo_intento")).toInstant()).isAfter(Instant.now());
        assertThat(metricas.counter("outbox.fallos").count()).isEqualTo(fallosAntes + 1);
        assertThat(publicador.publicarPendientes()).as("no reintenta antes de tiempo").isZero();
        assertThat(enviador.enviados).isEmpty();
    }

    @Test
    void reintenta_cuando_vence_la_espera_y_el_broker_vuelve() {
        enviador.clavesQueFallan.add("tarjeta-1");
        guardar("tarjeta-1", "{\"n\":1}");
        publicador.publicarPendientes();

        enviador.clavesQueFallan.clear();
        jdbc.update("UPDATE outbox SET proximo_intento = now() - interval '1 second'");

        assertThat(publicador.publicarPendientes()).isEqualTo(1);
        assertThat(pendientes()).isZero();
    }

    @Test
    void la_espera_crece_con_cada_intento() {
        enviador.clavesQueFallan.add("tarjeta-1");
        guardar("tarjeta-1", "{\"n\":1}");

        publicador.publicarPendientes();
        Instant primero = jdbc.queryForObject("SELECT proximo_intento FROM outbox", Timestamp.class).toInstant();
        jdbc.update("UPDATE outbox SET proximo_intento = now() - interval '1 second'");
        Instant antesDelSegundo = Instant.now();
        publicador.publicarPendientes();
        Instant segundo = jdbc.queryForObject("SELECT proximo_intento FROM outbox", Timestamp.class).toInstant();

        assertThat(jdbc.queryForObject("SELECT intentos FROM outbox", Integer.class)).isEqualTo(2);
        assertThat(segundo.toEpochMilli() - antesDelSegundo.toEpochMilli())
                .isGreaterThan(primero.toEpochMilli() - antesDelSegundo.toEpochMilli());
    }

    @Test
    void no_adelanta_eventos_de_una_clave_cuyo_envio_fallo() {
        enviador.clavesQueFallan.add("tarjeta-1");
        guardar("tarjeta-1", "{\"n\":1}");
        guardar("tarjeta-1", "{\"n\":2}");
        guardar("tarjeta-2", "{\"n\":3}");

        publicador.publicarPendientes();
        publicador.publicarPendientes();

        assertThat(enviador.enviados).containsExactly("tarjeta-2|{\"n\":3}");
        assertThat(jdbc.queryForObject(
                "SELECT intentos FROM outbox WHERE payload::text LIKE '%2}'", Integer.class))
                .as("el segundo de la clave fallida no se intentó").isZero();
    }

    @Test
    void el_siguiente_ciclo_no_adelanta_eventos_detras_de_uno_reprogramado() {
        enviador.clavesQueFallan.add("tarjeta-1");
        guardar("tarjeta-1", "{\"n\":1}");
        publicador.publicarPendientes();
        // El broker vuelve, pero el primero espera su reintento; llega otro de la misma tarjeta.
        enviador.clavesQueFallan.clear();
        guardar("tarjeta-1", "{\"n\":2}");

        assertThat(publicador.publicarPendientes()).isZero();
        assertThat(enviador.enviados).as("el segundo no sale antes que el primero").isEmpty();

        jdbc.update("UPDATE outbox SET proximo_intento = now() - interval '1 second'");
        assertThat(publicador.publicarPendientes()).isEqualTo(2);
        assertThat(enviador.enviados).containsExactly("tarjeta-1|{\"n\":1}", "tarjeta-1|{\"n\":2}");
    }

    @Test
    void con_el_broker_caido_un_ciclo_intenta_un_solo_envio() {
        enviador.clavesQueFallan.addAll(Set.of("tarjeta-1", "tarjeta-2", "tarjeta-3"));
        guardar("tarjeta-1", "{\"n\":1}");
        guardar("tarjeta-2", "{\"n\":2}");
        guardar("tarjeta-3", "{\"n\":3}");

        publicador.publicarPendientes();

        // Cada envío fallido puede esperar hasta 30 s el ack: no se encadenan dentro de la transacción.
        assertThat(jdbc.queryForObject("SELECT sum(intentos) FROM outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void expone_la_cantidad_de_pendientes_como_metrica() {
        enviador.clavesQueFallan.add("tarjeta-1");
        guardar("tarjeta-1", "{\"n\":1}");
        guardar("tarjeta-1", "{\"n\":2}");

        assertThat(metricas.get("outbox.pendientes").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void borra_los_enviados_de_mas_de_siete_dias() {
        guardar("tarjeta-1", "{\"n\":1}");
        guardar("tarjeta-2", "{\"n\":2}");
        guardar("tarjeta-3", "{\"n\":3}");
        publicador.publicarPendientes();
        jdbc.update("UPDATE outbox SET enviado_en = ? WHERE clave = 'tarjeta-1'",
                Timestamp.from(Instant.now().minus(8, ChronoUnit.DAYS)));
        jdbc.update("UPDATE outbox SET enviado_en = NULL WHERE clave = 'tarjeta-3'");

        int borrados = publicador.purgarEnviados();

        assertThat(borrados).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT clave FROM outbox ORDER BY clave", String.class))
                .containsExactly("tarjeta-2", "tarjeta-3");
    }
}
