package com.fraude.transacciones.transaccion;

import com.fraude.common.eventos.Canal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Transacción validada y resuelta (tarjeta, cliente, comercio) antes de decidir. */
public record TransaccionPendiente(
        UUID id,
        UUID idExterno,
        UUID tarjetaId,
        UUID clienteId,
        String comercioId,
        String mcc,
        String dispositivoHuella,
        BigDecimal monto,
        String moneda,
        Canal canal,
        String pais,
        BigDecimal latitud,
        BigDecimal longitud,
        Instant fecha) {
}
