package com.fraude.perfiles;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;

/** Carga el contexto sin arrancar Kafka Streams (no hay broker en esta prueba). */
@SpringBootTest(classes = PerfilesApplication.class, properties = "spring.kafka.streams.auto-startup=false")
class PerfilesApplicationTests {

    @Autowired
    StreamsBuilderFactoryBean streams;

    @Test
    void contextLoads() {
        assertThat(streams.getTopology()).isNotNull();
        assertThat(streams.getTopology().describe().toString()).contains("transacciones.creadas");
    }
}
