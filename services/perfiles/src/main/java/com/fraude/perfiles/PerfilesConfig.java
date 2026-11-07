package com.fraude.perfiles;

import com.fraude.perfiles.perfil.EscritorPerfilValkey;
import com.fraude.perfiles.valkey.EscritorDispositivos;
import com.fraude.perfiles.valkey.EscritorUltima;
import com.fraude.perfiles.valkey.EscritorVelocidad;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.StreamsBuilderFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.streams.RecoveringDeserializationExceptionHandler;

@Configuration
@EnableKafkaStreams
public class PerfilesConfig {

    @Bean
    TopologiaPerfiles topologiaPerfiles(StreamsBuilder builder, StringRedisTemplate redis,
            @Value("${perfiles.ventana:30d}") Duration ventana) {
        TopologiaPerfiles topologia = new TopologiaPerfiles(new EscritorPerfilValkey(redis),
                List.of(new EscritorVelocidad(redis), new EscritorUltima(redis), new EscritorDispositivos(redis)),
                ventana);
        topologia.agregar(builder);
        return topologia;
    }

    /**
     * Mensajes que no se pueden deserializar: se publican tal cual en {@code <tópico>.dlt}
     * con la excepción en cabeceras y la partición sigue (CLAUDE.md, ADR 0010). El productor
     * parte de la configuración de Spring Boot (incluye la conexión resuelta).
     */
    @Bean
    DeadLetterPublishingRecoverer recuperadorDlt(ProducerFactory<?, ?> base) {
        KafkaTemplate<byte[], byte[]> plantilla = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                base.getConfigurationProperties(), new ByteArraySerializer(), new ByteArraySerializer()));
        return new DeadLetterPublishingRecoverer(plantilla,
                (registro, error) -> new TopicPartition(TopologiaPerfiles.dlt(registro.topic()), -1));
    }

    @Bean
    StreamsBuilderFactoryBeanCustomizer manejoDeMensajesVenenosos(DeadLetterPublishingRecoverer recuperador) {
        return fabrica -> {
            Properties config = fabrica.getStreamsConfiguration();
            config.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                    RecoveringDeserializationExceptionHandler.class);
            config.put(RecoveringDeserializationExceptionHandler.KSTREAM_DESERIALIZATION_RECOVERER, recuperador);
            fabrica.setStreamsConfiguration(config);
        };
    }
}
