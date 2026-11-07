package com.fraude.scoring.perfil;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Lo que las reglas saben del cliente y la tarjeta antes de la transacción (Valkey, §5.4).
 * Sin perfil, {@code n30d}, {@code promedio30d} y {@code desviacion30d} son nulos y las
 * colecciones están vacías.
 *
 * @param horas 24 contadores por hora de Lima, o vacía si no hay perfil
 */
public record PerfilContexto(
        Integer n30d,
        BigDecimal promedio30d,
        BigDecimal desviacion30d,
        Set<String> paises,
        Set<String> mccs,
        List<Integer> horas,
        long transaccionesUltimos10Min,
        UltimaUbicacion ultima,
        Set<String> dispositivos) {

    /** El perfil es confiable si tiene al menos {@code minimo} transacciones en 30 días (§7.2). */
    public boolean perfilSuficiente(int minimo) {
        return n30d != null && n30d >= minimo;
    }
}
