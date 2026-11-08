package com.fraude.common.metricas;

import com.fraude.common.errores.SolicitudInvalidaException;
import java.time.Duration;
import java.time.Instant;

/**
 * Ventana {@code [desde, hasta)} de los endpoints de métricas (ADR 0017). Sin {@code hasta}
 * termina ahora; sin {@code desde}, empieza {@code porDefecto} antes del final.
 */
public record Ventana(Instant desde, Instant hasta) {

    public static Ventana resolver(Instant desde, Instant hasta, Duration porDefecto) {
        Instant fin = hasta != null ? hasta : Instant.now();
        Instant inicio = desde != null ? desde : fin.minus(porDefecto);
        if (!inicio.isBefore(fin)) {
            throw new SolicitudInvalidaException("desde debe ser anterior a hasta");
        }
        return new Ventana(inicio, fin);
    }
}
