package com.fraude.common.errores;

/** La operación no es válida en el estado actual del recurso; se responde HTTP 409 con Problem Details. */
public class ConflictoException extends RuntimeException {

    public ConflictoException(String mensaje) {
        super(mensaje);
    }
}
