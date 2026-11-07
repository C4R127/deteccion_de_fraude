package com.fraude.scoring.regla;

import java.util.Map;

/** Cambios a una regla; un campo nulo conserva el valor de la versión anterior. */
public record EdicionRegla(
        String nombre,
        String descripcion,
        Map<String, Object> parametros,
        Integer puntos,
        Boolean activa) {
}
