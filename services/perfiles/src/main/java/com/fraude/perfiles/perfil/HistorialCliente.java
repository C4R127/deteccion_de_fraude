package com.fraude.perfiles.perfil;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Estado por cliente en Kafka Streams: transacciones APROBAR o REVISAR dentro de la ventana
 * y la mayor fecha de evento vista. Es lo mínimo para recalcular el perfil en cada evento.
 */
public record HistorialCliente(Instant ultimaFecha, List<Movimiento> movimientos) {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    public record Movimiento(UUID transaccionId, Instant fecha, BigDecimal monto, String pais, String mcc) {
    }

    public static HistorialCliente vacio() {
        return new HistorialCliente(null, List.of());
    }

    /**
     * Incorpora un evento: actualiza la última fecha (nunca hacia atrás), agrega el movimiento
     * si cuenta para el perfil y no estaba ya (reprocesamiento), y descarta lo que quedó fuera
     * de la ventana {@code (ultimaFecha − ventana, ultimaFecha]}.
     */
    public HistorialCliente con(Instant fecha, Movimiento movimiento, Duration ventana) {
        Instant ultima = ultimaFecha == null || fecha.isAfter(ultimaFecha) ? fecha : ultimaFecha;
        Instant limite = ultima.minus(ventana);
        List<Movimiento> nuevos = new ArrayList<>();
        boolean repetido = false;
        for (Movimiento m : movimientos) {
            if (m.fecha().isAfter(limite)) {
                nuevos.add(m);
            }
            repetido |= movimiento != null && m.transaccionId().equals(movimiento.transaccionId());
        }
        if (movimiento != null && !repetido && movimiento.fecha().isAfter(limite)) {
            nuevos.add(movimiento);
        }
        return new HistorialCliente(ultima, List.copyOf(nuevos));
    }

    public Perfil perfil() {
        int n = movimientos.size();
        BigDecimal promedio = BigDecimal.ZERO.setScale(2);
        BigDecimal desviacion = BigDecimal.ZERO.setScale(2);
        if (n > 0) {
            BigDecimal suma = movimientos.stream().map(Movimiento::monto).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal media = suma.divide(BigDecimal.valueOf(n), MathContext.DECIMAL64);
            BigDecimal varianza = movimientos.stream()
                    .map(m -> m.monto().subtract(media).pow(2))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(n), MathContext.DECIMAL64);
            promedio = media.setScale(2, RoundingMode.HALF_EVEN);
            desviacion = varianza.sqrt(MathContext.DECIMAL64).setScale(2, RoundingMode.HALF_EVEN);
        }
        Integer[] horas = new Integer[24];
        Arrays.fill(horas, 0);
        TreeSet<String> paises = new TreeSet<>();
        TreeSet<String> mccs = new TreeSet<>();
        for (Movimiento m : movimientos) {
            horas[m.fecha().atZone(LIMA).getHour()]++;
            paises.add(m.pais());
            mccs.add(m.mcc());
        }
        return new Perfil(n, promedio, desviacion, paises, mccs, List.of(horas), ultimaFecha);
    }
}
