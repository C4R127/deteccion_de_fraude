package com.fraude.common.evaluacion;

import com.fraude.common.eventos.Canal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Cuerpo de {@code POST /internal/v1/evaluaciones} (ADR 0006). */
public record EvaluacionSolicitud(
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
        Instant fecha) {
}
