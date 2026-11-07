package com.fraude.transacciones.transaccion;

import com.fraude.common.eventos.Decision;
import java.util.UUID;

/** Respuesta de {@code POST /api/v1/transacciones}; {@code score} es nulo con respaldo. */
public record TransaccionResponse(UUID transaccionId, Decision decision, Integer score, boolean respaldo) {
}
