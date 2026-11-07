# ADR 0016: el nombre del cliente lo sirve transacciones, enmascarado para AUDITOR

- Estado: aceptada
- Fecha: 2026-11-07

## Contexto

§10 pide mostrar al AUDITOR el nombre del cliente enmascarado (ej. "J*** P***"). El nombre
solo existe en `transacciones.cliente`: ni `casos.caso` ni los eventos lo traen, y ningún
servicio puede leer el esquema de otro.

## Decisión

- transacciones, dueño del dato, expone `GET /api/v1/clientes/{id}` para los roles de
  lectura: `id`, `nombre`, `segmento`, `ciudadResidencia`, `fechaAlta`.
- Si el usuario no tiene ADMIN, SUPERVISOR ni ANALISTA (es decir, solo AUDITOR), `nombre` se
  enmascara: cada palabra conserva su primera letra seguida de `***`.
- casos no guarda el nombre. El detalle del caso lo pide a transacciones al abrirse; la
  bandeja no muestra el nombre.

## Consecuencias

- El dato personal queda en un solo lugar y el enmascarado en un solo servicio.
- El consumidor de casos no depende de que transacciones responda.
- La bandeja identifica al cliente por la tarjeta (últimos 4) y el monto, no por el nombre.
