package com.fraude.transacciones.scoring;

import com.fraude.common.evaluacion.EvaluacionResultado;
import com.fraude.common.evaluacion.EvaluacionSolicitud;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.net.http.HttpClient;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Pide la decisión al scoring con tiempo límite (Resilience4j {@link TimeLimiter}).
 * Devuelve vacío si el scoring falla, responde error o excede el tiempo: quien llama aplica
 * el respaldo {@code APROBAR, respaldo = true, score = null}.
 */
@Component
public class ScoringClient {

    private static final Logger log = LoggerFactory.getLogger(ScoringClient.class);
    private static final String RUTA = "/internal/v1/evaluaciones";

    private final RestClient rest;
    private final TimeLimiter limitador;
    private final ExecutorService ejecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ScoringClient(RestClient.Builder builder, ScoringProperties props) {
        // HTTP/1.1: sin el intento de upgrade h2c, que agrega latencia y reinicios de stream.
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.tiempoLimite())
                .build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(http, ejecutor);
        // La llamada en segundo plano no debe quedar colgada más allá del límite.
        fabrica.setReadTimeout(props.tiempoLimite().multipliedBy(2));
        this.rest = builder.baseUrl(props.url()).requestFactory(fabrica).build();
        this.limitador = TimeLimiter.of("scoring", TimeLimiterConfig.custom()
                .timeoutDuration(props.tiempoLimite())
                .cancelRunningFuture(true)
                .build());
    }

    public Optional<EvaluacionResultado> evaluar(EvaluacionSolicitud solicitud) {
        try {
            EvaluacionResultado resultado = limitador.executeFutureSupplier(() -> CompletableFuture.supplyAsync(
                    () -> rest.post().uri(RUTA).body(solicitud).retrieve().body(EvaluacionResultado.class),
                    ejecutor));
            if (resultado == null || resultado.decision() == null) {
                log.warn("Scoring respondió sin decisión para {}; se aplica respaldo", solicitud.transaccionId());
                return Optional.empty();
            }
            return Optional.of(resultado);
        } catch (Exception e) {
            log.warn("Scoring no disponible para {} ({}); se aplica respaldo",
                    solicitud.transaccionId(), e.toString());
            return Optional.empty();
        }
    }
}
