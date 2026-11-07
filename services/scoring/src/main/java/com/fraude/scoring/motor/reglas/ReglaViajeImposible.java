package com.fraude.scoring.motor.reglas;

import com.fraude.common.evaluacion.EvaluacionSolicitud;
import com.fraude.common.eventos.Canal;
import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.scoring.motor.ContextoEvaluacion;
import com.fraude.scoring.motor.Regla;
import com.fraude.scoring.perfil.UltimaUbicacion;
import com.fraude.scoring.regla.ReglaVigente;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * R03: velocidad implícita desde la última transacción mayor que velocidadMaximaKmH
 * (haversine). Con menos de segundosMinimos se cumple si la distancia supera
 * distanciaMinimaKm (evita dividir por cero). No aplica si la actual o la última son
 * ECOMMERCE o no tienen coordenadas.
 */
@Component
public class ReglaViajeImposible implements Regla {

    @Override
    public String codigo() {
        return "R03";
    }

    @Override
    public Optional<ReglaDisparada> evaluar(ContextoEvaluacion ctx, ReglaVigente regla) {
        EvaluacionSolicitud tx = ctx.transaccion();
        UltimaUbicacion ultima = ctx.perfil().ultima();
        if (tx.canal() == Canal.ECOMMERCE || tx.latitud() == null || tx.longitud() == null
                || ultima == null || ultima.canal() == Canal.ECOMMERCE || !ultima.tieneCoordenadas()) {
            return Optional.empty();
        }
        double km = Haversine.km(ultima.latitud(), ultima.longitud(), tx.latitud(), tx.longitud());
        // Valor absoluto: la última puede ser posterior si los eventos llegaron en desorden.
        long segundos = Duration.between(ultima.fecha(), tx.fecha()).abs().toSeconds();

        if (segundos < regla.parametros().entero("segundosMinimos")) {
            if (km <= regla.parametros().decimal("distanciaMinimaKm").doubleValue()) {
                return Optional.empty();
            }
            return Optional.of(new ReglaDisparada(codigo(), regla.puntos(),
                    "%d km en %d s".formatted(Math.round(km), segundos)));
        }
        double kmH = km / (segundos / 3600.0);
        if (kmH <= regla.parametros().decimal("velocidadMaximaKmH").doubleValue()) {
            return Optional.empty();
        }
        return Optional.of(new ReglaDisparada(codigo(), regla.puntos(),
                "%d km en %d min (%d km/h)".formatted(Math.round(km), segundos / 60, Math.round(kmH))));
    }
}
