package com.fraude.transacciones.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Envía los eventos pendientes del outbox (ADR 0010). Un evento se marca enviado solo después
 * del ack; si falla, se reprograma con espera creciente y no se adelantan los eventos
 * siguientes de la misma clave, para conservar el orden por tarjeta.
 */
@Service
public class PublicadorOutbox {

    private static final Logger log = LoggerFactory.getLogger(PublicadorOutbox.class);
    static final int LOTE = 100;
    static final Duration ESPERA_BASE = Duration.ofSeconds(1);
    static final Duration ESPERA_MAXIMA = Duration.ofMinutes(5);
    static final Duration RETENCION_ENVIADOS = Duration.ofDays(7);

    private final OutboxStore store;
    private final EnviadorEventos enviador;
    private final TransactionTemplate tx;
    private final Counter fallos;
    private final Clock reloj = Clock.systemUTC();

    public PublicadorOutbox(OutboxStore store, EnviadorEventos enviador, TransactionTemplate tx,
            MeterRegistry metricas) {
        this.store = store;
        this.enviador = enviador;
        this.tx = tx;
        this.fallos = Counter.builder("outbox.fallos")
                .description("Envíos del outbox a Kafka que fallaron y se reprogramaron")
                .register(metricas);
        Gauge.builder("outbox.pendientes", store, OutboxStore::contarPendientes)
                .description("Eventos del outbox aún no confirmados por Kafka")
                .register(metricas);
    }

    /** Publica un lote de pendientes vencidos; devuelve cuántos se confirmaron. */
    public int publicarPendientes() {
        Integer enviados = tx.execute(estado -> {
            int ok = 0;
            Set<String> clavesBloqueadas = new HashSet<>();
            for (OutboxStore.Pendiente p : store.tomarPendientes(LOTE)) {
                if (clavesBloqueadas.contains(p.clave())) {
                    continue;
                }
                try {
                    enviador.enviar(p.topico(), p.clave(), p.payload());
                    store.marcarEnviado(p.id());
                    ok++;
                } catch (Exception e) {
                    clavesBloqueadas.add(p.clave());
                    Instant proximo = reloj.instant().plus(espera(p.intentos() + 1));
                    store.marcarFallo(p.id(), e.toString(), proximo);
                    fallos.increment();
                    log.error("No se pudo publicar el evento {} del outbox en {} (intento {}); reintento {}",
                            p.id(), p.topico(), p.intentos() + 1, proximo, e);
                }
            }
            return ok;
        });
        return enviados == null ? 0 : enviados;
    }

    /** Borra las filas ya enviadas hace más de 7 días. */
    public int purgarEnviados() {
        return store.borrarEnviadosAntesDe(reloj.instant().minus(RETENCION_ENVIADOS));
    }

    /** 1 s × 2^(intento − 1), con tope de 5 minutos. */
    static Duration espera(int intento) {
        long factor = 1L << Math.min(intento - 1, 20);
        Duration espera = ESPERA_BASE.multipliedBy(factor);
        return espera.compareTo(ESPERA_MAXIMA) > 0 ? ESPERA_MAXIMA : espera;
    }
}
