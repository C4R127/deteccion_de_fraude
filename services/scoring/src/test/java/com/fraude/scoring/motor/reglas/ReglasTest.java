package com.fraude.scoring.motor.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraude.common.eventos.Canal;
import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.scoring.perfil.UltimaUbicacion;
import com.fraude.scoring.regla.ReglaVigente;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Una clase anidada por regla (§7.2). */
class ReglasTest {

    private static final BigDecimal CUSCO_LAT = new BigDecimal("-13.532000");
    private static final BigDecimal CUSCO_LON = new BigDecimal("-71.967500");
    private static final BigDecimal CALLAO_LAT = new BigDecimal("-12.056000");
    private static final BigDecimal CALLAO_LON = new BigDecimal("-77.118000");

    @Nested
    class R01MontoInusual {
        final ReglaVigente regla = Ctx.regla("R01", 40, Map.of("factor", 5, "minimoTransacciones", 10));
        final ReglaMontoInusual r01 = new ReglaMontoInusual();

        @Test
        void dispara_con_monto_mayor_a_cinco_veces_el_promedio_y_detalle_de_la_especificacion() {
            Ctx c = Ctx.tx();
            c.monto = "2450.00";

            Optional<ReglaDisparada> d = r01.evaluar(c.construir(), regla);

            assertThat(d).contains(new ReglaDisparada("R01", 40, "monto 2450.00 PEN, promedio 180.00 PEN"));
        }

        @Test
        void monto_igual_a_cinco_veces_el_promedio_no_dispara() {
            Ctx c = Ctx.tx();
            c.monto = "900.00";
            assertThat(r01.evaluar(c.construir(), regla)).isEmpty();
        }

        @Test
        void no_dispara_con_n30d_9() {
            Ctx c = Ctx.tx();
            c.monto = "2450.00";
            c.n30d = 9;
            assertThat(r01.evaluar(c.construir(), regla)).isEmpty();
        }

        @Test
        void no_dispara_sin_perfil() {
            Ctx c = Ctx.tx().sinPerfil();
            c.monto = "99999.00";
            assertThat(r01.evaluar(c.construir(), regla)).isEmpty();
        }

        @Test
        void usa_el_factor_configurado() {
            Ctx c = Ctx.tx();
            c.monto = "400.00";
            var factor2 = Ctx.regla("R01", 40, Map.of("factor", 2, "minimoTransacciones", 10));
            assertThat(r01.evaluar(c.construir(), factor2)).isPresent();
        }
    }

    @Nested
    class R02Velocidad {
        final ReglaVigente regla = Ctx.regla("R02", 30, Map.of("minimoPrevias", 5, "ventanaMinutos", 10));
        final ReglaVelocidad r02 = new ReglaVelocidad();

        @Test
        void cuatro_previas_no_dispara() {
            Ctx c = Ctx.tx();
            c.previas = 4;
            assertThat(r02.evaluar(c.construir(), regla)).isEmpty();
        }

        @Test
        void cinco_previas_dispara() {
            Ctx c = Ctx.tx();
            c.previas = 5;
            assertThat(r02.evaluar(c.construir(), regla))
                    .contains(new ReglaDisparada("R02", 30, "5 transacciones previas en 10 minutos"));
        }

        @Test
        void se_evalua_sin_perfil() {
            Ctx c = Ctx.tx().sinPerfil();
            c.previas = 7;
            assertThat(r02.evaluar(c.construir(), regla)).isPresent();
        }
    }

    @Nested
    class R03ViajeImposible {
        final ReglaVigente regla = Ctx.regla("R03", 50,
                Map.of("velocidadMaximaKmH", 800, "segundosMinimos", 60, "distanciaMinimaKm", 50));
        final ReglaViajeImposible r03 = new ReglaViajeImposible();

        private Ctx desde(BigDecimal lat, BigDecimal lon, Duration antes, Canal canalAnterior) {
            Ctx c = Ctx.tx();
            c.lat = CUSCO_LAT;
            c.lon = CUSCO_LON;
            c.ultima = new UltimaUbicacion(lat, lon, Ctx.FECHA.minus(antes), canalAnterior);
            return c;
        }

        @Test
        void lima_a_cusco_en_10_minutos_dispara() {
            Ctx c = desde(Ctx.LIMA_LAT, Ctx.LIMA_LON, Duration.ofMinutes(10), Canal.POS);

            Optional<ReglaDisparada> d = r03.evaluar(c.construir(), regla);

            assertThat(d).isPresent();
            assertThat(d.get().puntos()).isEqualTo(50);
            assertThat(d.get().detalle()).matches("\\d+ km en 10 min \\(\\d+ km/h\\)");
        }

