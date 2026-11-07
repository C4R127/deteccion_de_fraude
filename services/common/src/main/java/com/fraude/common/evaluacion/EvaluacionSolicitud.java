package com.fraude.common.evaluacion;

import com.fraude.common.eventos.Canal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Cuerpo de {@code POST /internal/v1/evaluaciones} (ADR 0006). */
public record EvaluacionSolicitud(
        @NotNull UUID transaccionId,
        @NotNull UUID idExterno,
        @NotNull UUID tarjetaId,
        @NotNull UUID clienteId,
        @NotBlank String comercioId,
        @NotBlank String mcc,
        String dispositivoHuella,
        @NotNull @Positive BigDecimal monto,
        @NotBlank String moneda,
        @NotNull Canal canal,
        @NotBlank String pais,
        BigDecimal latitud,
        BigDecimal longitud,
        @NotNull Instant fecha) {
}
