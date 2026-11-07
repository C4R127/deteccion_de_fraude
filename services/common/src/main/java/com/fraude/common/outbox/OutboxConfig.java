package com.fraude.common.outbox;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Outbox transaccional reutilizable (ADR 0010). Cada servicio productor lo activa con
 * {@code @Import(OutboxConfig.class)} y crea la tabla {@code outbox} en su propio esquema
 * con su migración Flyway.
 */
@Configuration(proxyBeanMethods = false)
@Import({OutboxStore.class, EnviadorKafka.class, PublicadorOutbox.class, ProgramadorOutbox.class})
public class OutboxConfig {
}
