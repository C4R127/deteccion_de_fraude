package com.fraude.scoring.motor.reglas;

import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.scoring.motor.ContextoEvaluacion;
import com.fraude.scoring.motor.Regla;
import com.fraude.scoring.perfil.PerfilContexto;
import com.fraude.scoring.regla.ReglaVigente;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * R07: hora local (zona configurada, America/Lima) entre horaInicio:00 y horaFin:59 y el
 * perfil no tiene transacciones en ninguna de esas horas; solo con perfil suficiente.
 */
@Component
public class ReglaHorarioInusual implements Regla {

    @Override
    public String codigo() {
        return "R07";
    }

    @Override
    public Optional<ReglaDisparada> evaluar(ContextoEvaluacion ctx, ReglaVigente regla) {
        PerfilContexto perfil = ctx.perfil();
        if (!perfil.perfilSuficiente(regla.parametros().entero("minimoTransacciones")) || perfil.horas().size() != 24) {
            return Optional.empty();
        }
        int inicio = regla.parametros().entero("horaInicio");
        int fin = regla.parametros().entero("horaFin");
        ZonedDateTime local = ctx.transaccion().fecha().atZone(ZoneId.of(regla.parametros().texto("zona")));
        if (local.getHour() < inicio || local.getHour() > fin) {
            return Optional.empty();
        }
        int enEsasHoras = perfil.horas().subList(inicio, fin + 1).stream().mapToInt(Integer::intValue).sum();
        if (enEsasHoras > 0) {
            return Optional.empty();
        }
        return Optional.of(new ReglaDisparada(codigo(), regla.puntos(),
                "%02d:%02d en Lima sin historial entre %02d:00 y %02d:59".formatted(
                        local.getHour(), local.getMinute(), inicio, fin)));
    }
}
