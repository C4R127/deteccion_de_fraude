# ADR 0013: calentamiento al arrancar

- Estado: aceptada
- Fecha: 2026-11-07

## Contexto

Riesgo abierto "arranque en frío": tras arrancar, las primeras evaluaciones superaban el
tiempo límite de 200 ms (carga de clases, compilación, conexiones a PostgreSQL y Valkey,
conexiones HTTP) y caían en respaldo. Con una ráfaga inicial, ni el scoring ni el cliente
de transacciones llegaban a tiempo aunque cada uno estuviera "caliente" de forma secuencial.

## Decisión

Ambos servicios ejecutan un `ApplicationRunner` antes de declararse listos
(`calentamiento.*`, activo por defecto):

- **Scoring:** fase paralela (16 hilos, 400 iteraciones mínimas) y fase de a una hasta que
  la media de 10 iteraciones baja de 20 ms (máximo 20 s). Cada iteración ejecuta el motor
  con una transacción sintética, escribe evaluación y outbox en una transacción que se
  revierte y hace un POST inválido al endpoint propio (422). Sin efectos visibles: no hay
  filas, eventos ni muestras en `scoring.evaluacion.latencia`.
- **Transacciones:** el mismo esquema (200 llamadas en tandas de 10 y luego de a una hasta
  10 ms) con solicitudes de evaluación inválidas (sin `transaccionId`) que el scoring
  rechaza con 422; recorren el mismo camino que una evaluación real.
- Scoring usa un pool de conexiones de Lettuce (el pipeline de `PerfilLector` necesita una
  conexión dedicada; sin pool se abría una por evaluación).

## Consecuencias

- Verificado en frío con ráfaga inicial: 0 de 20 en respaldo (antes 14 de 20).
- El arranque tarda unos segundos más (scoring ~5 s, transacciones ~2 s).
- Si el scoring no está disponible al arrancar transacciones, su calentamiento se omite
  (best effort) y las primeras evaluaciones pueden volver a caer en respaldo.
- El scoring recibe peticiones 422 de calentamiento al arrancar transacciones.
