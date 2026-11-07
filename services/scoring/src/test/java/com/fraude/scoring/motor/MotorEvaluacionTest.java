package com.fraude.scoring.motor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fraude.common.evaluacion.EvaluacionSolicitud;
import com.fraude.common.eventos.Canal;
import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.scoring.bloqueo.Bloqueo;
import com.fraude.scoring.bloqueo.ListaBloqueoService;
import com.fraude.scoring.perfil.PerfilContexto;
import com.fraude.scoring.perfil.PerfilLector;
import com.fraude.scoring.regla.CatalogoReglas;
import com.fraude.scoring.regla.Parametros;
import com.fraude.scoring.regla.ReglaVigente;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MotorEvaluacionTest {

    private final CatalogoReglas catalogo = mock(CatalogoReglas.class);
    private final PerfilLector perfiles = mock(PerfilLector.class);
    private final ListaBloqueoService bloqueos = mock(ListaBloqueoService.class);
    private final List<String> evaluadas = new ArrayList<>();

    private final EvaluacionSolicitud solicitud = new EvaluacionSolicitud(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), "COM-00001", "5411", "dev_1", new BigDecimal("100.00"), "PEN",
            Canal.POS, "PE", new BigDecimal("-12.046400"), new BigDecimal("-77.042800"),
            Instant.parse("2026-11-06T14:30:00Z"));

    /** Regla falsa que siempre dispara con los puntos de su versión vigente. */
    private Regla siempre(String codigo) {
        return new Regla() {
            @Override
            public String codigo() {
                return codigo;
            }

            @Override
            public Optional<ReglaDisparada> evaluar(ContextoEvaluacion ctx, ReglaVigente regla) {
                evaluadas.add(codigo);
                return Optional.of(new ReglaDisparada(codigo, regla.puntos(), "detalle " + codigo));
            }
        };
    }

    private static ReglaVigente vigente(String codigo, int puntos, boolean activa, int version) {
        Map<String, Object> parametros = codigo.equals("R02")
                ? Map.of("minimoPrevias", 5, "ventanaMinutos", 15) : Map.of();
        return new ReglaVigente(codigo, codigo, codigo, new Parametros(parametros), puntos, activa, version);
    }

    private MotorEvaluacion motor(List<ReglaVigente> vigentes) {
        when(catalogo.vigentes()).thenReturn(vigentes);
        List<Regla> reglas = vigentes.stream().map(v -> siempre(v.codigo())).toList();
        return new MotorEvaluacion(catalogo, reglas, perfiles, bloqueos, new Umbrales(40, 70));
    }

    @BeforeEach
    void perfilVacio() {
        when(perfiles.leer(any(), any(), any(), any())).thenReturn(
                new PerfilContexto(null, null, null, Set.of(), Set.of(), List.of(), 0, null, Set.of()));
        when(bloqueos.buscar(any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void lista_de_bloqueo_rechaza_con_100_y_solo_b01_sin_evaluar_reglas() {
        var m = motor(List.of(vigente("R01", 40, true, 1)));
        when(bloqueos.buscar(eq(solicitud.tarjetaId()), eq("dev_1"), eq("COM-00001"), any()))
                .thenReturn(Optional.of(new Bloqueo("TARJETA", solicitud.tarjetaId().toString(), "fraude confirmado")));

        Calculo c = m.calcular(solicitud);

        assertThat(c.score()).isEqualTo(100);
        assertThat(c.decision()).isEqualTo(Decision.RECHAZAR);
        assertThat(c.reglasDisparadas()).extracting(ReglaDisparada::codigo).containsExactly("B01");
        assertThat(c.reglasDisparadas().get(0).puntos()).isEqualTo(100);
        assertThat(c.reglasDisparadas().get(0).detalle()).contains("TARJETA").contains("fraude confirmado");
        assertThat(evaluadas).isEmpty();
        verify(perfiles, never()).leer(any(), any(), any(), any());
    }

    @Test
    void suma_los_puntos_con_tope_100() {
        var m = motor(List.of(vigente("R01", 40, true, 1), vigente("R03", 50, true, 1), vigente("R05", 25, true, 1)));

        Calculo c = m.calcular(solicitud);

        assertThat(c.score()).isEqualTo(100);
        assertThat(c.reglasDisparadas()).hasSize(3);
        assertThat(c.decision()).isEqualTo(Decision.RECHAZAR);
    }

    @Test
    void limites_de_decision_39_40_69_70() {
        assertThat(motor(List.of(vigente("R01", 39, true, 1))).calcular(solicitud).decision())
                .isEqualTo(Decision.APROBAR);
        assertThat(motor(List.of(vigente("R01", 40, true, 1))).calcular(solicitud).decision())
                .isEqualTo(Decision.REVISAR);
        assertThat(motor(List.of(vigente("R01", 69, true, 1))).calcular(solicitud).decision())
                .isEqualTo(Decision.REVISAR);
        assertThat(motor(List.of(vigente("R01", 70, true, 1))).calcular(solicitud).decision())
                .isEqualTo(Decision.RECHAZAR);
    }

    @Test
    void sin_reglas_disparadas_aprueba_con_0() {
        var m = new MotorEvaluacion(catalogo, List.of(), perfiles, bloqueos, new Umbrales(40, 70));
        when(catalogo.vigentes()).thenReturn(List.of());

        Calculo c = m.calcular(solicitud);

        assertThat(c.score()).isZero();
        assertThat(c.decision()).isEqualTo(Decision.APROBAR);
        assertThat(c.reglasDisparadas()).isEmpty();
    }

    @Test
    void las_reglas_inactivas_no_se_evaluan() {
        var m = motor(List.of(vigente("R01", 40, false, 2), vigente("R06", 15, true, 1)));

        Calculo c = m.calcular(solicitud);

        assertThat(evaluadas).containsExactly("R06");
        assertThat(c.score()).isEqualTo(15);
        assertThat(c.versionesReglas()).containsExactly(Map.entry("R06", 1));
    }

    @Test
    void registra_la_version_de_cada_regla_evaluada() {
        var m = motor(List.of(vigente("R01", 0, true, 3), vigente("R06", 0, true, 1)));

        assertThat(m.calcular(solicitud).versionesReglas()).containsEntry("R01", 3).containsEntry("R06", 1);
    }

    @Test
    void lee_el_perfil_con_la_ventana_de_r02() {
        var m = motor(List.of(vigente("R02", 30, true, 1)));

        m.calcular(solicitud);

        verify(perfiles).leer(solicitud.clienteId(), solicitud.tarjetaId(), solicitud.fecha(), Duration.ofMinutes(15));
    }
}
