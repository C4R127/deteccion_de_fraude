package com.fraude.scoring.motor.reglas;

import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.scoring.motor.ContextoEvaluacion;
import com.fraude.scoring.motor.Regla;
import com.fraude.scoring.regla.ReglaVigente;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** R06: MCC en la lista configurable de rubros de riesgo. */
@Component
public class ReglaRubroRiesgo implements Regla {

    @Override
    public String codigo() {
        return "R06";
    }

    @Override
    public Optional<ReglaDisparada> evaluar(ContextoEvaluacion ctx, ReglaVigente regla) {
        String mcc = ctx.transaccion().mcc();
        if (mcc == null || !regla.parametros().textos("mccs").contains(mcc)) {
            return Optional.empty();
        }
        return Optional.of(new ReglaDisparada(codigo(), regla.puntos(), "MCC %s de riesgo".formatted(mcc)));
    }
}
