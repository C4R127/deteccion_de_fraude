package com.fraude.scoring.regla;

import java.time.Instant;
import java.util.Map;

/** Vista de una versión de regla para la API. */
public record VersionRegla(
        String codigo,
        String nombre,
        String descripcion,
        Map<String, Object> parametros,
        int puntos,
        boolean activa,
        int version,
        Boolean vigente,
        Instant creadaEn,
        String creadaPor) {

    public static VersionRegla de(ReglaVigente r) {
        return new VersionRegla(r.codigo(), r.nombre(), r.descripcion(), r.parametros().valores(), r.puntos(),
                r.activa(), r.version(), true, null, null);
    }

    static VersionRegla de(ReglaEntity e) {
        return new VersionRegla(e.getCodigo(), e.getNombre(), e.getDescripcion(), e.getParametros(), e.getPuntos(),
                e.isActiva(), e.getVersion(), e.isVigente(), e.getCreadaEn(), e.getCreadaPor());
    }
}
