-- Esquema casos (§5.3). Los casos nacen del tópico decisiones.sospechosas (§8.1).

CREATE TABLE caso (
    id                 uuid          PRIMARY KEY,
    -- unique: el mismo evento dos veces no crea dos casos (consumidor idempotente).
    transaccion_id     uuid          NOT NULL UNIQUE,
    tarjeta_id         uuid          NOT NULL,
    cliente_id         uuid          NOT NULL,
    dispositivo_huella text          NULL,
    monto              numeric(15,2) NOT NULL,
    score              int           NOT NULL CHECK (score BETWEEN 0 AND 100),
    decision           text          NOT NULL CHECK (decision IN ('REVISAR', 'RECHAZAR')),
    reglas_disparadas  jsonb         NOT NULL,
    prioridad          text          NOT NULL CHECK (prioridad IN ('ALTA', 'MEDIA')),
    estado             text          NOT NULL CHECK (estado IN ('NUEVO', 'ASIGNADO', 'EN_INVESTIGACION', 'CERRADO')),
    analista_id        text          NULL,
    creado_en          timestamptz   NOT NULL,
    cerrado_en         timestamptz   NULL,
    resolucion         text          NULL CHECK (resolucion IN ('FRAUDE_CONFIRMADO', 'FALSO_POSITIVO')),
    CHECK ((estado = 'CERRADO') = (resolucion IS NOT NULL AND cerrado_en IS NOT NULL)),
    CHECK (estado = 'NUEVO' OR analista_id IS NOT NULL)
);
-- Bandeja (§8.3): filtros por estado, prioridad y analista; orden por prioridad y antigüedad.
CREATE INDEX caso_bandeja_idx ON caso (estado, prioridad, creado_en);
CREATE INDEX caso_analista_idx ON caso (analista_id, creado_en);

CREATE TABLE nota_caso (
    id        uuid        PRIMARY KEY,
    caso_id   uuid        NOT NULL REFERENCES caso (id),
    autor_id  text        NOT NULL,
    texto     text        NOT NULL,
    creada_en timestamptz NOT NULL
);
CREATE INDEX nota_caso_caso_idx ON nota_caso (caso_id, creada_en);

CREATE TABLE historial_caso (
    id              uuid        PRIMARY KEY,
    caso_id         uuid        NOT NULL REFERENCES caso (id),
    estado_anterior text        NULL,
    estado_nuevo    text        NOT NULL,
    usuario_id      text        NOT NULL,
    fecha           timestamptz NOT NULL,
    comentario      text        NULL
);
CREATE INDEX historial_caso_caso_idx ON historial_caso (caso_id, fecha);

-- Auditoría: historial_caso solo admite INSERT (CLAUDE.md).
CREATE FUNCTION solo_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'La tabla % solo admite INSERT', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER historial_caso_solo_insert
    BEFORE UPDATE OR DELETE ON historial_caso
    FOR EACH ROW EXECUTE FUNCTION solo_insert();

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
