# ADR 0003: Apache Kafka en lugar de RabbitMQ

- Estado: aceptada
- Fecha: 2026-11-07 (registra la decisión de la especificación §4)

## Contexto

Tres flujos asíncronos conectan los servicios: transacciones creadas (para perfiles),
decisiones sospechosas (para casos) y casos cerrados (para listas de bloqueo). Los
perfiles necesitan procesar en orden los eventos de cada tarjeta y poder recalcularse
desde el principio.

## Decisión

Apache Kafka 4 en modo KRaft (sin ZooKeeper), con:

- clave del mensaje = `tarjetaId`, para mantener el orden por tarjeta dentro de una
  partición;
- retención del log, para que Kafka Streams reconstruya su estado y se pueda reprocesar
  la historia;
- Kafka Streams para las ventanas de perfiles;
- outbox transaccional, productores idempotentes y consumidores idempotentes con DLT
  (ADR 0010).

## Alternativas descartadas

- **RabbitMQ:** colas más simples y buen enrutamiento, pero borra el mensaje al
  consumirlo (no permite reprocesar la historia) y no tiene un equivalente directo a
  Kafka Streams para agregar por ventanas con estado.

## Consecuencias

- El orden por tarjeta depende del número de particiones: cambiarlo en un tópico
  existente exige una ADR.
- Más piezas que operar (tópicos, particiones, lag de consumidores) y más reglas que
  cumplir, documentadas en CLAUDE.md.
- En local corre un solo broker con replicación 1; fuera de local se exige replicación 3 y
  `min.insync.replicas=2`.
