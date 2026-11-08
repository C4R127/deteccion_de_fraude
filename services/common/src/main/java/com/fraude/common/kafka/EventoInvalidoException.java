package com.fraude.common.kafka;

/** Evento que no se puede procesar por su contenido: va a la DLT sin reintentos. */
public class EventoInvalidoException extends RuntimeException {

    public EventoInvalidoException(String mensaje) {
        super(mensaje);
    }
}
