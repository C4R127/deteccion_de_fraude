package com.fraude.scoring.perfil;

import com.fraude.common.eventos.Canal;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Lee de Valkey, en una sola ida y vuelta (pipeline), el perfil del cliente, la velocidad y
 * la última ubicación de la tarjeta y los dispositivos conocidos (§7.1 paso 2).
 */
@Component
public class PerfilLector {

    private final StringRedisTemplate redis;

    public PerfilLector(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @param fecha   fecha de la transacción evaluada
     * @param ventana para contar transacciones previas en {@code [fecha − ventana, fecha)}
     */
    @SuppressWarnings("unchecked")
    public PerfilContexto leer(UUID clienteId, UUID tarjetaId, Instant fecha, Duration ventana) {
        long hasta = fecha.toEpochMilli() - 1; // scores en ms: excluye la propia fecha
        long desde = fecha.minus(ventana).toEpochMilli();
        List<Object> r = redis.executePipelined((RedisCallback<Object>) conexion -> {
            StringRedisConnection c = (StringRedisConnection) conexion;
            c.hGetAll("perfil:" + clienteId);
            c.zCount("velocidad:" + tarjetaId, desde, hasta);
            c.hGetAll("ultima:" + tarjetaId);
            c.sMembers("dispositivos:" + clienteId);
            return null;
        });
        Map<String, String> perfil = (Map<String, String>) r.get(0);
        Long velocidad = (Long) r.get(1);
        Map<String, String> ultima = (Map<String, String>) r.get(2);
        Set<String> dispositivos = (Set<String>) r.get(3);

        boolean conPerfil = perfil != null && !perfil.isEmpty();
        return new PerfilContexto(
                conPerfil ? Integer.valueOf(perfil.get("n30d")) : null,
                conPerfil ? new BigDecimal(perfil.get("promedio30d")) : null,
                conPerfil ? new BigDecimal(perfil.get("desviacion30d")) : null,
                conPerfil ? csv(perfil.get("paises")) : Set.of(),
                conPerfil ? csv(perfil.get("mccs")) : Set.of(),
                conPerfil ? horas(perfil.get("horas")) : List.of(),
                velocidad == null ? 0 : velocidad,
                ultima(ultima),
                dispositivos == null ? Set.of() : Set.copyOf(dispositivos));
    }

    private static Set<String> csv(String valor) {
        if (valor == null || valor.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(valor.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static List<Integer> horas(String valor) {
        if (valor == null || valor.isBlank()) {
            return List.of();
        }
        return Arrays.stream(valor.split(",")).map(String::trim).map(Integer::valueOf).toList();
    }

    private static UltimaUbicacion ultima(Map<String, String> h) {
        if (h == null || h.isEmpty() || h.get("fecha") == null) {
            return null;
        }
        String lat = h.get("lat");
        String lon = h.get("lon");
        return new UltimaUbicacion(
                lat == null || lat.isBlank() ? null : new BigDecimal(lat),
                lon == null || lon.isBlank() ? null : new BigDecimal(lon),
                Instant.ofEpochMilli(Long.parseLong(h.get("fecha"))),
                h.get("canal") == null ? null : Canal.valueOf(h.get("canal")));
    }
}
