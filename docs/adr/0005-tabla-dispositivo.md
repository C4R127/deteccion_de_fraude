# ADR 0005: tabla `dispositivo` en el esquema `transacciones`

- Estado: aceptada
- Fecha: 2026-11-07

## Contexto

La Tarea 2.1 del plan pide la entidad `dispositivo` y el generador siembra dispositivos
(§6.2 `siembra`), pero la especificación §5.1 no definía esa tabla.

## Decisión

Se agrega al esquema `transacciones`:

`dispositivo`: `huella text pk`, `cliente_id uuid fk → cliente`, `creado_en timestamptz`.

Registra los dispositivos conocidos de cada cliente que entrega la siembra. Las reglas
de scoring (R05) siguen usando `dispositivos:{clienteId}` en Valkey, que alimenta el
servicio de perfiles; esta tabla no la consulta el scoring.

## Consecuencias

- La siembra es la fuente de los dispositivos iniciales y queda trazable en base de datos.
- Hay dos registros de dispositivos con propósitos distintos (maestro en PostgreSQL,
  dispositivos observados en Valkey); no se sincronizan.

(Los números 0001–0004 quedan reservados para las ADR de la Tarea 6.4 del plan.)
