package com.fraude.scoring.motor;

import com.fraude.common.evaluacion.EvaluacionSolicitud;
import com.fraude.scoring.perfil.PerfilContexto;

/** Transacción a evaluar y lo que Valkey sabe del cliente y la tarjeta antes de ella. */
public record ContextoEvaluacion(EvaluacionSolicitud transaccion, PerfilContexto perfil) {
}
