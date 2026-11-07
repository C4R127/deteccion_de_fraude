package com.fraude.common.outbox;

/** Envía un evento y vuelve solo cuando el broker confirmó (ack); si no, lanza excepción. */
public interface EnviadorEventos {

    void enviar(String topico, String clave, String payload) throws Exception;
}
