package com.fraude.scoring.motor.reglas;

import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.scoring.motor.ContextoEvaluacion;
import com.fraude.scoring.motor.Regla;
import com.fraude.scoring.regla.ReglaVigente;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** R05: huella de dispositivo no nula que no está entre los dispositivos conocidos del cliente. */
@Component
public class ReglaDispositivoNuevo implements Regla {

    @Override
    public String codigo() {
        return "R05";
    }

    @Override
    public Optional<ReglaDisparada> evaluar(ContextoEvaluacion ctx, ReglaVigente regla) {
        String huella = ctx.transaccion().dispositivoHuella();
        if (huella == null || huella.isBlank() || ctx.perfil().dispositivos().contains(huella)) {
            return Optional.empty();
        }
        return Optional.of(new ReglaDisparada(codigo(), regla.puntos(), "dispositivo %s no conocido".formatted(huella)));
    }
}
