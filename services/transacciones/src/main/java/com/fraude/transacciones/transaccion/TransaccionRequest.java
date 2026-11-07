package com.fraude.transacciones.transaccion;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Cuerpo de {@code POST /api/v1/transacciones} con las validaciones de §11 (HTTP 422). */
public record TransaccionRequest(
        @NotNull UUID idExterno,
        @NotBlank String tarjetaToken,
        @NotBlank String comercioId,
        String dispositivoHuella,
        @NotNull @Positive @Digits(integer = 13, fraction = 2) BigDecimal monto,
        @NotNull @Pattern(regexp = "PEN", message = "solo se acepta PEN") String moneda,
        @NotNull @Pattern(regexp = "POS|ECOMMERCE|ATM", message = "debe ser POS, ECOMMERCE o ATM") String canal,
        @NotNull @Pattern(regexp = "[A-Z]{2}") String pais,
        @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6) BigDecimal latitud,
        @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6) BigDecimal longitud,
        @NotNull Instant fecha) {

    /** POS y ATM llevan coordenadas; ECOMMERCE no tiene ubicación física. */
    @JsonIgnore
    @AssertTrue(message = "POS y ATM requieren latitud y longitud; ECOMMERCE no debe tenerlas")
    public boolean isCoordenadas() {
        if (canal == null) {
            return true;
        }
        boolean conCoordenadas = latitud != null && longitud != null;
        boolean sinCoordenadas = latitud == null && longitud == null;
        return "ECOMMERCE".equals(canal) ? sinCoordenadas : conCoordenadas;
    }
}
