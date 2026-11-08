package com.fraude.common.kafka;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Manejo de errores de los consumidores (reglas de Kafka de CLAUDE.md): fallos transitorios
 * se reintentan con espera creciente; los eventos inválidos y los que agotan los reintentos
 * van a {@code <tópico>.dlt} con la excepción en cabeceras. La partición nunca se bloquea.
 * Los offsets se confirman después del efecto (sin auto-commit, ack por registro).
 *
 * <p>Común a los servicios con consumidores {@code @KafkaListener} (scoring y casos); cada uno
 * lo activa con {@code @Import(ConsumidoresConfig.class)}.
 */
@Configuration(proxyBeanMethods = false)
public class ConsumidoresConfig {

    private static final Logger log = LoggerFactory.getLogger(ConsumidoresConfig.class);

    @Bean
    DefaultErrorHandler manejoDeErrores(KafkaTemplate<Object, Object> plantilla) {
        var recuperador = new DeadLetterPublishingRecoverer(plantilla, (registro, error) -> {
            log.error("Evento de {} enviado a la DLT: {}", registro.topic(), error.getMessage());
            return new TopicPartition(registro.topic() + ".dlt", -1);
        });
        ExponentialBackOff espera = new ExponentialBackOff(500, 2.0);
        espera.setMaxElapsedTime(10_000);
        DefaultErrorHandler manejador = new DefaultErrorHandler(recuperador, espera);
        manejador.addNotRetryableExceptions(EventoInvalidoException.class);
        return manejador;
    }
}
