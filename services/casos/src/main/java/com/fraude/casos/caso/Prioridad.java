package com.fraude.casos.caso;

import com.fraude.common.eventos.Decision;

/** Prioridad del caso según la decisión (§7.3): RECHAZAR → ALTA, REVISAR → MEDIA. */
public enum Prioridad {
    ALTA, MEDIA;

    public static Prioridad de(Decision decision) {
        return switch (decision) {
            case RECHAZAR -> ALTA;
            case REVISAR -> MEDIA;
            case APROBAR -> throw new IllegalArgumentException("Una decisión APROBAR no genera caso");
        };
    }
}
