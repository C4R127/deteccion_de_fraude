package com.fraude.perfiles;

import com.fraude.perfiles.perfil.EscritorPerfilValkey;
import com.fraude.perfiles.valkey.EscritorDispositivos;
import com.fraude.perfiles.valkey.EscritorUltima;
import com.fraude.perfiles.valkey.EscritorVelocidad;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.streams.StreamsBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.EnableKafkaStreams;

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
}
