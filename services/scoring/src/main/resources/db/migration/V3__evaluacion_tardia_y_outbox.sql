-- ADR 0012: una evaluación que supera el tiempo límite se marca y no publica.
ALTER TABLE evaluacion ADD COLUMN tardia boolean NOT NULL DEFAULT false;

-- Outbox transaccional (CLAUDE.md "Reglas obligatorias de Kafka", ADR 0010).
CREATE TABLE outbox (
    id              uuid        PRIMARY KEY,
    topico          text        NOT NULL,
    clave           text        NOT NULL,
    payload         text        NOT NULL, -- JSON tal cual se publicará
    creado_en       timestamptz NOT NULL DEFAULT clock_timestamp(),
    enviado_en      timestamptz NULL,
    intentos        int         NOT NULL DEFAULT 0,
    proximo_intento timestamptz NOT NULL DEFAULT clock_timestamp(),
    ultimo_error    text        NULL
);
CREATE INDEX outbox_pendientes_idx ON outbox (creado_en) WHERE enviado_en IS NULL;
