package com.fraude.common.eventos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Publicado por scoring en {@link Topicos#DECISIONES_SOSPECHOSAS} cuando la decisión es
 * REVISAR o RECHAZAR. Clave del mensaje: {@code tarjetaId}.
 */
public record DecisionSospechosaEvento(
        int version,
        UUID transaccionId,
        UUID tarjetaId,
        UUID clienteId,
        String dispositivoHuella,
        BigDecimal monto,
        int score,
        Decision decision,
        List<ReglaDisparada> reglasDisparadas,
        Instant fecha) {

    public static final int VERSION_ACTUAL = 1;

    public DecisionSospechosaEvento {
        reglasDisparadas = reglasDisparadas == null ? List.of() : List.copyOf(reglasDisparadas);
    }
}
