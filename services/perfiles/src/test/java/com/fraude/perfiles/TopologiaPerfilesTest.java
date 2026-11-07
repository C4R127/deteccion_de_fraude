package com.fraude.perfiles;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.common.eventos.Canal;
import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.Topicos;
import com.fraude.common.eventos.TransaccionCreadaEvento;
import com.fraude.common.json.JsonFraude;
import com.fraude.perfiles.perfil.EscritorPerfil;
import com.fraude.perfiles.perfil.Perfil;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TopologiaPerfilesTest {

    private static final UUID CLIENTE = UUID.fromString("6f1c2d3e-0000-4000-8000-000000000001");
    private static final UUID TARJETA = UUID.fromString("6f1c2d3e-0000-4000-8000-0000000000a1");
    /** Fechas de 2025: muy anteriores al reloj del sistema, como en el modo historia. */
    private static final Instant BASE = Instant.parse("2025-03-01T15:00:00Z");

    private final ObjectMapper mapper = JsonFraude.objectMapper();
    private final EscritorFalso escritor = new EscritorFalso();
    private TopologyTestDriver driver;
    private TestInputTopic<String, String> entrada;

    /** Guarda el último perfil escrito por cliente, como lo haría Valkey. */
    static class EscritorFalso implements EscritorPerfil {
        final Map<String, Perfil> perfiles = new HashMap<>();
        int escrituras;

        @Override
        public void escribir(String clienteId, Perfil perfil) {
            perfiles.put(clienteId, perfil);
            escrituras++;
        }
    }

    @BeforeEach
    void crear() throws Exception {
        StreamsBuilder builder = new StreamsBuilder();
        new TopologiaPerfiles(escritor, Duration.ofDays(30)).agregar(builder);
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "perfiles-prueba");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "no-usado:9092");
        props.put(StreamsConfig.STATE_DIR_CONFIG, Files.createTempDirectory("perfiles").toString());
        // El reloj del driver empieza "ahora": si la topología usara el reloj, se notaría.
        driver = new TopologyTestDriver(builder.build(), props, Instant.now());
        entrada = driver.createInputTopic(Topicos.TRANSACCIONES_CREADAS, new StringSerializer(),
                new StringSerializer());
    }

    @AfterEach
    void cerrar() {
        driver.close();
    }

    private void enviar(String monto, Instant fecha, Decision decision, String pais, String mcc) throws Exception {
        var evento = new TransaccionCreadaEvento(1, UUID.randomUUID(), UUID.randomUUID(), TARJETA, CLIENTE,
                "COM-00001", mcc, null, new BigDecimal(monto), "PEN", Canal.POS, pais,
                new BigDecimal("-12.046400"), new BigDecimal("-77.042800"), fecha, decision,
                decision == Decision.APROBAR ? 10 : 55, false);
        // Timestamp del registro = ahora; la topología debe usar la fecha del evento.
        entrada.pipeInput(TARJETA.toString(), mapper.writeValueAsString(evento), Instant.now());
    }

    private void enviar(String monto, Instant fecha) throws Exception {
        enviar(monto, fecha, Decision.APROBAR, "PE", "5411");
    }

    private Perfil perfil() {
        return escritor.perfiles.get(CLIENTE.toString());
    }

    @Test
    void promedio_y_desviacion_de_30_dias() throws Exception {
        enviar("100.00", BASE);
        enviar("200.00", BASE.plus(Duration.ofDays(1)));
        enviar("300.00", BASE.plus(Duration.ofDays(2)));

        assertThat(perfil().n30d()).isEqualTo(3);
        assertThat(perfil().promedio30d()).isEqualByComparingTo("200.00");
        assertThat(perfil().promedio30d().scale()).isEqualTo(2);
        // Desviación poblacional: sqrt(((100)² + 0 + (100)²) / 3) = 81.65
        assertThat(perfil().desviacion30d()).isEqualByComparingTo("81.65");
    }

    @Test
    void excluye_transacciones_de_mas_de_30_dias() throws Exception {
        enviar("1000.00", BASE, Decision.APROBAR, "AR", "7995");
        enviar("100.00", BASE.plus(Duration.ofDays(31)));
        enviar("300.00", BASE.plus(Duration.ofDays(32)));

        assertThat(perfil().n30d()).isEqualTo(2);
        assertThat(perfil().promedio30d()).isEqualByComparingTo("200.00");
        assertThat(perfil().paises()).containsExactly("PE");
        assertThat(perfil().mccs()).containsExactly("5411");
    }

    @Test
    void el_limite_de_30_dias_es_exclusivo_por_el_extremo_antiguo() throws Exception {
        enviar("100.00", BASE);
        enviar("300.00", BASE.plus(Duration.ofDays(30)));

        assertThat(perfil().n30d()).isEqualTo(1);
        assertThat(perfil().promedio30d()).isEqualByComparingTo("300.00");
    }

    @Test
    void excluye_transacciones_rechazadas_del_promedio() throws Exception {
        enviar("100.00", BASE);
        enviar("300.00", BASE.plus(Duration.ofHours(1)), Decision.REVISAR, "PE", "5812");
        enviar("9999.00", BASE.plus(Duration.ofHours(2)), Decision.RECHAZAR, "US", "6051");

        assertThat(perfil().n30d()).isEqualTo(2);
        assertThat(perfil().promedio30d()).isEqualByComparingTo("200.00");
        assertThat(perfil().paises()).containsExactly("PE");
        assertThat(perfil().mccs()).containsExactly("5411", "5812");
    }

    @Test
    void cuenta_horas_en_hora_de_lima() throws Exception {
        enviar("100.00", Instant.parse("2025-03-01T08:00:00Z"));
        enviar("100.00", Instant.parse("2025-03-01T08:59:59Z"));
        enviar("100.00", Instant.parse("2025-03-02T04:30:00Z"));

        assertThat(perfil().horas()).hasSize(24);
        assertThat(perfil().horas().get(3)).isEqualTo(2);
        assertThat(perfil().horas().get(23)).isEqualTo(1);
        assertThat(perfil().horas().stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);
    }

    @Test
    void usa_la_fecha_del_evento_no_el_reloj() throws Exception {
        // Ambas fechas están a más de 30 días del reloj, pero a 10 días entre sí.
        enviar("100.00", BASE);
        enviar("300.00", BASE.plus(Duration.ofDays(10)));

        assertThat(perfil().n30d()).isEqualTo(2);
        assertThat(perfil().actualizado()).isEqualTo(BASE.plus(Duration.ofDays(10)));
    }

    @Test
    void un_evento_atrasado_no_mueve_la_ventana_hacia_atras() throws Exception {
        enviar("100.00", BASE.plus(Duration.ofDays(40)));
        enviar("300.00", BASE);

        assertThat(perfil().n30d()).isEqualTo(1);
        assertThat(perfil().promedio30d()).isEqualByComparingTo("100.00");
        assertThat(perfil().actualizado()).isEqualTo(BASE.plus(Duration.ofDays(40)));
    }

    @Test
    void reprocesar_el_mismo_evento_no_lo_cuenta_dos_veces() throws Exception {
        var evento = new TransaccionCreadaEvento(1, UUID.randomUUID(), UUID.randomUUID(), TARJETA, CLIENTE,
                "COM-00001", "5411", null, new BigDecimal("150.00"), "PEN", Canal.POS, "PE",
                new BigDecimal("-12.046400"), new BigDecimal("-77.042800"), BASE, Decision.APROBAR, 0, false);
        String json = mapper.writeValueAsString(evento);

        entrada.pipeInput(TARJETA.toString(), json);
        entrada.pipeInput(TARJETA.toString(), json);

        assertThat(perfil().n30d()).isEqualTo(1);
    }

    @Test
    void cliente_solo_con_rechazadas_tiene_perfil_vacio() throws Exception {
        enviar("500.00", BASE, Decision.RECHAZAR, "PE", "5411");

        assertThat(perfil().n30d()).isZero();
        assertThat(perfil().promedio30d()).isEqualByComparingTo("0.00");
        assertThat(perfil().paises()).isEmpty();
    }
}
