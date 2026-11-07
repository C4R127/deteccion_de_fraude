package com.fraude.common.evaluacion;

import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.ReglaDisparada;
import java.util.List;

/** Respuesta de {@code POST /internal/v1/evaluaciones} (ADR 0006). */
public record EvaluacionResultado(int score, Decision decision, List<ReglaDisparada> reglasDisparadas) {

    public EvaluacionResultado {
        reglasDisparadas = reglasDisparadas == null ? List.of() : List.copyOf(reglasDisparadas);
    }
}
