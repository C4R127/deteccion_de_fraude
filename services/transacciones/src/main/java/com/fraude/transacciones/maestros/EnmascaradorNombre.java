package com.fraude.transacciones.maestros;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Enmascara un nombre para AUDITOR (§10, ADR 0016): cada palabra deja su inicial y {@code ***}. */
public final class EnmascaradorNombre {

    private EnmascaradorNombre() {
    }

    public static String enmascarar(String nombre) {
        if (nombre == null) {
            return null;
        }
        return Arrays.stream(nombre.strip().split("\\s+"))
                .filter(palabra -> !palabra.isEmpty())
                .map(palabra -> palabra.substring(0, palabra.offsetByCodePoints(0, 1)) + "***")
                .collect(Collectors.joining(" "));
    }
}
