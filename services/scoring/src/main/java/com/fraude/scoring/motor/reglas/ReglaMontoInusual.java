package com.fraude.scoring.motor.reglas;

import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.scoring.motor.ContextoEvaluacion;
import com.fraude.scoring.motor.Regla;
import com.fraude.scoring.perfil.PerfilContexto;
import com.fraude.scoring.regla.ReglaVigente;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** R01: monto mayor que factor × promedio30d; solo con perfil suficiente. */
@Component
public class ReglaMontoInusual implements Regla {

    @Override
    public String codigo() {
        return "R01";
    }

    @Override
    public Optional<ReglaDisparada> evaluar(ContextoEvaluacion ctx, ReglaVigente regla) {
        PerfilContexto perfil = ctx.perfil();
        if (!perfil.perfilSuficiente(regla.parametros().entero("minimoTransacciones"))
                || perfil.promedio30d() == null) {
            return Optional.empty();
        }
        BigDecimal monto = ctx.transaccion().monto();
        BigDecimal limite = regla.parametros().decimal("factor").multiply(perfil.promedio30d());
        if (monto.compareTo(limite) <= 0) {
            return Optional.empty();
        }
        return Optional.of(new ReglaDisparada(codigo(), regla.puntos(), "monto %s PEN, promedio %s PEN".formatted(
                monto.setScale(2, RoundingMode.HALF_EVEN).toPlainString(),
                perfil.promedio30d().setScale(2, RoundingMode.HALF_EVEN).toPlainString())));
    }
}
