package com.fraude.scoring.metricas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Métricas de detección y latencia sobre {@code evaluacion} y {@code etiqueta_verdad} (§9,
 * ADR 0017). Es el único lugar que lee {@code etiqueta_verdad}: el motor nunca la consulta.
 *
 * <p>Marcada = REVISAR o RECHAZAR y no tardía (una evaluación tardía cuenta como respaldo,
 * ADR 0012). Los porcentajes son nulos cuando el denominador es 0.
 */
@Service
public class MetricasService {

    public record Latencia(BigDecimal p50, BigDecimal p95, BigDecimal p99) {
    }

    public record Deteccion(long fraudes, long fraudesMarcados, long marcadas, long legitimas, long legitimasMarcadas,
            BigDecimal deteccion, BigDecimal precision, BigDecimal falsosPositivos, BigDecimal montoFraudeEvitado,
            BigDecimal montoFraudeNoDetectado) {
    }

    /** Por escenario solo detección y montos: una legítima no pertenece a ningún escenario (ADR 0017). */
    public record Escenario(String escenario, long fraudes, long fraudesMarcados, BigDecimal deteccion,
            BigDecimal montoFraudeEvitado, BigDecimal montoFraudeNoDetectado) {
    }

    public record Metricas(Instant desde, Instant hasta, long evaluaciones, long evaluacionesTardias,
            BigDecimal transaccionesPorSegundo, Latencia latenciaMs, Deteccion deteccion, List<Escenario> porEscenario) {
    }

    private static final String MARCADA = "(e.decision IN ('REVISAR', 'RECHAZAR') AND NOT e.tardia)";
    private static final String EVITADO = "(e.decision = 'RECHAZAR' AND NOT e.tardia)";
    private static final String NO_DETECTADO = "(e.decision = 'APROBAR' OR e.tardia)";

    private final JdbcTemplate jdbc;

    public MetricasService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Metricas calcular(Instant desde, Instant hasta) {
        Timestamp d = Timestamp.from(desde);
        Timestamp h = Timestamp.from(hasta);

        Map<String, Object> g = jdbc.queryForMap("""
                SELECT count(*) AS evaluaciones,
                       count(*) FILTER (WHERE e.tardia) AS tardias,
                       count(v.escenario) AS fraudes,
                       count(v.escenario) FILTER (WHERE %1$s) AS fraudes_marcados,
                       count(*) FILTER (WHERE %1$s) AS marcadas,
                       count(*) FILTER (WHERE v.escenario IS NULL) AS legitimas,
                       count(*) FILTER (WHERE v.escenario IS NULL AND %1$s) AS legitimas_marcadas,
                       coalesce(sum(e.monto) FILTER (WHERE v.escenario IS NOT NULL AND %2$s), 0) AS evitado,
                       coalesce(sum(e.monto) FILTER (WHERE v.escenario IS NOT NULL AND %3$s), 0) AS no_detectado,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY e.latencia_ms) AS p50,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY e.latencia_ms) AS p95,
                       percentile_cont(0.99) WITHIN GROUP (ORDER BY e.latencia_ms) AS p99
                FROM evaluacion e
                LEFT JOIN etiqueta_verdad v ON v.transaccion_id_externo = e.id_externo
                WHERE e.creada_en >= ? AND e.creada_en < ?
                """.formatted(MARCADA, EVITADO, NO_DETECTADO), d, h);

        long evaluaciones = numero(g.get("evaluaciones"));
        long fraudes = numero(g.get("fraudes"));
        long fraudesMarcados = numero(g.get("fraudes_marcados"));
        long marcadas = numero(g.get("marcadas"));
        long legitimas = numero(g.get("legitimas"));
        long legitimasMarcadas = numero(g.get("legitimas_marcadas"));
        var deteccion = new Deteccion(fraudes, fraudesMarcados, marcadas, legitimas, legitimasMarcadas,
                porcentaje(fraudesMarcados, fraudes), porcentaje(fraudesMarcados, marcadas),
                porcentaje(legitimasMarcadas, legitimas), monto(g.get("evitado")), monto(g.get("no_detectado")));

        List<Escenario> porEscenario = jdbc.query("""
                SELECT v.escenario,
                       count(*) AS fraudes,
                       count(*) FILTER (WHERE %1$s) AS marcados,
                       coalesce(sum(e.monto) FILTER (WHERE %2$s), 0) AS evitado,
                       coalesce(sum(e.monto) FILTER (WHERE %3$s), 0) AS no_detectado
                FROM evaluacion e
                JOIN etiqueta_verdad v ON v.transaccion_id_externo = e.id_externo
                WHERE e.creada_en >= ? AND e.creada_en < ?
                GROUP BY v.escenario
                ORDER BY v.escenario
                """.formatted(MARCADA, EVITADO, NO_DETECTADO),
                (rs, n) -> new Escenario(rs.getString("escenario"), rs.getLong("fraudes"), rs.getLong("marcados"),
                        porcentaje(rs.getLong("marcados"), rs.getLong("fraudes")), monto(rs.getBigDecimal("evitado")),
                        monto(rs.getBigDecimal("no_detectado"))),
                d, h);

        double segundos = Duration.between(desde, hasta).toMillis() / 1000.0;
        BigDecimal tps = BigDecimal.valueOf(evaluaciones / segundos).setScale(2, RoundingMode.HALF_UP);
        var latencia = new Latencia(decimal(g.get("p50")), decimal(g.get("p95")), decimal(g.get("p99")));
        return new Metricas(desde, hasta, evaluaciones, numero(g.get("tardias")), tps, latencia, deteccion,
                porEscenario);
    }

    private static long numero(Object valor) {
        return ((Number) valor).longValue();
    }

    private static BigDecimal monto(Object valor) {
        return ((BigDecimal) valor).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(Object valor) {
        return valor == null ? null : BigDecimal.valueOf(((Number) valor).doubleValue()).setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    static BigDecimal porcentaje(long parte, long total) {
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf(parte * 100L).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }
}
