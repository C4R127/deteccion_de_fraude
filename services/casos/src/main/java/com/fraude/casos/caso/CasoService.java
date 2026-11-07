package com.fraude.casos.caso;

import com.fraude.casos.caso.CasoStore.Nota;
import com.fraude.common.errores.RecursoNoEncontradoException;
import com.fraude.common.eventos.DecisionSospechosaEvento;
import com.fraude.common.eventos.Resolucion;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ciclo de vida de los casos (§8). Cada transición bloquea la fila, valida con
 * {@link MaquinaEstadosCaso} e inserta su historial en la misma transacción; el cierre
 * guarda además {@code CasoCerradoEvento} en el outbox.
 */
@Service
public class CasoService {

    static final String USUARIO_SISTEMA = "sistema";

    private final CasoStore store;
    private final PublicadorCasos publicador;

    public CasoService(CasoStore store, PublicadorCasos publicador) {
        this.store = store;
        this.publicador = publicador;
    }

    /** Crea el caso de una decisión sospechosa; si ya existía para esa transacción, no hace nada. */
    @Transactional
    public Optional<Caso> crear(DecisionSospechosaEvento evento) {
        Instant ahora = Instant.now();
        Optional<UUID> id = store.insertar(evento, ahora);
        id.ifPresent(casoId -> store.registrarHistorial(casoId, null, EstadoCaso.NUEVO, USUARIO_SISTEMA, ahora, null));
        return id.flatMap(store::buscar);
    }

    @Transactional
    public Caso tomar(UUID id, Actor actor) {
        return transicionar(id, actor, actual -> MaquinaEstadosCaso.tomar(actual, actor), null, null);
    }

    @Transactional
    public Caso asignar(UUID id, Actor actor, String analistaId) {
        return transicionar(id, actor, actual -> MaquinaEstadosCaso.asignar(actual, actor, analistaId), null, null);
    }

    @Transactional
    public Caso investigar(UUID id, Actor actor) {
        return transicionar(id, actor, actual -> MaquinaEstadosCaso.investigar(actual, actor), null, null);
    }

    @Transactional
    public Caso cerrar(UUID id, Actor actor, Resolucion resolucion, String comentario) {
        return transicionar(id, actor, actual -> MaquinaEstadosCaso.cerrar(actual, actor, resolucion), resolucion,
                comentario);
    }

    @Transactional
    public Nota agregarNota(UUID id, Actor actor, String texto) {
        if (!actor.puedeTrabajarCasos()) {
            throw new AccessDeniedException("Solo ANALISTA, SUPERVISOR o ADMIN pueden agregar notas");
        }
        existente(id);
        return store.agregarNota(id, actor.usuario(), texto.strip(), Instant.now());
    }

    public Caso buscar(UUID id) {
        return existente(id);
    }

    private Caso transicionar(UUID id, Actor actor, UnaryOperator<EstadoActual> transicion, Resolucion resolucion,
            String comentario) {
        Caso caso = store.bloquear(id).orElseThrow(() -> noExiste(id));
        EstadoActual nuevo = transicion.apply(caso.estadoActual());
        Instant ahora = Instant.now();
        boolean cierra = nuevo.estado() == EstadoCaso.CERRADO;
        store.actualizar(id, nuevo, cierra ? resolucion : null, cierra ? ahora : null);
        store.registrarHistorial(id, caso.estado(), nuevo.estado(), actor.usuario(), ahora, comentario);
        if (cierra) {
            publicador.casoCerrado(caso, resolucion, ahora);
        }
        return existente(id);
    }

    private Caso existente(UUID id) {
        return store.buscar(id).orElseThrow(() -> noExiste(id));
    }

    private static RecursoNoEncontradoException noExiste(UUID id) {
        return new RecursoNoEncontradoException("El caso " + id + " no existe");
    }
}
