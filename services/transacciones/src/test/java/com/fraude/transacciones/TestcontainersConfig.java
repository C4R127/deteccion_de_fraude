package com.fraude.transacciones;

import com.fraude.common.eventos.Topicos;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/** PostgreSQL y Kafka reales para las pruebas; mismas versiones que infra/docker-compose.yml. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16.15");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.3.2");
    }

    /** En local los crea infra/kafka/crear-topicos.sh; en pruebas, KafkaAdmin. */
    @Bean
    NewTopic transaccionesCreadas() {
        return new NewTopic(Topicos.TRANSACCIONES_CREADAS, 6, (short) 1);
    }
}
