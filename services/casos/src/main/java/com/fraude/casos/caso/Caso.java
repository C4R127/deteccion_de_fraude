package com.fraude.casos.caso;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.common.eventos.Resolucion;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Fila de {@code caso} (§5.3). */
public record Caso(
        UUID id,
        UUID transaccionId,
        UUID tarjetaId,
        UUID clienteId,
        String dispositivoHuella,
        BigDecimal monto,
        int score,
        Decision decision,
        List<ReglaDisparada> reglasDisparadas,
        Prioridad prioridad,
        EstadoCaso estado,
        String analistaId,
        Instant creadoEn,
        Instant cerradoEn,
        Resolucion resolucion) {

    @JsonIgnore
    public EstadoActual estadoActual() {
        return new EstadoActual(estado, analistaId);
    }
}
