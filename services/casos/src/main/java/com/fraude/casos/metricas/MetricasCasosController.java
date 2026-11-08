package com.fraude.casos.metricas;

import com.fraude.common.errores.SolicitudInvalidaException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Casos abiertos por estado, prioridad y analista (foto actual) y resolución de los casos
 * cerrados en la ventana (§9, ADR 0017).
 */
@RestController
public class MetricasCasosController {

    static final Duration VENTANA_POR_DEFECTO = Duration.ofDays(7);

    public record PorAnalista(String analista, long abiertos) {
    }

    public record MetricasCasos(Instant desde, Instant hasta, long abiertos, Map<String, Long> abiertosPorEstado,
            Map<String, Long> abiertosPorPrioridad, List<PorAnalista> abiertosPorAnalista, long cerrados,
            Map<String, Long> cerradosPorResolucion, BigDecimal tiempoMedioResolucionMinutos) {
    }

    private final JdbcTemplate jdbc;

    public MetricasCasosController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ventana {@code [desde, hasta)} para los cierres; por defecto, los últimos 7 días. */
    @GetMapping("/api/v1/metricas/casos")
    public MetricasCasos obtener(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant hasta) {
        Instant fin = hasta != null ? hasta : Instant.now();
        Instant inicio = desde != null ? desde : fin.minus(VENTANA_POR_DEFECTO);
        if (!inicio.isBefore(fin)) {
            throw new SolicitudInvalidaException("desde debe ser anterior a hasta");
        }

        Map<String, Long> porEstado = conteo("SELECT estado, count(*) FROM caso WHERE estado <> 'CERRADO' GROUP BY 1",
                "NUEVO", "ASIGNADO", "EN_INVESTIGACION");
        Map<String, Long> porPrioridad = conteo(
                "SELECT prioridad, count(*) FROM caso WHERE estado <> 'CERRADO' GROUP BY 1", "ALTA", "MEDIA");
        List<PorAnalista> porAnalista = jdbc.query("""
                SELECT analista_id, count(*) AS abiertos FROM caso WHERE estado <> 'CERRADO'
                GROUP BY analista_id ORDER BY analista_id NULLS LAST
                """, (rs, n) -> new PorAnalista(rs.getString("analista_id"), rs.getLong("abiertos")));
        long abiertos = porEstado.values().stream().mapToLong(Long::longValue).sum();

        Timestamp d = Timestamp.from(inicio);
        Timestamp h = Timestamp.from(fin);
        Map<String, Long> porResolucion = conteo("""
                SELECT resolucion, count(*) FROM caso
                WHERE estado = 'CERRADO' AND cerrado_en >= ? AND cerrado_en < ? GROUP BY 1
                """, new String[] {"FRAUDE_CONFIRMADO", "FALSO_POSITIVO"}, d, h);
        BigDecimal minutos = jdbc.queryForObject("""
                SELECT avg(extract(epoch FROM cerrado_en - creado_en) / 60) FROM caso
                WHERE estado = 'CERRADO' AND cerrado_en >= ? AND cerrado_en < ?
                """, BigDecimal.class, d, h);

        return new MetricasCasos(inicio, fin, abiertos, porEstado, porPrioridad, porAnalista,
                porResolucion.values().stream().mapToLong(Long::longValue).sum(), porResolucion,
                minutos == null ? null : minutos.setScale(1, RoundingMode.HALF_UP));
    }

    /** Conteos por clave, con 0 para las claves que no aparecen. */
    private Map<String, Long> conteo(String sql, String[] claves, Object... args) {
        Map<String, Long> resultado = new LinkedHashMap<>();
        for (String clave : claves) {
            resultado.put(clave, 0L);
        }
        jdbc.query(sql, rs -> {
            resultado.put(rs.getString(1), rs.getLong(2));
        }, args);
        return resultado;
    }

    private Map<String, Long> conteo(String sql, String... claves) {
        return conteo(sql, claves, new Object[0]);
    }
}
