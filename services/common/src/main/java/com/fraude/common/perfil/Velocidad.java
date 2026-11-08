package com.fraude.common.perfil;

import java.time.Duration;

/**
 * Cuánto guarda perfiles de {@code velocidad:{tarjetaId}} en Valkey. La ventana de R02
 * ({@code ventanaMinutos}) no puede superarla: contaría de menos sin aviso.
 */
public final class Velocidad {

    public static final Duration RETENCION = Duration.ofHours(1);

    private Velocidad() {
    }
}
