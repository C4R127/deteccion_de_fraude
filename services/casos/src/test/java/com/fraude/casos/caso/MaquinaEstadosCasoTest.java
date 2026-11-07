package com.fraude.casos.caso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fraude.common.errores.ConflictoException;
import com.fraude.common.errores.SolicitudInvalidaException;
import com.fraude.common.eventos.Resolucion;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** §8.1: transiciones permitidas y quién puede hacerlas. */
class MaquinaEstadosCasoTest {

    private static final Actor ANALISTA1 = new Actor("analista1", Set.of("ANALISTA"));
    private static final Actor ANALISTA2 = new Actor("analista2", Set.of("ANALISTA"));
    private static final Actor SUPERVISOR = new Actor("supervisor", Set.of("SUPERVISOR"));
    private static final Actor ADMIN = new Actor("admin", Set.of("ADMIN"));
    private static final Actor AUDITOR = new Actor("auditor", Set.of("AUDITOR"));

    private static final EstadoActual NUEVO = new EstadoActual(EstadoCaso.NUEVO, null);
    private static final EstadoActual ASIGNADO_A1 = new EstadoActual(EstadoCaso.ASIGNADO, "analista1");
    private static final EstadoActual INVESTIGANDO_A1 = new EstadoActual(EstadoCaso.EN_INVESTIGACION, "analista1");
    private static final EstadoActual CERRADO = new EstadoActual(EstadoCaso.CERRADO, "analista1");

    @Test
    void un_analista_toma_un_caso_nuevo() {
        assertThat(MaquinaEstadosCaso.tomar(NUEVO, ANALISTA1))
                .isEqualTo(new EstadoActual(EstadoCaso.ASIGNADO, "analista1"));
    }

    @Test
    void un_supervisor_tambien_puede_tomar() {
        assertThat(MaquinaEstadosCaso.tomar(NUEVO, SUPERVISOR).analistaId()).isEqualTo("supervisor");
    }

    @Test
    void no_se_toma_un_caso_ya_asignado() {
        assertThatThrownBy(() -> MaquinaEstadosCaso.tomar(ASIGNADO_A1, ANALISTA2))
                .isInstanceOf(ConflictoException.class);
    }

    @Test
    void el_auditor_no_toma_casos() {
        assertThatThrownBy(() -> MaquinaEstadosCaso.tomar(NUEVO, AUDITOR))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void el_supervisor_asigna_y_reasigna() {
        assertThat(MaquinaEstadosCaso.asignar(NUEVO, SUPERVISOR, "analista2"))
                .isEqualTo(new EstadoActual(EstadoCaso.ASIGNADO, "analista2"));
        assertThat(MaquinaEstadosCaso.asignar(ASIGNADO_A1, ADMIN, "analista2"))
                .isEqualTo(new EstadoActual(EstadoCaso.ASIGNADO, "analista2"));
    }

    @Test
    void un_analista_no_asigna() {
        assertThatThrownBy(() -> MaquinaEstadosCaso.asignar(NUEVO, ANALISTA1, "analista2"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void no_se_reasigna_un_caso_en_investigacion() {
        assertThatThrownBy(() -> MaquinaEstadosCaso.asignar(INVESTIGANDO_A1, SUPERVISOR, "analista2"))
                .isInstanceOf(ConflictoException.class);
    }

    @Test
    void asignar_exige_analista() {
        assertThatThrownBy(() -> MaquinaEstadosCaso.asignar(NUEVO, SUPERVISOR, " "))
                .isInstanceOf(SolicitudInvalidaException.class);
    }

    @Test
    void solo_el_analista_asignado_investiga() {
        assertThat(MaquinaEstadosCaso.investigar(ASIGNADO_A1, ANALISTA1))
                .isEqualTo(new EstadoActual(EstadoCaso.EN_INVESTIGACION, "analista1"));
        assertThatThrownBy(() -> MaquinaEstadosCaso.investigar(ASIGNADO_A1, ANALISTA2))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> MaquinaEstadosCaso.investigar(ASIGNADO_A1, SUPERVISOR))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void no_se_investiga_un_caso_nuevo() {
        assertThatThrownBy(() -> MaquinaEstadosCaso.investigar(NUEVO, ANALISTA1))
                .isInstanceOf(ConflictoException.class);
    }

    @Test
    void cierran_el_analista_asignado_y_el_supervisor() {
        assertThat(MaquinaEstadosCaso.cerrar(INVESTIGANDO_A1, ANALISTA1, Resolucion.FRAUDE_CONFIRMADO))
                .isEqualTo(new EstadoActual(EstadoCaso.CERRADO, "analista1"));
        assertThat(MaquinaEstadosCaso.cerrar(INVESTIGANDO_A1, SUPERVISOR, Resolucion.FALSO_POSITIVO).estado())
                .isEqualTo(EstadoCaso.CERRADO);
        assertThat(MaquinaEstadosCaso.cerrar(INVESTIGANDO_A1, ADMIN, Resolucion.FALSO_POSITIVO).estado())
                .isEqualTo(EstadoCaso.CERRADO);
    }

    @Test
    void un_analista_no_asignado_no_cierra() {
        assertThatThrownBy(() -> MaquinaEstadosCaso.cerrar(INVESTIGANDO_A1, ANALISTA2, Resolucion.FALSO_POSITIVO))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void cerrar_exige_resolucion() {
        assertThatThrownBy(() -> MaquinaEstadosCaso.cerrar(INVESTIGANDO_A1, ANALISTA1, null))
                .isInstanceOf(SolicitudInvalidaException.class);
    }

    @Test
    void solo_se_cierra_un_caso_en_investigacion() {
        assertThatThrownBy(() -> MaquinaEstadosCaso.cerrar(ASIGNADO_A1, ANALISTA1, Resolucion.FALSO_POSITIVO))
                .isInstanceOf(ConflictoException.class);
    }

    @Test
    void un_caso_cerrado_no_admite_transiciones() {
        assertThatThrownBy(() -> MaquinaEstadosCaso.tomar(CERRADO, ANALISTA1)).isInstanceOf(ConflictoException.class);
        assertThatThrownBy(() -> MaquinaEstadosCaso.asignar(CERRADO, SUPERVISOR, "analista2"))
                .isInstanceOf(ConflictoException.class);
        assertThatThrownBy(() -> MaquinaEstadosCaso.investigar(CERRADO, ANALISTA1))
                .isInstanceOf(ConflictoException.class);
        assertThatThrownBy(() -> MaquinaEstadosCaso.cerrar(CERRADO, ANALISTA1, Resolucion.FALSO_POSITIVO))
                .isInstanceOf(ConflictoException.class);
    }
}
