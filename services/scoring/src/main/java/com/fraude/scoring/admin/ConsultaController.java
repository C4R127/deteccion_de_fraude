package com.fraude.scoring.admin;

import com.fraude.common.errores.RecursoNoEncontradoException;
import com.fraude.scoring.motor.EvaluacionStore;
import com.fraude.scoring.motor.EvaluacionStore.EvaluacionDetalle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Consultas de lectura (§11): perfil del cliente (Valkey) y evaluación de una transacción. */
@RestController
public class ConsultaController {

    private final StringRedisTemplate redis;
    private final EvaluacionStore evaluaciones;

    public ConsultaController(StringRedisTemplate redis, EvaluacionStore evaluaciones) {
        this.redis = redis;
        this.evaluaciones = evaluaciones;
    }

    public record PerfilResponse(UUID clienteId, int n30d, BigDecimal promedio30d, BigDecimal desviacion30d,
            Set<String> paises, Set<String> mccs, List<Integer> horas, Set<String> dispositivos, Instant actualizado) {
    }

    @GetMapping("/api/v1/clientes/{id}/perfil")
    public PerfilResponse perfil(@PathVariable UUID id) {
        Map<Object, Object> h = redis.opsForHash().entries("perfil:" + id);
        if (h.isEmpty()) {
            throw new RecursoNoEncontradoException("El cliente " + id + " no tiene perfil");
        }
        Set<String> dispositivos = redis.opsForSet().members("dispositivos:" + id);
        return new PerfilResponse(id, Integer.parseInt(texto(h, "n30d")), new BigDecimal(texto(h, "promedio30d")),
                new BigDecimal(texto(h, "desviacion30d")), csv(texto(h, "paises")), csv(texto(h, "mccs")),
                Arrays.stream(texto(h, "horas").split(",")).map(String::trim).map(Integer::valueOf).toList(),
                dispositivos == null ? Set.of() : new TreeSet<>(dispositivos),
                Instant.ofEpochMilli(Long.parseLong(texto(h, "actualizado"))));
    }

    @GetMapping("/api/v1/evaluaciones/{transaccionId}")
    public EvaluacionDetalle evaluacion(@PathVariable UUID transaccionId) {
        return evaluaciones.detalle(transaccionId).orElseThrow(
                () -> new RecursoNoEncontradoException("No hay evaluación para la transacción " + transaccionId));
    }

    private static String texto(Map<Object, Object> h, String campo) {
        Object v = h.get(campo);
        return v == null ? "" : v.toString();
    }

    private static Set<String> csv(String valor) {
        TreeSet<String> conjunto = new TreeSet<>();
        for (String s : valor.split(",")) {
            if (!s.isBlank()) {
                conjunto.add(s.trim());
            }
        }
        return conjunto;
    }
}
