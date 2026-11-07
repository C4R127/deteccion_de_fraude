package com.fraude.scoring.perfil;

import com.fraude.common.eventos.Canal;
import java.math.BigDecimal;
import java.time.Instant;

/** {@code ultima:{tarjetaId}}; latitud y longitud son nulas si fue ECOMMERCE. */
public record UltimaUbicacion(BigDecimal latitud, BigDecimal longitud, Instant fecha, Canal canal) {

    public boolean tieneCoordenadas() {
        return latitud != null && longitud != null;
    }
}
