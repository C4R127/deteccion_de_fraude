package com.fraude.scoring;

import com.fraude.common.evaluacion.EvaluacionSolicitud;
import com.fraude.common.eventos.Canal;
import com.fraude.common.eventos.Topicos;
import com.fraude.common.outbox.OutboxStore;
import com.fraude.scoring.motor.EvaluacionStore;
import com.fraude.scoring.motor.MotorEvaluacion;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Calentamiento al arrancar (riesgo "arranque en frío"), antes de que la aplicación se
 * declare lista. Repite en rondas paralelas el camino de una evaluación hasta que su latencia
 * baja del objetivo (o se agota el tiempo máximo), sin efectos visibles:
 * <ul>
 *   <li>motor con transacciones sintéticas (Valkey, lista de bloqueo, reglas);</li>
 *   <li>escritura de evaluación y outbox dentro de una transacción que se revierte;</li>
 *   <li>POST con cuerpo inválido al endpoint propio (responde 422: Tomcat, JSON, validación).</li>
 * </ul>
 * No guarda evaluaciones, no publica eventos ni registra la métrica de latencia.
 */
@Component
@ConditionalOnProperty(name = "calentamiento.habilitado", havingValue = "true", matchIfMissing = true)
public class CalentamientoScoring implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CalentamientoScoring.class);

    private final MotorEvaluacion motor;
    private final EvaluacionStore store;
    private final OutboxStore outbox;
    private final TransactionTemplate tx;
    private final Environment entorno;
    private final int iteracionesMinimas;
    private final Duration objetivo;
    private final Duration duracionMaxima;
    private final int hilos;
    private final AtomicInteger hechas = new AtomicInteger();

    public CalentamientoScoring(MotorEvaluacion motor, EvaluacionStore store, OutboxStore outbox,
            TransactionTemplate tx, Environment entorno,
            @Value("${calentamiento.iteraciones-minimas:50}") int iteracionesMinimas,
            @Value("${calentamiento.objetivo:20ms}") Duration objetivo,
            @Value("${calentamiento.duracion-maxima:20s}") Duration duracionMaxima,
            @Value("${calentamiento.hilos:16}") int hilos) {
        this.hilos = Math.max(1, hilos);
        this.motor = motor;
        this.store = store;
        this.outbox = outbox;
        this.tx = tx;
        this.entorno = entorno;
        this.iteracionesMinimas = iteracionesMinimas;
        this.objetivo = objetivo;
        this.duracionMaxima = duracionMaxima;
    }

    @Override
    public void run(ApplicationArguments args) {
        long inicio = System.nanoTime();
        long limite = inicio + duracionMaxima.toNanos();
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        String puerto = entorno.getProperty("local.server.port");
        long mediaSecuencial = Long.MAX_VALUE;
        try (ExecutorService ejecutor = Executors.newFixedThreadPool(hilos)) {
            // Fase 1: rondas de "hilos" iteraciones simultáneas (código caliente y pools llenos).
            while (System.nanoTime() < limite && hechas.get() < iteracionesMinimas) {
                List<Future<?>> ronda = new ArrayList<>();
                for (int i = 0; i < hilos; i++) {
                    ronda.add(ejecutor.submit(() -> {
                        iteracion(http, puerto);
                        hechas.incrementAndGet();
                    }));
                }
                for (Future<?> f : ronda) {
                    f.get();
                }
            }
            // Fase 2: iteraciones de a una hasta que la media de las últimas baja del objetivo.
            long[] ultimas = new long[RONDA_SECUENCIAL];
            while (System.nanoTime() < limite && mediaSecuencial >= objetivo.toNanos()) {
                for (int i = 0; i < RONDA_SECUENCIAL; i++) {
                    long t0 = System.nanoTime();
                    iteracion(http, puerto);
                    hechas.incrementAndGet();
                    ultimas[i] = System.nanoTime() - t0;
                }
                mediaSecuencial = java.util.Arrays.stream(ultimas).sum() / RONDA_SECUENCIAL;
            }
        } catch (Exception e) {
            log.warn("Calentamiento interrumpido tras {} iteraciones: {}", hechas.get(), e.toString());
        }
        log.info("Calentamiento del scoring: {} iteraciones en {} ms ({} hilos; últimas de a una: {} ms de media)",
                hechas.get(), (System.nanoTime() - inicio) / 1_000_000, hilos,
                mediaSecuencial == Long.MAX_VALUE ? -1 : mediaSecuencial / 1_000_000);
    }

    private static final int RONDA_SECUENCIAL = 10;

    private void iteracion(HttpClient http, String puerto) {
        EvaluacionSolicitud s = sintetica();
        var calculo = motor.calcular(s);
        store.buscar(s.transaccionId());
        tx.executeWithoutResult(estado -> {
            store.insertar(s, calculo, 0, false, Instant.now());
            outbox.guardar(Topicos.DECISIONES_SOSPECHOSAS, s.tarjetaId().toString(), "{}");
            estado.setRollbackOnly();
        });
        if (puerto != null) {
            try {
                http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + "/internal/v1/evaluaciones"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"calentamiento\": true}")).build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                log.debug("Calentamiento HTTP: {}", e.toString());
            }
        }
    }

    public int iteracionesHechas() {
        return hechas.get();
    }

    /** Transacción ficticia: ids aleatorios que no existen en Valkey ni en las tablas. */
    static EvaluacionSolicitud sintetica() {
        return new EvaluacionSolicitud(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "CALENTAMIENTO", "5411", "calentamiento", new BigDecimal("1.00"), "PEN", Canal.POS, "PE",
                new BigDecimal("-12.046400"), new BigDecimal("-77.042800"), Instant.now());
    }
}
