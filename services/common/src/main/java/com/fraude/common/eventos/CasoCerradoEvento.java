package com.fraude.common.eventos;

import java.time.Instant;
import java.util.UUID;

/**
 * Publicado por casos en {@link Topicos#CASOS_EVENTOS} al cerrar un caso (§8.2).
 * Clave del mensaje: {@code tarjetaId}.
 */
public record CasoCerradoEvento(
        int version,
        UUID casoId,
        UUID transaccionId,
        UUID tarjetaId,
        String dispositivoHuella,
        Resolucion resolucion,
        Instant fecha) {

    public static final int VERSION_ACTUAL = 1;
}
