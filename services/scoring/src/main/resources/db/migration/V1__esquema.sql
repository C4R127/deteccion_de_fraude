-- Esquema scoring (especificación §5.2, ADR 0011).

CREATE TABLE regla (
    id          uuid        PRIMARY KEY,
    codigo      text        NOT NULL,
    nombre      text        NOT NULL,
    descripcion text        NOT NULL,
    parametros  jsonb       NOT NULL,
    puntos      int         NOT NULL CHECK (puntos >= 0),
    activa      boolean     NOT NULL,
    version     int         NOT NULL CHECK (version >= 1),
    vigente     boolean     NOT NULL,
    creada_en   timestamptz NOT NULL,
    creada_por  text        NOT NULL,
    UNIQUE (codigo, version)
);
-- Solo una fila vigente por código.
CREATE UNIQUE INDEX regla_vigente_unica ON regla (codigo) WHERE vigente;

-- Auditoría: solo INSERT (CLAUDE.md).
CREATE TABLE evaluacion (
    id                uuid           PRIMARY KEY,
    transaccion_id    uuid           NOT NULL UNIQUE,
    id_externo        uuid           NOT NULL UNIQUE,
    monto             numeric(15, 2) NOT NULL,
    score             int            NOT NULL CHECK (score BETWEEN 0 AND 100),
    decision          text           NOT NULL CHECK (decision IN ('APROBAR', 'REVISAR', 'RECHAZAR')),
    reglas_disparadas jsonb          NOT NULL,
    versiones_reglas  jsonb          NOT NULL,
    latencia_ms       int            NOT NULL,
    creada_en         timestamptz    NOT NULL
);
CREATE INDEX evaluacion_creada_en_idx ON evaluacion (creada_en);

CREATE FUNCTION solo_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'La tabla % solo admite INSERT', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER evaluacion_solo_insert
    BEFORE UPDATE OR DELETE ON evaluacion
    FOR EACH ROW EXECUTE FUNCTION solo_insert();

CREATE TABLE lista_bloqueo (
    id            uuid        PRIMARY KEY,
    tipo          text        NOT NULL CHECK (tipo IN ('TARJETA', 'DISPOSITIVO', 'COMERCIO')),
    valor         text        NOT NULL,
    motivo        text        NOT NULL,
    origen        text        NOT NULL CHECK (origen IN ('MANUAL', 'CASO')),
    vigente_hasta timestamptz NULL,
    creada_en     timestamptz NOT NULL,
    creada_por    text        NOT NULL,
    -- Una entrada por (tipo, valor); la vigencia se evalúa con vigente_hasta al leer.
    UNIQUE (tipo, valor)
);

-- Solo la usa el cálculo de métricas (§9); la evaluación de reglas nunca la consulta.
CREATE TABLE etiqueta_verdad (
    transaccion_id_externo uuid PRIMARY KEY,
    escenario              text NOT NULL
);
