package com.fraude.scoring.motor;

import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.scoring.regla.ReglaVigente;
import java.util.Optional;

/** Regla del motor (§7.2); los parámetros y puntos salen de la versión vigente. */
public interface Regla {

    String codigo();

    Optional<ReglaDisparada> evaluar(ContextoEvaluacion ctx, ReglaVigente regla);
}
