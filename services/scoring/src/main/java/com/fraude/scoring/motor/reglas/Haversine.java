package com.fraude.scoring.motor.reglas;

import java.math.BigDecimal;

/** Distancia de gran círculo entre dos coordenadas, en kilómetros. */
public final class Haversine {

    private static final double RADIO_TIERRA_KM = 6371.0088;

    private Haversine() {
    }

    public static double km(BigDecimal lat1, BigDecimal lon1, BigDecimal lat2, BigDecimal lon2) {
        double f1 = Math.toRadians(lat1.doubleValue());
        double f2 = Math.toRadians(lat2.doubleValue());
        double df = f2 - f1;
        double dl = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());
        double a = Math.sin(df / 2) * Math.sin(df / 2)
                + Math.cos(f1) * Math.cos(f2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * RADIO_TIERRA_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
