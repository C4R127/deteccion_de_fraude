-- Outbox transaccional (CLAUDE.md "Reglas obligatorias de Kafka", ADR 0010).
-- El evento se inserta en la misma transacción que el cambio de negocio y un publicador
-- lo envía a Kafka; solo se marca enviado tras el ack.

CREATE TABLE outbox (
    id              uuid        PRIMARY KEY,
    topico          text        NOT NULL,
    clave           text        NOT NULL,
    payload         text        NOT NULL, -- JSON tal cual se publicará (jsonb lo reordenaría)
    creado_en       timestamptz NOT NULL DEFAULT clock_timestamp(),
    enviado_en      timestamptz NULL,
    intentos        int         NOT NULL DEFAULT 0,
    proximo_intento timestamptz NOT NULL DEFAULT clock_timestamp(),
    ultimo_error    text        NULL
);
-- Pendientes en orden de creación (lo que lee el publicador).
CREATE INDEX outbox_pendientes_idx ON outbox (creado_en) WHERE enviado_en IS NULL;
