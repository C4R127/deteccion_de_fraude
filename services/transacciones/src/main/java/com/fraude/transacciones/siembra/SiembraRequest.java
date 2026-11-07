package com.fraude.transacciones.siembra;

import com.fraude.transacciones.maestros.EstadoTarjeta;
import com.fraude.transacciones.maestros.Segmento;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Cuerpo de {@code POST /internal/v1/siembra}: datos maestros del generador. */
public record SiembraRequest(
        @NotNull List<@Valid @NotNull ClienteSiembra> clientes,
        @NotNull List<@Valid @NotNull TarjetaSiembra> tarjetas,
        @NotNull List<@Valid @NotNull ComercioSiembra> comercios,
        @NotNull List<@Valid @NotNull DispositivoSiembra> dispositivos) {

    public record ClienteSiembra(
            @NotNull UUID id,
            @NotBlank String nombre,
            @NotNull Segmento segmento,
            @NotBlank String ciudadResidencia,
            @NotNull Instant fechaAlta) {
    }

    public record TarjetaSiembra(
            @NotNull UUID id,
            @NotNull UUID clienteId,
            // Solo tokens: nunca un número de tarjeta.
            @NotNull @Pattern(regexp = "tok_[A-Za-z0-9]{4,64}") String token,
            @NotNull @Pattern(regexp = "\\d{4}") String ultimos4,
            @NotNull EstadoTarjeta estado,
            @NotNull Instant fechaEmision) {
    }

    public record ComercioSiembra(
            @NotBlank String id,
            @NotBlank String nombre,
            @NotNull @Pattern(regexp = "\\d{4}") String mcc,
            @NotNull @Pattern(regexp = "[A-Z]{2}") String pais,
            @NotBlank String ciudad,
            @Digits(integer = 3, fraction = 6) BigDecimal latitud,
            @Digits(integer = 3, fraction = 6) BigDecimal longitud,
            boolean online) {
    }

    public record DispositivoSiembra(
            @NotBlank String huella,
            @NotNull UUID clienteId) {
    }
}
