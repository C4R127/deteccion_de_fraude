package com.fraude.common.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Acceso a la tabla {@code outbox} (ADR 0010). */
@Repository
public class OutboxStore {

    public record Pendiente(UUID id, String topico, String clave, String payload, int intentos) {
    }

    private final JdbcTemplate jdbc;

    public OutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Debe llamarse dentro de la transacción del cambio de negocio. */
    public void guardar(String topico, String clave, String payload) {
        jdbc.update("INSERT INTO outbox (id, topico, clave, payload) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), topico, clave, payload);
    }

    /** Pendientes vencidos en orden de creación, bloqueados para este publicador. */
    public List<Pendiente> tomarPendientes(int limite) {
        return jdbc.query("""
                SELECT id, topico, clave, payload, intentos FROM outbox
                WHERE enviado_en IS NULL AND proximo_intento <= clock_timestamp()
                ORDER BY creado_en, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, n) -> new Pendiente(rs.getObject("id", UUID.class), rs.getString("topico"),
                        rs.getString("clave"), rs.getString("payload"), rs.getInt("intentos")),
                limite);
    }

    public void marcarEnviado(UUID id) {
        jdbc.update("UPDATE outbox SET enviado_en = clock_timestamp(), ultimo_error = NULL WHERE id = ?", id);
    }

    public void marcarFallo(UUID id, String error, Instant proximoIntento) {
        jdbc.update("UPDATE outbox SET intentos = intentos + 1, ultimo_error = ?, proximo_intento = ? WHERE id = ?",
                error, Timestamp.from(proximoIntento), id);
    }

    public long contarPendientes() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE enviado_en IS NULL", Long.class);
    }

    public int borrarEnviadosAntesDe(Instant limite) {
        return jdbc.update("DELETE FROM outbox WHERE enviado_en IS NOT NULL AND enviado_en < ?",
                Timestamp.from(limite));
    }
}
