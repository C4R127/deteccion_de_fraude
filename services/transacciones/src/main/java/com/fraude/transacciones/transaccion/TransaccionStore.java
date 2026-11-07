package com.fraude.transacciones.transaccion;

import com.fraude.common.eventos.Decision;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Escrituras de {@code transaccion} con SQL explícito: la inserción usa
 * {@code ON CONFLICT (id_externo) DO NOTHING} para que dos solicitudes simultáneas con el
 * mismo {@code idExterno} produzcan una sola fila (la segunda espera al commit de la primera).
 */
@Repository
public class TransaccionStore {

    private final JdbcTemplate jdbc;

    public TransaccionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserta la transacción pendiente ({@code decision = null}); false si el idExterno ya existía. */
    public boolean insertarPendiente(TransaccionPendiente t) {
        int filas = jdbc.update("""
                INSERT INTO transaccion (id, id_externo, tarjeta_id, cliente_id, comercio_id,
                    dispositivo_huella, monto, moneda, canal, pais, latitud, longitud, fecha)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id_externo) DO NOTHING
                """,
                t.id(), t.idExterno(), t.tarjetaId(), t.clienteId(), t.comercioId(), t.dispositivoHuella(),
                t.monto(), t.moneda(), t.canal().name(), t.pais(), t.latitud(), t.longitud(),
                Timestamp.from(t.fecha()));
        return filas == 1;
    }

    public void registrarDecision(UUID id, Decision decision, Integer score, boolean respaldo) {
        jdbc.update("UPDATE transaccion SET decision = ?, score = ?, respaldo = ? WHERE id = ?",
                decision.name(), score, respaldo, id);
    }

    public Optional<TransaccionResponse> buscarRespuesta(UUID idExterno) {
        return jdbc.query("SELECT id, decision, score, respaldo FROM transaccion WHERE id_externo = ?",
                (rs, n) -> new TransaccionResponse(
                        rs.getObject("id", UUID.class),
                        rs.getString("decision") == null ? null : Decision.valueOf(rs.getString("decision")),
                        rs.getObject("score", Integer.class),
                        rs.getBoolean("respaldo")),
                idExterno).stream().findFirst();
    }
}