        @Test
        void lima_a_callao_en_2_horas_no_dispara() {
            Ctx c = Ctx.tx();
            c.lat = CALLAO_LAT;
            c.lon = CALLAO_LON;
            c.ultima = new UltimaUbicacion(Ctx.LIMA_LAT, Ctx.LIMA_LON, Ctx.FECHA.minus(Duration.ofHours(2)), Canal.POS);
            assertThat(r03.evaluar(c.construir(), regla)).isEmpty();
        }

        @Test
        void tiempo_cero_con_100_km_dispara_sin_error() {
            Ctx c = Ctx.tx();
            c.lat = new BigDecimal("-12.946400"); // ~100 km al sur de Lima
            c.ultima = new UltimaUbicacion(Ctx.LIMA_LAT, Ctx.LIMA_LON, Ctx.FECHA, Canal.POS);

            Optional<ReglaDisparada> d = r03.evaluar(c.construir(), regla);

            assertThat(d).isPresent();
            assertThat(d.get().detalle()).matches("\\d+ km en 0 s");
        }

        @Test
        void tiempo_corto_con_distancia_menor_a_50_km_no_dispara() {
            Ctx c = Ctx.tx();
            c.lat = CALLAO_LAT;
            c.lon = CALLAO_LON;
            c.ultima = new UltimaUbicacion(Ctx.LIMA_LAT, Ctx.LIMA_LON, Ctx.FECHA.minusSeconds(20), Canal.POS);
            assertThat(r03.evaluar(c.construir(), regla)).isEmpty();
        }

        @Test
        void ecommerce_no_aplica() {
            Ctx c = desde(Ctx.LIMA_LAT, Ctx.LIMA_LON, Duration.ofMinutes(10), Canal.POS);
            c.canal = Canal.ECOMMERCE;
            c.lat = null;
            c.lon = null;
            assertThat(r03.evaluar(c.construir(), regla)).isEmpty();
        }

        @Test
        void ultima_ecommerce_no_aplica() {
            Ctx c = desde(null, null, Duration.ofMinutes(10), Canal.ECOMMERCE);
            assertThat(r03.evaluar(c.construir(), regla)).isEmpty();
        }

        @Test
        void sin_ultima_no_aplica() {
            Ctx c = Ctx.tx();
            c.lat = CUSCO_LAT;
            c.lon = CUSCO_LON;
            assertThat(r03.evaluar(c.construir(), regla)).isEmpty();
        }

        @Test
        void una_ultima_posterior_por_desorden_usa_el_tiempo_absoluto() {
            Ctx c = Ctx.tx();
            c.lat = CUSCO_LAT;
            c.lon = CUSCO_LON;
            c.ultima = new UltimaUbicacion(Ctx.LIMA_LAT, Ctx.LIMA_LON, Ctx.FECHA.plus(Duration.ofMinutes(10)), Canal.POS);
            assertThat(r03.evaluar(c.construir(), regla)).isPresent();
        }
    }

    @Nested
    class R04PaisNuevo {
        final ReglaVigente regla = Ctx.regla("R04", 20, Map.of("minimoTransacciones", 10));
        final ReglaPaisNuevo r04 = new ReglaPaisNuevo();

        @Test
        void pais_fuera_del_perfil_dispara() {
            Ctx c = Ctx.tx();
            c.pais = "AR";
            assertThat(r04.evaluar(c.construir(), regla))
                    .contains(new ReglaDisparada("R04", 20, "país AR no visto en 30 días"));
        }

        @Test
        void pais_conocido_no_dispara() {
            assertThat(r04.evaluar(Ctx.tx().construir(), regla)).isEmpty();
        }

        @Test
        void no_dispara_sin_perfil_suficiente() {
            Ctx c = Ctx.tx().sinPerfil();
            c.pais = "AR";
            assertThat(r04.evaluar(c.construir(), regla)).isEmpty();
            Ctx poco = Ctx.tx();
            poco.pais = "AR";
            poco.n30d = 9;
            assertThat(r04.evaluar(poco.construir(), regla)).isEmpty();
        }
    }

    @Nested
    class R05DispositivoNuevo {
        final ReglaVigente regla = Ctx.regla("R05", 25, Map.of());
        final ReglaDispositivoNuevo r05 = new ReglaDispositivoNuevo();

