package com.fraude.scoring.motor;

import com.fraude.common.eventos.Decision;

/**
 * Umbrales de decisión (§7.3, configurables): por debajo de {@code revisar} se aprueba, desde
 * {@code rechazar} se rechaza y entre ambos se revisa.
 */
public record Umbrales(int revisar, int rechazar) {

    public Decision decidir(int score) {
        if (score >= rechazar) {
            return Decision.RECHAZAR;
        }
        return score >= revisar ? Decision.REVISAR : Decision.APROBAR;
    }
}
