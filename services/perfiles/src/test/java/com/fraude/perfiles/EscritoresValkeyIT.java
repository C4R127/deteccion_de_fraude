package com.fraude.perfiles;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraude.common.eventos.Canal;
import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.TransaccionCreadaEvento;
import com.fraude.perfiles.perfil.EscritorPerfilValkey;
import com.fraude.perfiles.perfil.Perfil;
import com.fraude.perfiles.valkey.EscritorDispositivos;
import com.fraude.perfiles.valkey.EscritorUltima;
import com.fraude.perfiles.valkey.EscritorVelocidad;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class EscritoresValkeyIT {

    @Container
    static final GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8.1.11-alpine")
            .withExposedPorts(6379);

    static LettuceConnectionFactory conexion;
    static StringRedisTemplate redis;

    private static final UUID CLIENTE = UUID.fromString("6f1c2d3e-0000-4000-8000-000000000001");
    private static final UUID TARJETA = UUID.fromString("6f1c2d3e-0000-4000-8000-0000000000a1");
    private static final Instant BASE = Instant.parse("2025-03-01T15:00:00Z");

    EscritorVelocidad velocidad;
    EscritorUltima ultima;
    EscritorDispositivos dispositivos;

    @BeforeAll
    static void conectar() {
        conexion = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(valkey.getHost(), valkey.getMappedPort(6379)));
        conexion.afterPropertiesSet();
        redis = new StringRedisTemplate(conexion);
    }

    @AfterAll
    static void desconectar() {
        conexion.destroy();
    }

    @BeforeEach
    void limpiar() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        velocidad = new EscritorVelocidad(redis);
        ultima = new EscritorUltima(redis);
        dispositivos = new EscritorDispositivos(redis);
    }

    private static TransaccionCreadaEvento evento(UUID id, Instant fecha, Decision decision, Canal canal,
            String huella) {
        boolean sinUbicacion = canal == Canal.ECOMMERCE;
        return new TransaccionCreadaEvento(1, id, UUID.randomUUID(), TARJETA, CLIENTE, "COM-00001", "5411",
                huella, new BigDecimal("100.00"), "PEN", canal, "PE",
                sinUbicacion ? null : new BigDecimal("-12.046400"),
                sinUbicacion ? null : new BigDecimal("-77.042800"), fecha, decision, 10, false);
    }

    private static TransaccionCreadaEvento evento(Instant fecha, Decision decision) {
        return evento(UUID.randomUUID(), fecha, decision, Canal.POS, null);
    }

    private long enVelocidad() {
        return redis.opsForZSet().size("velocidad:" + TARJETA);
    }

    @Test
    void velocidad_poda_entradas_de_mas_de_una_hora() {
        velocidad.escribir(evento(BASE, Decision.APROBAR));
        velocidad.escribir(evento(BASE.plus(Duration.ofMinutes(30)), Decision.APROBAR));
        velocidad.escribir(evento(BASE.plus(Duration.ofMinutes(61)), Decision.APROBAR));

        assertThat(enVelocidad()).isEqualTo(2);
        assertThat(redis.opsForZSet().rangeByScore("velocidad:" + TARJETA, 0, Double.MAX_VALUE)).hasSize(2);
        Double minimo = redis.opsForZSet().rangeWithScores("velocidad:" + TARJETA, 0, 0).iterator().next().getScore();
        assertThat(minimo.longValue()).isEqualTo(BASE.plus(Duration.ofMinutes(30)).toEpochMilli());
    }

    @Test
    void velocidad_poda_respecto_del_evento_mas_reciente_aunque_llegue_uno_atrasado() {
        velocidad.escribir(evento(BASE.plus(Duration.ofHours(3)), Decision.APROBAR));
        velocidad.escribir(evento(BASE, Decision.APROBAR));

        assertThat(enVelocidad()).isEqualTo(1);
    }

    @Test
    void velocidad_y_ultima_incluyen_rechazadas() {
        velocidad.escribir(evento(BASE, Decision.RECHAZAR));
        ultima.escribir(evento(BASE, Decision.RECHAZAR));

        assertThat(enVelocidad()).isEqualTo(1);
        assertThat(redis.opsForHash().get("ultima:" + TARJETA, "fecha")).isEqualTo(String.valueOf(BASE.toEpochMilli()));
    }

    @Test
    void reprocesar_el_mismo_evento_no_duplica_velocidad() {
        var mismo = evento(BASE, Decision.APROBAR);

        velocidad.escribir(mismo);
        velocidad.escribir(mismo);

        assertThat(enVelocidad()).isEqualTo(1);
    }

    @Test
    void ultima_guarda_ubicacion_fecha_y_canal() {
        ultima.escribir(evento(BASE, Decision.APROBAR));

        var hash = redis.opsForHash().entries("ultima:" + TARJETA);
        assertThat(hash).containsEntry("lat", "-12.046400").containsEntry("lon", "-77.042800")
                .containsEntry("fecha", String.valueOf(BASE.toEpochMilli())).containsEntry("canal", "POS");
    }

    @Test
    void ultima_ecommerce_no_deja_coordenadas_de_la_anterior() {
        ultima.escribir(evento(BASE, Decision.APROBAR));
        ultima.escribir(evento(UUID.randomUUID(), BASE.plus(Duration.ofMinutes(5)), Decision.APROBAR,
                Canal.ECOMMERCE, "dev_1"));

        var hash = redis.opsForHash().entries("ultima:" + TARJETA);
        assertThat(hash).containsEntry("canal", "ECOMMERCE").doesNotContainKeys("lat", "lon");
    }

    @Test
    void ultima_no_retrocede_con_un_evento_atrasado() {
        ultima.escribir(evento(BASE.plus(Duration.ofHours(1)), Decision.APROBAR));
        ultima.escribir(evento(UUID.randomUUID(), BASE, Decision.APROBAR, Canal.ECOMMERCE, null));

        var hash = redis.opsForHash().entries("ultima:" + TARJETA);
        assertThat(hash).containsEntry("fecha", String.valueOf(BASE.plus(Duration.ofHours(1)).toEpochMilli()))
                .containsEntry("canal", "POS");
    }

    @Test
    void dispositivos_solo_agrega_huellas_de_aprobar() {
        dispositivos.escribir(evento(UUID.randomUUID(), BASE, Decision.APROBAR, Canal.ECOMMERCE, "dev_ok"));
        dispositivos.escribir(evento(UUID.randomUUID(), BASE, Decision.REVISAR, Canal.ECOMMERCE, "dev_revisar"));
        dispositivos.escribir(evento(UUID.randomUUID(), BASE, Decision.RECHAZAR, Canal.ECOMMERCE, "dev_rechazo"));
        dispositivos.escribir(evento(UUID.randomUUID(), BASE, Decision.APROBAR, Canal.POS, null));

        assertThat(redis.opsForSet().members("dispositivos:" + CLIENTE)).containsExactly("dev_ok");
    }

    @Test
    void perfil_se_escribe_como_hash_con_los_campos_de_la_especificacion() {
        List<Integer> horas = new ArrayList<>(Collections.nCopies(24, 0));
        horas.set(3, 2);
        horas.set(14, 5);
        var perfil = new Perfil(12, new BigDecimal("180.00"), new BigDecimal("45.50"),
                new TreeSet<>(List.of("PE", "CL")), new TreeSet<>(List.of("5411", "5812")), horas, BASE);

        new EscritorPerfilValkey(redis).escribir(CLIENTE.toString(), perfil);

        var hash = redis.opsForHash().entries("perfil:" + CLIENTE);
        assertThat(hash).containsEntry("n30d", "12").containsEntry("promedio30d", "180.00")
                .containsEntry("desviacion30d", "45.50").containsEntry("paises", "CL,PE")
                .containsEntry("mccs", "5411,5812")
                .containsEntry("horas", "0,0,0,2,0,0,0,0,0,0,0,0,0,0,5,0,0,0,0,0,0,0,0,0")
                .containsEntry("actualizado", String.valueOf(BASE.toEpochMilli()));
    }
}
