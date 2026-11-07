package com.fraude.perfiles.perfil;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.SortedSet;

/**
 * Contenido de {@code perfil:{clienteId}} (§5.4, ADR 0009). Todos los campos salen de la
 * misma muestra: APROBAR o REVISAR en los 30 días previos a {@code actualizado}.
 *
 * @param horas 24 contadores por hora de Lima (índice 0 = 00:00–00:59)
 */
public record Perfil(
        int n30d,
        BigDecimal promedio30d,
        BigDecimal desviacion30d,
        SortedSet<String> paises,
        SortedSet<String> mccs,
        List<Integer> horas,
        Instant actualizado) {
}
