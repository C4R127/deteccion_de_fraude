package com.fraude.scoring;

import com.fraude.common.eventos.Topicos;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Infraestructura real para las pruebas, con las mismas versiones que
 * infra/docker-compose.yml. Las pruebas nunca usan el Kafka local (reglas de Kafka).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16.15");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> valkey() {
        return new GenericContainer<>("valkey/valkey:8.1.11-alpine").withExposedPorts(6379);
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.3.2");
    }

    @Bean
    NewTopic decisionesSospechosas() {
        return new NewTopic(Topicos.DECISIONES_SOSPECHOSAS, 6, (short) 1);
    }
}
