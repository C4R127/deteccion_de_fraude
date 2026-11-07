package com.fraude.casos.caso;

/** Estado de un caso y su analista asignado (nulo si está NUEVO). */
public record EstadoActual(EstadoCaso estado, String analistaId) {
}
