# ADR 0001: decisión en línea con respaldo por tiempo límite

- Estado: aceptada
- Fecha: 2026-11-07 (registra la decisión de la especificación §3)

## Contexto

Un pago con tarjeta espera la respuesta del emisor en milisegundos. El sistema debe
decidir APROBAR, REVISAR o RECHAZAR antes de responder, pero el motor de reglas depende
de Valkey y de PostgreSQL, y cualquiera de los dos puede tardar o fallar.

## Decisión

- transacciones llama a scoring de forma **síncrona** (`POST /internal/v1/evaluaciones`)
  con un tiempo límite configurable, inicial 200 ms (Resilience4j `TimeLimiter`).
- Si scoring falla o se pasa del límite, la transacción se aprueba **por respaldo**:
  `APROBAR`, `respaldo = true`, `score = null`. El pago no se bloquea por una falla
  interna.
- Los perfiles y los casos se actualizan después y de forma asíncrona por Kafka; no
  forman parte del camino crítico.
- Una evaluación que termina después del límite se guarda marcada como tardía y no abre
  caso (ADR 0012).

## Alternativas descartadas

- **Evaluación asíncrona (aprobar y revisar después):** más simple, pero no puede
  rechazar un fraude a tiempo.
- **Rechazar ante falla:** protege más, pero una caída del scoring bloquearía todos los
  pagos legítimos.

## Consecuencias

- Disponibilidad antes que detección cuando algo falla. Los respaldos se miden
  (`GET /api/v1/metricas/transacciones`, ADR 0017) para verlos crecer.
- Medido en la Tarea 4.6: p95 del timer del scoring 14 ms a 20 tps. Los respaldos
  aparecen solo en el arranque en frío, que se mitiga con calentamiento (ADR 0013).
- La conexión de base de datos de transacciones queda tomada mientras espera al scoring;
  a 20 tps se usaron como máximo 8 de 10 conexiones.
