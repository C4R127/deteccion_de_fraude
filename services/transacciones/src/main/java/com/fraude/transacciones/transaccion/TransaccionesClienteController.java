package com.fraude.transacciones.transaccion;

import com.fraude.common.errores.RecursoNoEncontradoException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Últimas transacciones de un cliente para el detalle del caso (§8.3, §11). La tarjeta va solo
 * por sus últimos 4 dígitos; nunca el token ni el número.
 */
@RestController
public class TransaccionesClienteController {

    public record TransaccionResumen(UUID id, UUID idExterno, Instant fecha, BigDecimal monto, String moneda,
            String canal, String comercioId, String comercioNombre, String mcc, String ciudad, String pais,
            BigDecimal latitud, BigDecimal longitud, String ultimos4, String dispositivoHuella, String decision,
            Integer score, boolean respaldo) {
    }

    private final JdbcTemplate jdbc;

    public TransaccionesClienteController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/clientes/{id}/transacciones")
    public List<TransaccionResumen> ultimas(@PathVariable UUID id,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limite) {
        Integer existe = jdbc.queryForObject("SELECT count(*) FROM cliente WHERE id = ?", Integer.class, id);
        if (existe == null || existe == 0) {
            throw new RecursoNoEncontradoException("El cliente " + id + " no existe");
        }
        return jdbc.query("""
                SELECT t.id, t.id_externo, t.fecha, t.monto, t.moneda, t.canal, t.comercio_id, c.nombre AS comercio,
                       c.mcc, c.ciudad, t.pais, t.latitud, t.longitud, k.ultimos4, t.dispositivo_huella, t.decision,
                       t.score, t.respaldo
                FROM transaccion t
                JOIN comercio c ON c.id = t.comercio_id
                JOIN tarjeta k ON k.id = t.tarjeta_id
                WHERE t.cliente_id = ?
                ORDER BY t.fecha DESC, t.id
                LIMIT ?
                """, (rs, n) -> new TransaccionResumen(rs.getObject("id", UUID.class),
                rs.getObject("id_externo", UUID.class), rs.getTimestamp("fecha").toInstant(), rs.getBigDecimal("monto"),
                rs.getString("moneda"), rs.getString("canal"), rs.getString("comercio_id"), rs.getString("comercio"),
                rs.getString("mcc"), rs.getString("ciudad"), rs.getString("pais"), rs.getBigDecimal("latitud"),
                rs.getBigDecimal("longitud"), rs.getString("ultimos4"), rs.getString("dispositivo_huella"),
                rs.getString("decision"), (Integer) rs.getObject("score"), rs.getBoolean("respaldo")),
                id, limite);
    }
}
