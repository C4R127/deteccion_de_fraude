package com.fraude.scoring.bloqueo;

import com.fraude.common.errores.RecursoNoEncontradoException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Listas de bloqueo (§5.2, §7.1 paso 1, ADR 0014). */
@Service
public class ListaBloqueoService {

    /** Fila completa de {@code lista_bloqueo}. */
    public record Entrada(UUID id, String tipo, String valor, String motivo, String origen, Instant vigenteHasta,
            Instant creadaEn, String creadaPor) {
    }

    private final JdbcTemplate jdbc;

    public ListaBloqueoService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Primera coincidencia vigente en {@code ahora}; la tarjeta se compara por su id. */
    public Optional<Bloqueo> buscar(UUID tarjetaId, String huella, String comercioId, Instant ahora) {
        return jdbc.query("""
                SELECT tipo, valor, motivo FROM lista_bloqueo
                WHERE ((tipo = 'TARJETA' AND valor = ?) OR (tipo = 'DISPOSITIVO' AND valor = ?)
                       OR (tipo = 'COMERCIO' AND valor = ?))
                  AND (vigente_hasta IS NULL OR vigente_hasta > ?)
                ORDER BY tipo DESC
                LIMIT 1
                """,
                (rs, n) -> new Bloqueo(rs.getString("tipo"), rs.getString("valor"), rs.getString("motivo")),
                tarjetaId.toString(), huella, comercioId, Timestamp.from(ahora)).stream().findFirst();
    }

    /**
     * Bloquea {@code (tipo, valor)}. Si ya existía (vigente o vencida), la reactiva con los
     * datos nuevos: hay una sola fila por {@code (tipo, valor)}.
     */
    public Entrada bloquear(String tipo, String valor, String motivo, String origen, Instant vigenteHasta,
            String usuario) {
        return jdbc.queryForObject("""
                INSERT INTO lista_bloqueo (id, tipo, valor, motivo, origen, vigente_hasta, creada_en, creada_por)
                VALUES (?, ?, ?, ?, ?, ?, now(), ?)
                ON CONFLICT (tipo, valor) DO UPDATE
                    SET motivo = EXCLUDED.motivo, origen = EXCLUDED.origen, vigente_hasta = EXCLUDED.vigente_hasta,
                        creada_en = EXCLUDED.creada_en, creada_por = EXCLUDED.creada_por
                RETURNING *
                """, ListaBloqueoService::entrada,
                UUID.randomUUID(), tipo, valor, motivo, origen,
                vigenteHasta == null ? null : Timestamp.from(vigenteHasta), usuario);
    }

    public List<Entrada> listar() {
        return jdbc.query("SELECT * FROM lista_bloqueo ORDER BY creada_en DESC", ListaBloqueoService::entrada);
    }

    /**
     * Vence la entrada en {@code ahora} (deja de bloquear, la fila se conserva). Usa el reloj de
     * la aplicación, el mismo con el que {@link #buscar} compara: con {@code now()} de la base
     * de datos, una diferencia de relojes dejaba vigente una entrada recién vencida.
     */
    public void vencer(UUID id, Instant ahora) {
        Timestamp momento = Timestamp.from(ahora);
        int filas = jdbc.update("UPDATE lista_bloqueo SET vigente_hasta = ? "
                + "WHERE id = ? AND (vigente_hasta IS NULL OR vigente_hasta > ?)", momento, id, momento);
        if (filas == 0 && jdbc.queryForObject("SELECT count(*) FROM lista_bloqueo WHERE id = ?", Integer.class, id) == 0) {
            throw new RecursoNoEncontradoException("La entrada " + id + " no existe");
        }
    }

    private static Entrada entrada(ResultSet rs, int n) throws SQLException {
        Timestamp hasta = rs.getTimestamp("vigente_hasta");
        return new Entrada(rs.getObject("id", UUID.class), rs.getString("tipo"), rs.getString("valor"),
                rs.getString("motivo"), rs.getString("origen"), hasta == null ? null : hasta.toInstant(),
                rs.getTimestamp("creada_en").toInstant(), rs.getString("creada_por"));
    }
}
