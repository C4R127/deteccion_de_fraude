package com.fraude.transacciones.scoring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Calienta el cliente del scoring al arrancar, antes de que la aplicación se declare lista,
 * para que las primeras transacciones no caigan en respaldo por arranque en frío.
 */
@Component
@ConditionalOnProperty(name = "calentamiento.habilitado", havingValue = "true", matchIfMissing = true)
public class CalentamientoTransacciones implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CalentamientoTransacciones.class);

    private final ScoringClient cliente;
    private final int iteracionesMinimas;
    private final Duration objetivo;
    private final Duration duracionMaxima;

    public CalentamientoTransacciones(ScoringClient cliente,
            @Value("${calentamiento.iteraciones-minimas:50}") int iteracionesMinimas,
            @Value("${calentamiento.objetivo:10ms}") Duration objetivo,
            @Value("${calentamiento.duracion-maxima:20s}") Duration duracionMaxima) {
        this.cliente = cliente;
        this.iteracionesMinimas = iteracionesMinimas;
        this.objetivo = objetivo;
        this.duracionMaxima = duracionMaxima;
    }

    /**
     * Fase 1: tandas de TANDA llamadas simultáneas hasta el mínimo (abren varias conexiones
     * HTTP, como hará una ráfaga real). Fase 2: llamadas de a una hasta que su media baja del
     * objetivo. Se detiene si el scoring no acepta conexiones o se agota el tiempo máximo.
     */
    @Override
    public void run(ApplicationArguments args) {
        long inicio = System.nanoTime();
        long limite = inicio + duracionMaxima.toNanos();
        int llamadas = 0;
        int ok = 0;
        long mediaSecuencial = Long.MAX_VALUE;
        try (ExecutorService ejecutor = Executors.newFixedThreadPool(TANDA)) {
            boolean disponible = true;
            while (disponible && System.nanoTime() < limite && llamadas < iteracionesMinimas) {
                List<Future<Integer>> tanda = new ArrayList<>();
                for (int i = 0; i < TANDA; i++) {
                    tanda.add(ejecutor.submit(() -> cliente.calentar(1)));
                }
                int correctas = 0;
                for (Future<Integer> f : tanda) {
                    correctas += f.get();
                }
                llamadas += TANDA;
                ok += correctas;
                disponible = correctas > 0;
            }
            while (disponible && System.nanoTime() < limite && mediaSecuencial >= objetivo.toNanos()) {
                long t0 = System.nanoTime();
                int correctas = cliente.calentar(TANDA);
                mediaSecuencial = (System.nanoTime() - t0) / TANDA;
                llamadas += TANDA;
                ok += correctas;
                disponible = correctas > 0;
            }
        } catch (Exception e) {
            log.warn("Calentamiento del cliente del scoring interrumpido: {}", e.toString());
        }
        log.info("Calentamiento del cliente del scoring: {}/{} llamadas en {} ms (de a una: {} ms de media)", ok,
                llamadas, (System.nanoTime() - inicio) / 1_000_000,
                mediaSecuencial == Long.MAX_VALUE ? -1 : mediaSecuencial / 1_000_000);
    }

    private static final int TANDA = 10;
}
