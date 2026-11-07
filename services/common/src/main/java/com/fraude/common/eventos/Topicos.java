package com.fraude.common.eventos;

/** Nombres de los tópicos de Kafka (§5.5). */
public final class Topicos {

    public static final String TRANSACCIONES_CREADAS = "transacciones.creadas";
    public static final String DECISIONES_SOSPECHOSAS = "decisiones.sospechosas";
    public static final String CASOS_EVENTOS = "casos.eventos";

    private Topicos() {
    }
}
