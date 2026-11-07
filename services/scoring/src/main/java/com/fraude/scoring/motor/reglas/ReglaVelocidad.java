package com.fraude.scoring.motor.reglas;

import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.scoring.motor.ContextoEvaluacion;
import com.fraude.scoring.motor.Regla;
import com.fraude.scoring.regla.ReglaVigente;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * R02: al menos minimoPrevias transacciones previas de la tarjeta en los ventanaMinutos
 * anteriores a la fecha (el motor lee el conteo de Valkey con esa ventana).
 */
@Component
public class ReglaVelocidad implements Regla {

    @Override
    public String codigo() {
        return "R02";
    }

    @Override
    public Optional<ReglaDisparada> evaluar(ContextoEvaluacion ctx, ReglaVigente regla) {
        long previas = ctx.perfil().transaccionesUltimos10Min();
        if (previas < regla.parametros().entero("minimoPrevias")) {
            return Optional.empty();
        }
        return Optional.of(new ReglaDisparada(codigo(), regla.puntos(), "%d transacciones previas en %d minutos"
                .formatted(previas, regla.parametros().entero("ventanaMinutos"))));
    }
}
