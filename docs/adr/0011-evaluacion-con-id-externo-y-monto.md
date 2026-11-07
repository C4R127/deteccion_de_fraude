# ADR 0011: `evaluacion` guarda `id_externo` y `monto`

- Estado: aceptada
- Fecha: 2026-11-07

## Contexto

Las métricas de detección (§9) cruzan las evaluaciones con `etiqueta_verdad` por el
`idExterno` de la transacción y suman montos de fraude evitado y no detectado. La tabla
`scoring.evaluacion` de §5.2 no tiene esos datos y el scoring no puede leer el esquema
`transacciones`. El contrato de evaluaciones (ADR 0006) ya los envía.

## Decisión

`scoring.evaluacion` agrega `id_externo uuid not null` (único) y `monto numeric(15,2) not null`,
copiados de la solicitud de evaluación.

## Consecuencias

- Detección, precisión, falsos positivos y montos por escenario se calculan dentro del
  esquema `scoring`.
- Siguen abiertas (Fase 6) las métricas de respaldos (las conoce `transacciones`) y de casos.
