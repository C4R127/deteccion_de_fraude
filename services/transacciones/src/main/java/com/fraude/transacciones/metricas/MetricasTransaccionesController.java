package com.fraude.transacciones.metricas;

import com.fraude.common.errores.SolicitudInvalidaException;
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
 * Volumen por minuto, decisiones y respaldos (§9, ADR 0017). La ventana usa la hora de
 * registro ({@code creada_en}): {@code historia} envía fechas pasadas pero se procesa ahora.
 */
@RestController
public class MetricasTransaccionesController {

    static final Duration VENTANA_POR_DEFECTO = Duration.ofHours(1);

    public record Minuto(Instant minuto, long total, long aprobar, long revisar, long rechazar, long respaldos) {
    }

    public record MetricasTransacciones(Instant desde, Instant hasta, long total, long respaldos,
            long tarjetaBloqueada, long pendientes, Map<String, Long> porDecision, List<Minuto> porMinuto) {
    }

    private final JdbcTemplate jdbc;

    public MetricasTransaccionesController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ventana {@code [desde, hasta)}; por defecto, la última hora. */
    @GetMapping("/api/v1/metricas/transacciones")
    public MetricasTransacciones obtener(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant hasta) {
        Instant fin = hasta != null ? hasta : Instant.now();
        Instant inicio = desde != null ? desde : fin.minus(VENTANA_POR_DEFECTO);
        if (!inicio.isBefore(fin)) {
            throw new SolicitudInvalidaException("desde debe ser anterior a hasta");
        }
        Timestamp d = Timestamp.from(inicio);
        Timestamp h = Timestamp.from(fin);

        Map<String, Object> g = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE respaldo) AS respaldos,
                       count(*) FILTER (WHERE decision = 'RECHAZAR' AND score IS NULL AND NOT respaldo) AS bloqueada,
                       count(*) FILTER (WHERE decision IS NULL) AS pendientes,
                       count(*) FILTER (WHERE decision = 'APROBAR') AS aprobar,
                       count(*) FILTER (WHERE decision = 'REVISAR') AS revisar,
                       count(*) FILTER (WHERE decision = 'RECHAZAR') AS rechazar
                FROM transaccion WHERE creada_en >= ? AND creada_en < ?
                """, d, h);
        Map<String, Long> porDecision = new LinkedHashMap<>();
        porDecision.put("APROBAR", numero(g.get("aprobar")));
        porDecision.put("REVISAR", numero(g.get("revisar")));
        porDecision.put("RECHAZAR", numero(g.get("rechazar")));

        List<Minuto> porMinuto = jdbc.query("""
                SELECT date_trunc('minute', creada_en) AS minuto,
                       count(*) AS total,
                       count(*) FILTER (WHERE decision = 'APROBAR') AS aprobar,
                       count(*) FILTER (WHERE decision = 'REVISAR') AS revisar,
                       count(*) FILTER (WHERE decision = 'RECHAZAR') AS rechazar,
                       count(*) FILTER (WHERE respaldo) AS respaldos
                FROM transaccion WHERE creada_en >= ? AND creada_en < ?
                GROUP BY 1 ORDER BY 1
                """, (rs, n) -> new Minuto(rs.getTimestamp("minuto").toInstant(), rs.getLong("total"),
                rs.getLong("aprobar"), rs.getLong("revisar"), rs.getLong("rechazar"), rs.getLong("respaldos")), d, h);

        return new MetricasTransacciones(inicio, fin, numero(g.get("total")), numero(g.get("respaldos")),
                numero(g.get("bloqueada")), numero(g.get("pendientes")), porDecision, porMinuto);
    }

    private static long numero(Object valor) {
        return ((Number) valor).longValue();
    }
}
