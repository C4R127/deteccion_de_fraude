package com.fraude.scoring.motor;

import com.fraude.common.evaluacion.EvaluacionSolicitud;
import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.scoring.bloqueo.Bloqueo;
import com.fraude.scoring.bloqueo.ListaBloqueoService;
import com.fraude.scoring.perfil.PerfilContexto;
import com.fraude.scoring.perfil.PerfilLector;
import com.fraude.scoring.regla.CatalogoReglas;
import com.fraude.scoring.regla.ReglaVigente;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Motor de reglas (§7.1): listas de bloqueo, lectura del perfil en una ida y vuelta,
 * evaluación de las reglas activas vigentes con tope 100 y decisión por umbrales.
 */
@Component
public class MotorEvaluacion {

    static final int SCORE_MAXIMO = 100;
    static final Duration VENTANA_POR_DEFECTO = Duration.ofMinutes(10);

    private final CatalogoReglas catalogo;
    private final Map<String, Regla> reglas;
    private final PerfilLector perfiles;
    private final ListaBloqueoService bloqueos;
    private final Umbrales umbrales;

    public MotorEvaluacion(CatalogoReglas catalogo, List<Regla> reglas, PerfilLector perfiles,
            ListaBloqueoService bloqueos, Umbrales umbrales) {
        this.catalogo = catalogo;
        this.reglas = reglas.stream().collect(Collectors.toMap(Regla::codigo, Function.identity()));
        this.perfiles = perfiles;
        this.bloqueos = bloqueos;
        this.umbrales = umbrales;
    }

    public Calculo calcular(EvaluacionSolicitud s) {
        Optional<Bloqueo> bloqueo = bloqueos.buscar(s.tarjetaId(), s.dispositivoHuella(), s.comercioId(),
                Instant.now());
        if (bloqueo.isPresent()) {
            Bloqueo b = bloqueo.get();
            return new Calculo(SCORE_MAXIMO, Decision.RECHAZAR, List.of(new ReglaDisparada("B01", SCORE_MAXIMO,
                    "%s en lista de bloqueo: %s".formatted(b.tipo(), b.motivo()))), Map.of());
        }

        List<ReglaVigente> activas = catalogo.vigentes().stream().filter(ReglaVigente::activa).toList();
        PerfilContexto perfil = perfiles.leer(s.clienteId(), s.tarjetaId(), s.fecha(), ventanaVelocidad(activas));
        ContextoEvaluacion ctx = new ContextoEvaluacion(s, perfil);

        List<ReglaDisparada> disparadas = new ArrayList<>();
        Map<String, Integer> versiones = new LinkedHashMap<>();
        for (ReglaVigente vigente : activas) {
            Regla regla = reglas.get(vigente.codigo());
            if (regla == null) {
                continue;
            }
            versiones.put(vigente.codigo(), vigente.version());
            regla.evaluar(ctx, vigente).ifPresent(disparadas::add);
        }
        int score = Math.min(SCORE_MAXIMO, disparadas.stream().mapToInt(ReglaDisparada::puntos).sum());
        return new Calculo(score, umbrales.decidir(score), List.copyOf(disparadas), versiones);
    }

    /** La ventana de velocidad sale de R02 (ventanaMinutos); si no está activa, 10 minutos. */
    private static Duration ventanaVelocidad(List<ReglaVigente> activas) {
        return activas.stream().filter(r -> r.codigo().equals("R02")).findFirst()
                .map(r -> Duration.ofMinutes(r.parametros().entero("ventanaMinutos")))
                .orElse(VENTANA_POR_DEFECTO);
    }
}
