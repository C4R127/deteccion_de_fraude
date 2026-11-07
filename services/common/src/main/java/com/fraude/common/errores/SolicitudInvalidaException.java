package com.fraude.common.errores;

/** Regla de negocio incumplida por la solicitud; se responde HTTP 422 con Problem Details. */
public class SolicitudInvalidaException extends RuntimeException {

    public SolicitudInvalidaException(String mensaje) {
        super(mensaje);
    }
}
