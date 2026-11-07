package com.fraude.common.errores;

/** Un recurso referenciado no existe; se responde HTTP 404 con Problem Details. */
public class RecursoNoEncontradoException extends RuntimeException {

    public RecursoNoEncontradoException(String mensaje) {
        super(mensaje);
    }
}
