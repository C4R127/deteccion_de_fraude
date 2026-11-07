package com.fraude.scoring.regla;

/** Versión vigente de una regla, inmutable, tal como la usa el motor. */
public record ReglaVigente(
        String codigo,
        String nombre,
        String descripcion,
        Parametros parametros,
        int puntos,
        boolean activa,
        int version) {

    static ReglaVigente de(ReglaEntity e) {
        return new ReglaVigente(e.getCodigo(), e.getNombre(), e.getDescripcion(), new Parametros(e.getParametros()),
                e.getPuntos(), e.isActiva(), e.getVersion());
    }
}