        @Test
        void huella_desconocida_dispara() {
            Ctx c = Ctx.tx();
            c.huella = "dev_nuevo";
            c.dispositivos = Set.of("dev_conocido");
            assertThat(r05.evaluar(c.construir(), regla))
                    .contains(new ReglaDisparada("R05", 25, "dispositivo dev_nuevo no conocido"));
        }

        @Test
        void huella_conocida_no_dispara() {
            Ctx c = Ctx.tx();
            c.huella = "dev_conocido";
            c.dispositivos = Set.of("dev_conocido");
            assertThat(r05.evaluar(c.construir(), regla)).isEmpty();
        }

        @Test
        void huella_nula_no_dispara() {
            assertThat(r05.evaluar(Ctx.tx().construir(), regla)).isEmpty();
        }

        @Test
        void se_evalua_sin_perfil() {
            Ctx c = Ctx.tx().sinPerfil();
            c.huella = "dev_nuevo";
            assertThat(r05.evaluar(c.construir(), regla)).isPresent();
        }
    }

    @Nested
    class R06RubroRiesgo {
        final ReglaVigente regla = Ctx.regla("R06", 15, Map.of("mccs", java.util.List.of("7995", "6051", "4829")));
        final ReglaRubroRiesgo r06 = new ReglaRubroRiesgo();

        @Test
        void mcc_de_riesgo_dispara() {
            Ctx c = Ctx.tx();
            c.mcc = "7995";
            assertThat(r06.evaluar(c.construir(), regla))
                    .contains(new ReglaDisparada("R06", 15, "MCC 7995 de riesgo"));
        }

        @Test
        void mcc_comun_no_dispara() {
            assertThat(r06.evaluar(Ctx.tx().construir(), regla)).isEmpty();
        }
    }

    @Nested
    class R07HorarioInusual {
        final ReglaVigente regla = Ctx.regla("R07", 10,
                Map.of("horaInicio", 2, "horaFin", 4, "zona", "America/Lima", "minimoTransacciones", 10));
        final ReglaHorarioInusual r07 = new ReglaHorarioInusual();

        private Ctx a(String instante) {
            Ctx c = Ctx.tx();
            c.fecha = Instant.parse(instante);
            c.horas.set(2, 0);
            c.horas.set(3, 0);
            c.horas.set(4, 0);
            return c;
        }

        @Test
        void tres_y_media_de_lima_dispara_si_no_hay_historial_a_esa_hora() {
            assertThat(r07.evaluar(a("2026-11-06T08:30:00Z").construir(), regla))
                    .contains(new ReglaDisparada("R07", 10, "03:30 en Lima sin historial entre 02:00 y 04:59"));
        }

        @Test
        void no_dispara_si_el_perfil_tiene_transacciones_en_esas_horas() {
            Ctx c = a("2026-11-06T08:30:00Z");
            c.horas.set(4, 1);
            assertThat(r07.evaluar(c.construir(), regla)).isEmpty();
        }

        @Test
        void limites_02_00_y_04_59_disparan_05_00_no() {
            assertThat(r07.evaluar(a("2026-11-06T07:00:00Z").construir(), regla)).isPresent();
            assertThat(r07.evaluar(a("2026-11-06T09:59:59Z").construir(), regla)).isPresent();
            assertThat(r07.evaluar(a("2026-11-06T10:00:00Z").construir(), regla)).isEmpty();
            assertThat(r07.evaluar(a("2026-11-06T06:59:59Z").construir(), regla)).isEmpty();
        }

        @Test
        void no_dispara_sin_perfil_suficiente() {
            Ctx c = a("2026-11-06T08:30:00Z");
            c.n30d = 9;
            assertThat(r07.evaluar(c.construir(), regla)).isEmpty();
            Ctx sin = Ctx.tx().sinPerfil();
            sin.fecha = Instant.parse("2026-11-06T08:30:00Z");
            assertThat(r07.evaluar(sin.construir(), regla)).isEmpty();
        }
    }

    @Nested
    class HaversineTest {
        @Test
        void distancia_lima_cusco_aproximada() {
            double km = Haversine.km(Ctx.LIMA_LAT, Ctx.LIMA_LON, CUSCO_LAT, CUSCO_LON);
            assertThat(km).isBetween(560.0, 600.0);
        }

        @Test
        void mismo_punto_es_cero() {
            assertThat(Haversine.km(Ctx.LIMA_LAT, Ctx.LIMA_LON, Ctx.LIMA_LAT, Ctx.LIMA_LON)).isZero();
        }
    }
}
