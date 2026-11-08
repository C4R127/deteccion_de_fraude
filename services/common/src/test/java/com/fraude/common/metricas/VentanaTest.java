package com.fraude.common.metricas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fraude.common.errores.SolicitudInvalidaException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Ventana {@code [desde, hasta)} de los endpoints de métricas (ADR 0017). */
class VentanaTest {

    private static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");

    @Test
    void usa_las_fechas_indicadas() {
        Ventana v = Ventana.resolver(T0, T0.plusSeconds(60), Duration.ofHours(1));

        assertThat(v.desde()).isEqualTo(T0);
        assertThat(v.hasta()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void sin_desde_cuenta_la_duracion_por_defecto_hacia_atras() {
        Ventana v = Ventana.resolver(null, T0, Duration.ofHours(24));

        assertThat(v.desde()).isEqualTo(T0.minus(Duration.ofHours(24)));
    }

    @Test
    void sin_hasta_termina_ahora() {
        Instant antes = Instant.now();
        Ventana v = Ventana.resolver(null, null, Duration.ofDays(7));

        assertThat(v.hasta()).isBetween(antes, Instant.now());
        assertThat(Duration.between(v.desde(), v.hasta())).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void desde_debe_ser_anterior_a_hasta() {
        assertThatThrownBy(() -> Ventana.resolver(T0, T0, Duration.ofHours(1)))
                .isInstanceOf(SolicitudInvalidaException.class)
                .hasMessage("desde debe ser anterior a hasta");
    }
}
