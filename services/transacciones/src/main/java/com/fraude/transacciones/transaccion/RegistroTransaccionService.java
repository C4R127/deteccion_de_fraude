package com.fraude.transacciones.transaccion;

import com.fraude.common.errores.RecursoNoEncontradoException;
import com.fraude.common.evaluacion.EvaluacionResultado;
import com.fraude.common.evaluacion.EvaluacionSolicitud;
import com.fraude.common.eventos.Canal;
import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.TransaccionCreadaEvento;
import com.fraude.transacciones.maestros.Comercio;
import com.fraude.transacciones.maestros.ComercioRepository;
import com.fraude.transacciones.maestros.EstadoTarjeta;
import com.fraude.transacciones.maestros.Tarjeta;
import com.fraude.transacciones.maestros.TarjetaRepository;
import com.fraude.transacciones.scoring.ScoringClient;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registro de una transacción (§3 pasos 2 a 6): idempotencia por {@code idExterno},
 * guardado pendiente, decisión del scoring con respaldo y actualización, todo en una
 * transacción de base de datos.
 */
@Service
public class RegistroTransaccionService {

    private final TarjetaRepository tarjetas;
    private final ComercioRepository comercios;
    private final TransaccionStore store;
    private final ScoringClient scoring;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher eventos;

    public RegistroTransaccionService(TarjetaRepository tarjetas, ComercioRepository comercios,
            TransaccionStore store, ScoringClient scoring, TransactionTemplate tx,
            ApplicationEventPublisher eventos) {
        this.tarjetas = tarjetas;
        this.comercios = comercios;
        this.store = store;
        this.scoring = scoring;
        this.tx = tx;
        this.eventos = eventos;
    }

    /** Resultado del registro; {@code nueva = false} si el idExterno ya existía. */
    public record Registro(TransaccionResponse respuesta, boolean nueva) {
    }

    public Registro registrar(TransaccionRequest req) {
        Optional<TransaccionResponse> existente = store.buscarRespuesta(req.idExterno());
        if (existente.isPresent()) {
            return new Registro(existente.get(), false);
        }
        Tarjeta tarjeta = tarjetas.findByToken(req.tarjetaToken())
                .orElseThrow(() -> new RecursoNoEncontradoException("La tarjeta indicada no existe"));
        Comercio comercio = comercios.findById(req.comercioId())
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "El comercio " + req.comercioId() + " no existe"));
        TransaccionPendiente pendiente = new TransaccionPendiente(UUID.randomUUID(), req.idExterno(),
                tarjeta.getId(), tarjeta.getClienteId(), comercio.getId(), comercio.getMcc(),
                req.dispositivoHuella(), req.monto(), req.moneda(), Canal.valueOf(req.canal()), req.pais(),
                req.latitud(), req.longitud(), req.fecha());

        return tx.execute(estado -> {
            if (!store.insertarPendiente(pendiente)) {
                // Otra solicitud con el mismo idExterno ganó la carrera y ya hizo commit.
                return new Registro(store.buscarRespuesta(req.idExterno()).orElseThrow(), false);
            }
            TransaccionResponse respuesta = decidir(pendiente, tarjeta);
            store.registrarDecision(pendiente.id(), respuesta.decision(), respuesta.score(), respuesta.respaldo());
            // Se publica en Kafka solo si esta transacción de base de datos hace commit (§3 paso 7).
            eventos.publishEvent(evento(pendiente, respuesta));
            return new Registro(respuesta, true);
        });
    }

    private static TransaccionCreadaEvento evento(TransaccionPendiente t, TransaccionResponse r) {
        return new TransaccionCreadaEvento(TransaccionCreadaEvento.VERSION_ACTUAL, t.id(), t.idExterno(),
                t.tarjetaId(), t.clienteId(), t.comercioId(), t.mcc(), t.dispositivoHuella(), t.monto(),
                t.moneda(), t.canal(), t.pais(), t.latitud(), t.longitud(), t.fecha(), r.decision(), r.score(),
                r.respaldo());
    }

    private TransaccionResponse decidir(TransaccionPendiente t, Tarjeta tarjeta) {
        if (tarjeta.getEstado() == EstadoTarjeta.BLOQUEADA) {
            return new TransaccionResponse(t.id(), Decision.RECHAZAR, null, false);
        }
        Optional<EvaluacionResultado> resultado = scoring.evaluar(new EvaluacionSolicitud(t.id(), t.idExterno(),
                t.tarjetaId(), t.clienteId(), t.comercioId(), t.mcc(), t.dispositivoHuella(), t.monto(),
                t.moneda(), t.canal(), t.pais(), t.latitud(), t.longitud(), t.fecha()));
        return resultado
                .map(r -> new TransaccionResponse(t.id(), r.decision(), r.score(), false))
                .orElseGet(() -> new TransaccionResponse(t.id(), Decision.APROBAR, null, true));
    }
}
