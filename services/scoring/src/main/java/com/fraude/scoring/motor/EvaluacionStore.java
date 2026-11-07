package com.fraude.scoring.motor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.common.evaluacion.EvaluacionResultado;
import com.fraude.common.evaluacion.EvaluacionSolicitud;
import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.common.json.JsonFraude;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistencia de {@code evaluacion} (solo INSERT). */
@Repository
public class EvaluacionStore {

    private static final TypeReference<List<ReglaDisparada>> LISTA_REGLAS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = JsonFraude.objectMapper();

    public EvaluacionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserta la evaluación; false si ya existía una para esa transacción. */
    public boolean insertar(EvaluacionSolicitud s, Calculo c, long latenciaMs, boolean tardia, Instant ahora) {
        int filas = jdbc.update("""
                INSERT INTO evaluacion (id, transaccion_id, id_externo, monto, score, decision, reglas_disparadas,
                                        versiones_reglas, latencia_ms, creada_en, tardia)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                ON CONFLICT (transaccion_id) DO NOTHING
                """,
                UUID.randomUUID(), s.transaccionId(), s.idExterno(), s.monto(), c.score(), c.decision().name(),
                aJson(c.reglasDisparadas()), aJson(c.versionesReglas()), (int) Math.min(latenciaMs, Integer.MAX_VALUE),
                Timestamp.from(ahora), tardia);
        return filas == 1;
    }

    public Optional<EvaluacionResultado> buscar(UUID transaccionId) {
        return jdbc.query("SELECT score, decision, reglas_disparadas::text AS reglas FROM evaluacion "
                        + "WHERE transaccion_id = ?",
                (rs, n) -> new EvaluacionResultado(rs.getInt("score"), Decision.valueOf(rs.getString("decision")),
                        deJson(rs.getString("reglas"))),
                transaccionId).stream().findFirst();
    }

    /** Evaluación completa para la consulta {@code GET /api/v1/evaluaciones/{transaccionId}}. */
    public record EvaluacionDetalle(UUID transaccionId, UUID idExterno, BigDecimal monto, int score,
            Decision decision, List<ReglaDisparada> reglasDisparadas, Map<String, Integer> versionesReglas,
            int latenciaMs, boolean tardia, Instant creadaEn) {
    }

    private static final TypeReference<Map<String, Integer>> MAPA_VERSIONES = new TypeReference<>() {
    };

    public Optional<EvaluacionDetalle> detalle(UUID transaccionId) {
        return jdbc.query("SELECT transaccion_id, id_externo, monto, score, decision, reglas_disparadas::text AS reglas, "
                        + "versiones_reglas::text AS versiones, latencia_ms, tardia, creada_en "
                        + "FROM evaluacion WHERE transaccion_id = ?",
                (rs, n) -> new EvaluacionDetalle(rs.getObject("transaccion_id", UUID.class),
                        rs.getObject("id_externo", UUID.class), rs.getBigDecimal("monto"), rs.getInt("score"),
                        Decision.valueOf(rs.getString("decision")), deJson(rs.getString("reglas")),
                        versiones(rs.getString("versiones")), rs.getInt("latencia_ms"), rs.getBoolean("tardia"),
                        rs.getTimestamp("creada_en").toInstant()),
                transaccionId).stream().findFirst();
    }

    private Map<String, Integer> versiones(String texto) {
        try {
            return json.readValue(texto, MAPA_VERSIONES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Versiones de reglas ilegibles", e);
        }
    }

    private String aJson(Object valor) {
        try {
            return json.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar la evaluación", e);
        }
    }

    private List<ReglaDisparada> deJson(String texto) {
        try {
            return json.readValue(texto, LISTA_REGLAS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Reglas disparadas ilegibles", e);
        }
    }
}
