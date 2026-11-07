package com.fraude.scoring.motor.reglas;

import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.scoring.motor.ContextoEvaluacion;
import com.fraude.scoring.motor.Regla;
import com.fraude.scoring.regla.ReglaVigente;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** R04: país que no está en el perfil de 30 días; solo con perfil suficiente. */
@Component
public class ReglaPaisNuevo implements Regla {

    @Override
    public String codigo() {
        return "R04";
    }

    @Override
    public Optional<ReglaDisparada> evaluar(ContextoEvaluacion ctx, ReglaVigente regla) {
        String pais = ctx.transaccion().pais();
        if (!ctx.perfil().perfilSuficiente(regla.parametros().entero("minimoTransacciones"))
                || ctx.perfil().paises().contains(pais)) {
            return Optional.empty();
        }
        return Optional.of(new ReglaDisparada(codigo(), regla.puntos(), "país %s no visto en 30 días".formatted(pais)));
    }
}
