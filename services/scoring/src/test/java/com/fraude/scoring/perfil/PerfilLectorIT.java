package com.fraude.scoring.perfil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fraude.common.eventos.Canal;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Lee las claves que escribe perfiles (§5.4) con el mismo formato. */
@Testcontainers
class PerfilLectorIT {

    @Container
    static final GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8.1.11-alpine")
            .withExposedPorts(6379);

    static LettuceConnectionFactory conexion;
    static StringRedisTemplate redis;

    private static final UUID CLIENTE = UUID.randomUUID();
    private static final UUID TARJETA = UUID.randomUUID();
    private static final Instant FECHA = Instant.parse("2026-11-06T14:30:00Z");
    private static final Duration DIEZ_MINUTOS = Duration.ofMinutes(10);

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
    }

    private void escribirTodo() {
        redis.opsForHash().putAll("perfil:" + CLIENTE, Map.of(
                "n30d", "12", "promedio30d", "180.00", "desviacion30d", "45.50", "paises", "CL,PE",
                "mccs", "5411,5812", "horas", "0,0,0,2,0,0,0,0,0,0,0,0,0,0,5,0,0,0,0,0,0,0,0,1",
                "actualizado", "1794000000000"));
        redis.opsForHash().putAll("ultima:" + TARJETA, Map.of(
                "lat", "-12.046400", "lon", "-77.042800", "fecha", String.valueOf(FECHA.minusSeconds(600).toEpochMilli()),
                "canal", "POS"));
        redis.opsForSet().add("dispositivos:" + CLIENTE, "dev_a", "dev_b");
    }

    private void velocidad(Duration antes) {
        redis.opsForZSet().add("velocidad:" + TARJETA, UUID.randomUUID().toString(),
                FECHA.minus(antes).toEpochMilli());
    }

    @Test
    void lee_perfil_ultima_y_dispositivos() {
        escribirTodo();

        PerfilContexto ctx = new PerfilLector(redis).leer(CLIENTE, TARJETA, FECHA, DIEZ_MINUTOS);

        assertThat(ctx.n30d()).isEqualTo(12);
        assertThat(ctx.promedio30d()).isEqualByComparingTo("180.00");
        assertThat(ctx.desviacion30d()).isEqualByComparingTo("45.50");
        assertThat(ctx.paises()).containsExactlyInAnyOrder("PE", "CL");
        assertThat(ctx.mccs()).containsExactlyInAnyOrder("5411", "5812");
        assertThat(ctx.horas()).hasSize(24);
        assertThat(ctx.horas().get(3)).isEqualTo(2);
        assertThat(ctx.horas().get(23)).isEqualTo(1);
        assertThat(ctx.ultima()).isEqualTo(new UltimaUbicacion(new BigDecimal("-12.046400"),
                new BigDecimal("-77.042800"), FECHA.minusSeconds(600), Canal.POS));
        assertThat(ctx.dispositivos()).containsExactlyInAnyOrder("dev_a", "dev_b");
    }

    @Test
    void lee_todo_en_una_sola_ida_y_vuelta() {
        escribirTodo();
        StringRedisTemplate espia = spy(redis);

        new PerfilLector(espia).leer(CLIENTE, TARJETA, FECHA, DIEZ_MINUTOS);

        verify(espia, times(1)).executePipelined(any(RedisCallback.class));
        verify(espia, never()).opsForHash();
        verify(espia, never()).opsForZSet();
        verify(espia, never()).opsForSet();
    }

    @Test
    void cliente_sin_claves_devuelve_contexto_vacio_sin_excepcion() {
        PerfilContexto ctx = new PerfilLector(redis).leer(CLIENTE, TARJETA, FECHA, DIEZ_MINUTOS);

        assertThat(ctx.n30d()).isNull();
        assertThat(ctx.promedio30d()).isNull();
        assertThat(ctx.paises()).isEmpty();
        assertThat(ctx.mccs()).isEmpty();
        assertThat(ctx.horas()).isEmpty();
        assertThat(ctx.transaccionesUltimos10Min()).isZero();
        assertThat(ctx.ultima()).isNull();
        assertThat(ctx.dispositivos()).isEmpty();
    }

    @Test
    void cuenta_solo_transacciones_en_los_10_minutos_anteriores_a_la_fecha() {
        velocidad(Duration.ofMinutes(1));
        velocidad(Duration.ofMinutes(9));
        velocidad(Duration.ofMinutes(10));
        velocidad(Duration.ofMinutes(11));
        velocidad(Duration.ofMinutes(50));
        velocidad(Duration.ofSeconds(-30));
        velocidad(Duration.ZERO);

        PerfilContexto ctx = new PerfilLector(redis).leer(CLIENTE, TARJETA, FECHA, DIEZ_MINUTOS);

        // 1 min, 9 min y justo 10 min antes; no las de después ni la de la misma fecha.
        assertThat(ctx.transaccionesUltimos10Min()).isEqualTo(3);
    }

    @Test
    void ultima_ecommerce_sin_coordenadas() {
        redis.opsForHash().putAll("ultima:" + TARJETA, Map.of(
                "fecha", String.valueOf(FECHA.minusSeconds(60).toEpochMilli()), "canal", "ECOMMERCE"));

        PerfilContexto ctx = new PerfilLector(redis).leer(CLIENTE, TARJETA, FECHA, DIEZ_MINUTOS);

        assertThat(ctx.ultima().canal()).isEqualTo(Canal.ECOMMERCE);
        assertThat(ctx.ultima().latitud()).isNull();
        assertThat(ctx.ultima().longitud()).isNull();
    }
}
