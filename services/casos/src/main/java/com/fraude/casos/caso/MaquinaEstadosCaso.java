package com.fraude.casos.caso;

import com.fraude.common.errores.ConflictoException;
import com.fraude.common.errores.SolicitudInvalidaException;
import com.fraude.common.eventos.Resolucion;
import org.springframework.security.access.AccessDeniedException;

/**
 * Transiciones permitidas del caso (§8.1). Primero se comprueba el estado (409), luego quién
 * actúa (403). SUPERVISOR y ADMIN tienen los mismos permisos (§10).
 *
 * <pre>
 * NUEVO            → ASIGNADO          tomar (analista, supervisor) o asignar (supervisor)
 * ASIGNADO         → ASIGNADO          asignar (supervisor reasigna)
 * ASIGNADO         → EN_INVESTIGACION  investigar (analista asignado)
 * EN_INVESTIGACION → CERRADO           cerrar (analista asignado o supervisor), con resolución
 * </pre>
 */
public final class MaquinaEstadosCaso {

    private MaquinaEstadosCaso() {
    }

    public static EstadoActual tomar(EstadoActual actual, Actor actor) {
        exigirEstado(actual, "tomar", EstadoCaso.NUEVO);
        if (!actor.puedeTrabajarCasos()) {
            throw new AccessDeniedException("Solo ANALISTA, SUPERVISOR o ADMIN pueden tomar casos");
        }
        return new EstadoActual(EstadoCaso.ASIGNADO, actor.usuario());
    }

    public static EstadoActual asignar(EstadoActual actual, Actor actor, String analistaId) {
        exigirEstado(actual, "asignar", EstadoCaso.NUEVO, EstadoCaso.ASIGNADO);
        if (!actor.esSupervisor()) {
            throw new AccessDeniedException("Solo SUPERVISOR o ADMIN pueden asignar casos");
        }
        if (analistaId == null || analistaId.isBlank()) {
            throw new SolicitudInvalidaException("Falta el analista a asignar");
        }
        return new EstadoActual(EstadoCaso.ASIGNADO, analistaId.strip());
    }

    public static EstadoActual investigar(EstadoActual actual, Actor actor) {
        exigirEstado(actual, "investigar", EstadoCaso.ASIGNADO);
        if (!actor.usuario().equals(actual.analistaId())) {
            throw new AccessDeniedException("Solo el analista asignado puede investigar el caso");
        }
        return new EstadoActual(EstadoCaso.EN_INVESTIGACION, actual.analistaId());
    }

    public static EstadoActual cerrar(EstadoActual actual, Actor actor, Resolucion resolucion) {
        exigirEstado(actual, "cerrar", EstadoCaso.EN_INVESTIGACION);
        if (!actor.usuario().equals(actual.analistaId()) && !actor.esSupervisor()) {
            throw new AccessDeniedException("Solo el analista asignado, SUPERVISOR o ADMIN pueden cerrar el caso");
        }
        if (resolucion == null) {
            throw new SolicitudInvalidaException("Cerrar un caso exige resolución (FRAUDE_CONFIRMADO o FALSO_POSITIVO)");
        }
        return new EstadoActual(EstadoCaso.CERRADO, actual.analistaId());
    }

    private static void exigirEstado(EstadoActual actual, String accion, EstadoCaso... permitidos) {
        for (EstadoCaso permitido : permitidos) {
            if (actual.estado() == permitido) {
                return;
            }
        }
        throw new ConflictoException("No se puede " + accion + " un caso en estado " + actual.estado());
    }
}
