package com.fraude.common.eventos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Publicado por transacciones en {@link Topicos#TRANSACCIONES_CREADAS} tras guardar la
 * decisión. Clave del mensaje: {@code tarjetaId}. {@code score} es nulo si hubo respaldo.
 */
public record TransaccionCreadaEvento(
        int version,
        UUID transaccionId,
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
        Instant fecha,
        Decision decision,
        Integer score,
        boolean respaldo) {

    public static final int VERSION_ACTUAL = 1;
}
