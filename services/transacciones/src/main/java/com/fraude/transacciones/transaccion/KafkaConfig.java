package com.fraude.transacciones.transaccion;

import com.fraude.common.eventos.TransaccionCreadaEvento;
import com.fraude.common.json.JsonFraude;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Productor de eventos con el JSON común ({@link JsonFraude}) y sin cabeceras de tipo Java,
 * para que los consumidores no dependan de nombres de clase de este servicio.
 */
@Configuration
public class KafkaConfig {

    /**
     * Parte de la configuración de la fábrica de Spring Boot (incluye la conexión resuelta,
     * p. ej. la de Testcontainers) y solo cambia los serializadores.
     */
    @Bean
    KafkaTemplate<String, TransaccionCreadaEvento> kafkaTransacciones(ProducerFactory<?, ?> base) {
        JsonSerializer<TransaccionCreadaEvento> json = new JsonSerializer<TransaccionCreadaEvento>(
                JsonFraude.objectMapper()).noTypeInfo();
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                base.getConfigurationProperties(), new StringSerializer(), json));
    }
}
