package com.fraude.scoring.bloqueo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Consulta de listas de bloqueo vigentes por tarjeta, dispositivo o comercio (§7.1 paso 1). */
@Service
public class ListaBloqueoService {

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
}
