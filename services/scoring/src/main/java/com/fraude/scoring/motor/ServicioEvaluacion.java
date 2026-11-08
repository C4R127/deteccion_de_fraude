package com.fraude.scoring.motor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.common.evaluacion.EvaluacionResultado;
import com.fraude.common.evaluacion.EvaluacionSolicitud;
import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.DecisionSospechosaEvento;
import com.fraude.common.eventos.Topicos;
import com.fraude.common.json.JsonFraude;
import com.fraude.common.outbox.OutboxStore;
import com.fraude.scoring.ScoringProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Evaluación en línea (§7, Tarea 4.4): idempotente por {@code transaccionId}, guarda la
 * evaluación y, si es REVISAR o RECHAZAR y no es tardía (ADR 0012), deja
 * {@link DecisionSospechosaEvento} en el outbox en la misma transacción (ADR 0010).
 */
@Service
public class ServicioEvaluacion {

    private final MotorEvaluacion motor;
    private final EvaluacionStore store;
    private final OutboxStore outbox;
    private final TransactionTemplate tx;
    private final long limiteNanos;
    private final Timer latencia;
    private final Clock reloj = Clock.systemUTC();
    private final ObjectMapper json = JsonFraude.objectMapper();

    public ServicioEvaluacion(MotorEvaluacion motor, EvaluacionStore store, OutboxStore outbox, TransactionTemplate tx,
            ScoringProperties props, MeterRegistry metricas) {
        this.motor = motor;
        this.store = store;
        this.outbox = outbox;
        this.tx = tx;
        this.limiteNanos = props.tiempoLimite().toNanos();
        this.latencia = Timer.builder("scoring.evaluacion.latencia")
                .description("Latencia de la evaluación de una transacción (§7.4)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(metricas);
    }

    public EvaluacionResultado evaluar(EvaluacionSolicitud s) {
        // Desde el inicio: la consulta de idempotencia y la espera de conexiones también cuentan
        // para el tiempo límite que aplica transacciones (ADR 0012).
        long inicio = System.nanoTime();
        Optional<EvaluacionResultado> guardada = store.buscar(s.transaccionId());
        if (guardada.isPresent()) {
            return guardada.get();
        }
        Calculo calculo = motor.calcular(s);

        Boolean nueva = tx.execute(estado -> {
            // Medido ya dentro de la transacción, con la conexión tomada: incluye la espera por el pool.
            // Quedan fuera solo el INSERT y el commit, de pocos milisegundos.
            long nanos = System.nanoTime() - inicio;
            boolean tardia = nanos > limiteNanos;
            boolean insertada = store.insertar(s, calculo, TimeUnit.NANOSECONDS.toMillis(nanos), tardia, reloj.instant());
            if (insertada && calculo.decision() != Decision.APROBAR && !tardia) {
                outbox.guardar(Topicos.DECISIONES_SOSPECHOSAS, s.tarjetaId().toString(), evento(s, calculo));
            }
            return insertada;
        });
        latencia.record(System.nanoTime() - inicio, TimeUnit.NANOSECONDS);
        if (!Boolean.TRUE.equals(nueva)) {
            // Otra solicitud con el mismo transaccionId ganó la carrera: se devuelve la guardada.
            return store.buscar(s.transaccionId()).orElseThrow();
        }
        return new EvaluacionResultado(calculo.score(), calculo.decision(), calculo.reglasDisparadas());
    }

    private String evento(EvaluacionSolicitud s, Calculo c) {
        var evento = new DecisionSospechosaEvento(DecisionSospechosaEvento.VERSION_ACTUAL, s.transaccionId(),
                s.tarjetaId(), s.clienteId(), s.dispositivoHuella(), s.monto(), c.score(), c.decision(),
                c.reglasDisparadas(), s.fecha());
        try {
            return json.writeValueAsString(evento);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar la decisión de " + s.transaccionId(), e);
        }
    }
}
