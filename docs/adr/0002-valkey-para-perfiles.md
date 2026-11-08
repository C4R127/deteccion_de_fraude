# ADR 0002: perfiles en Valkey, escritos por Kafka Streams

- Estado: aceptada
- Fecha: 2026-11-07 (registra la decisión de la especificación §3 y §5.4)

## Contexto

Las reglas comparan cada transacción con el comportamiento reciente del cliente:
promedio de 30 días, países, rubros, horas, dispositivos conocidos, velocidad de los
últimos 10 minutos y última ubicación. Calcularlo en cada evaluación sobre PostgreSQL
agrega consultas pesadas al camino crítico de 200 ms.

## Decisión

- El servicio **perfiles** (Kafka Streams) consume `transacciones.creadas`, agrega por
  ventanas usando la fecha del evento y escribe resultados ya listos en **Valkey**:
  `perfil:{clienteId}`, `velocidad:{tarjetaId}`, `ultima:{tarjetaId}` y
  `dispositivos:{clienteId}`.
- scoring solo **lee** Valkey, con una ida y vuelta por evaluación (pipeline).
- Se usa Valkey en lugar de Redis por su licencia abierta; es compatible con el
  protocolo y con Spring Data Redis (Lettuce).

## Alternativas descartadas

- **Consultar PostgreSQL en cada evaluación:** simple, pero lento y acopla scoring al
  esquema de transacciones (prohibido, ADR 0004).
- **Estado de Kafka Streams consultado por API (interactive queries):** evita Valkey,
  pero agrega una llamada de red a otro servicio en el camino crítico.

## Consecuencias

- La lectura del perfil es O(1) y rápida (p95 total de la evaluación 14 ms).
- El perfil va un poco atrasado: al evaluar una transacción todavía no la incluye. Las
  reglas cuentan solo las previas, y eso es lo que pide la especificación.
- Valkey es reconstruible: el estado fuente está en el changelog de Kafka Streams
  (`exactly_once_v2`).
