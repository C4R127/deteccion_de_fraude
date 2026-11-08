# ADR 0012: evaluación tardía no publica

- Estado: aceptada
- Fecha: 2026-11-07

## Contexto

Transacciones espera la decisión del scoring con un tiempo límite (200 ms). Si el scoring
responde después, transacciones ya aplicó `APROBAR` con respaldo, pero el scoring guardaría
su evaluación y podría publicar `DecisionSospechosaEvento`: se abriría un caso sobre un pago
aprobado y las métricas contarían un rechazo que nunca se aplicó.

## Decisión

- El scoring mide la latencia de cada evaluación. Si supera `scoring.tiempo-limite`
  (el mismo valor que usa transacciones, inicial 200 ms), la evaluación se guarda con
  `tardia = true` y **no** se publica `DecisionSospechosaEvento`.
- Las métricas (§9) tratan las evaluaciones tardías como decisiones de respaldo.
- La latencia se mide desde que llega la solicitud al servicio hasta justo antes de guardar la
  evaluación, dentro de la transacción. Incluye la consulta de idempotencia, el cálculo de
  reglas y la espera por una conexión del pool. Antes solo medía el cálculo de reglas; se
  corrigió tras la revisión de código.

## Consecuencias

- No se abren casos para pagos que transacciones aprobó por respaldo cuando el retraso
  está dentro del scoring.
- Queda un borde: si el scoring tarda menos que el límite pero la red suma el resto,
  transacciones aplica respaldo y el scoring publica igual. Se acepta para el MVP; si las
  métricas lo muestran, se revisará (por ejemplo, reconciliando con `transacciones.creadas`).
- Ambos servicios deben configurar el mismo tiempo límite.
