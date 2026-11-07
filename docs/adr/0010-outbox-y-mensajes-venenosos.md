# ADR 0010: outbox transaccional y tópicos de mensajes venenosos

- Estado: aceptada
- Fecha: 2026-11-07
- Reemplaza: la publicación con `@TransactionalEventListener(AFTER_COMMIT)` de la Tarea 2.3.

## Contexto

`CLAUDE.md` (“Reglas obligatorias de Kafka”) prohíbe publicar directo desde el código de
negocio, exige no perder eventos, productores que esperen el `ack`, tópicos `.dlt` para
mensajes que no se pueden procesar y `exactly_once_v2` en Kafka Streams.

## Decisión

**Productores (transacciones; luego scoring y casos):**

- Tabla `outbox` en el esquema del servicio: `id`, `topico`, `clave`, `payload` (JSON del
  evento), `creado_en`, `enviado_en`, `intentos`, `proximo_intento`, `ultimo_error`.
  Se inserta en la misma transacción de base de datos que el cambio de negocio.
- `PublicadorOutbox` (tarea programada, cada 200 ms): toma pendientes con
  `FOR UPDATE SKIP LOCKED` en orden de creación, envía y **espera el ack**; solo entonces
  marca `enviado_en`. Si falla: `intentos + 1`, espera creciente (1 s × 2^intentos, máximo
  5 min), `ultimo_error`, log de error y métrica. Si un evento de una clave falla, los
  siguientes de la misma clave en ese lote no se envían, para no alterar el orden por tarjeta.
- Productor: `acks=all`, `enable.idempotence=true`, reintentos por defecto dentro de
  `delivery.timeout.ms`.
- Métricas: `outbox.pendientes` (gauge) y `outbox.fallos` (contador).
- Las filas enviadas se borran a los 7 días.

**Consumidores (perfiles con Kafka Streams):**

- Errores de deserialización → `RecoveringDeserializationExceptionHandler` publica el
  registro original en `<tópico>.dlt` con la excepción en cabeceras; la partición sigue.
- Eventos que deserializan pero no traen los campos obligatorios → `<tópico>.dlt` con la
  cabecera `x-error` (nunca se descartan en silencio).
- `processing.guarantee=exactly_once_v2`.
- Lag expuesto con las métricas de Micrometer del consumidor de Kafka Streams.

**Infraestructura:** `crear-topicos.sh` crea un `.dlt` por tópico con sus mismas particiones.

## Consecuencias

- La latencia entre el commit y la llegada a Kafka sube hasta el intervalo del publicador
  (≤ 200 ms + envío). La respuesta HTTP no espera a Kafka.
- Un evento puede enviarse más de una vez (si el proceso cae tras el ack y antes de marcar
  `enviado_en`): los consumidores ya son idempotentes por `transaccionId`.
- `exactly_once_v2` cubre los offsets y el estado de Kafka Streams; las escrituras en
  Valkey quedan fuera de esa transacción y siguen protegidas por ser idempotentes.
