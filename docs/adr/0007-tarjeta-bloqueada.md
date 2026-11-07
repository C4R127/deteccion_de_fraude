# ADR 0007: transacción con tarjeta BLOQUEADA

- Estado: aceptada
- Fecha: 2026-11-07

## Contexto

§11 dice que con tarjeta BLOQUEADA la transacción "se registra y se rechaza sin llamar
al scoring", sin fijar score, respaldo ni publicación.

## Decisión

Se guarda con `decision = RECHAZAR`, `score = null`, `respaldo = false`, no se llama al
scoring y **sí** se publica `TransaccionCreadaEvento` en `transacciones.creadas`.

## Consecuencias

- Perfiles la cuenta en `velocidad` y `ultima` (§5.4 incluye las rechazadas), así los
  intentos repetidos con una tarjeta bloqueada siguen visibles.
- No hay fila en `scoring.evaluacion` para estas transacciones: las métricas de
  detección (§9) no las incluyen.
