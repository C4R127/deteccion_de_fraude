package com.fraude.common.eventos;

/** Regla que se cumplió en una evaluación, con su aporte al score (§7.2). */
public record ReglaDisparada(String codigo, int puntos, String detalle) {}
