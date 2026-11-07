# ADR 0014: API de reglas sin creación y baja de listas por vencimiento

- Estado: aceptada
- Fecha: 2026-11-07

## Contexto

§11 lista `GET/POST/PUT /api/v1/reglas` y `GET/POST/DELETE /api/v1/listas-bloqueo` sin
detallar. Las reglas R01–R07 están implementadas en código, y la lista de bloqueo es
evidencia de auditoría.

## Decisión

- Reglas: `GET /api/v1/reglas`, `GET /api/v1/reglas/{codigo}`,
  `GET /api/v1/reglas/{codigo}/versiones` y `PUT /api/v1/reglas/{codigo}` (crea la versión
  siguiente). **No hay POST de creación**: una regla nueva requiere código. El PUT exige los
  mismos nombres de parámetros que la versión vigente.
- Listas: `DELETE /api/v1/listas-bloqueo/{id}` **vence** la entrada (`vigente_hasta = ahora`);
  no borra la fila. Volver a bloquear el mismo `(tipo, valor)` reactiva la fila.

## Consecuencias

- Queda el rastro de quién bloqueó, por qué y desde cuándo dejó de bloquear.
- Para agregar una regla R08 hace falta código y una migración, no solo la API.
