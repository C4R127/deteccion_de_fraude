package com.fraude.scoring.motor;

import com.fraude.common.evaluacion.EvaluacionResultado;
import com.fraude.common.evaluacion.EvaluacionSolicitud;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint interno que llama transacciones de forma síncrona (no pasa por el gateway). */
@RestController
public class EvaluacionController {

    private final ServicioEvaluacion servicio;

    public EvaluacionController(ServicioEvaluacion servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/internal/v1/evaluaciones")
    public EvaluacionResultado evaluar(@Valid @RequestBody EvaluacionSolicitud solicitud) {
        return servicio.evaluar(solicitud);
    }
}
