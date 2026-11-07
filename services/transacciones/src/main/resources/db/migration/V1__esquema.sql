-- Esquema transacciones (especificación §5.1 y ADR 0005).
-- Clientes, tarjetas, comercios y dispositivos se cargan con POST /internal/v1/siembra.

CREATE TABLE cliente (
    id                uuid        PRIMARY KEY,
    nombre            text        NOT NULL,
    segmento          text        NOT NULL CHECK (segmento IN ('ESTANDAR', 'PREMIUM', 'JOVEN')),
    ciudad_residencia text        NOT NULL,
    fecha_alta        timestamptz NOT NULL
);

CREATE TABLE tarjeta (
    id            uuid        PRIMARY KEY,
    cliente_id    uuid        NOT NULL REFERENCES cliente (id),
    token         text        NOT NULL UNIQUE,
    ultimos4      char(4)     NOT NULL CHECK (ultimos4 ~ '^[0-9]{4}$'),
    estado        text        NOT NULL CHECK (estado IN ('ACTIVA', 'BLOQUEADA')),
    fecha_emision timestamptz NOT NULL
);
CREATE INDEX tarjeta_cliente_idx ON tarjeta (cliente_id);

CREATE TABLE comercio (
    id       text          PRIMARY KEY,
    nombre   text          NOT NULL,
    mcc      char(4)       NOT NULL,
    pais     char(2)       NOT NULL,
    ciudad   text          NOT NULL,
    latitud  numeric(9, 6) NULL,
    longitud numeric(9, 6) NULL,
    online   boolean       NOT NULL
);

CREATE TABLE dispositivo (
    huella     text        PRIMARY KEY,
    cliente_id uuid        NOT NULL REFERENCES cliente (id),
    creado_en  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX dispositivo_cliente_idx ON dispositivo (cliente_id);

CREATE TABLE transaccion (
    id                 uuid           PRIMARY KEY,
    id_externo         uuid           NOT NULL UNIQUE,
    tarjeta_id         uuid           NOT NULL REFERENCES tarjeta (id),
    cliente_id         uuid           NOT NULL REFERENCES cliente (id),
    comercio_id        text           NOT NULL REFERENCES comercio (id),
    dispositivo_huella text           NULL,
    monto              numeric(15, 2) NOT NULL CHECK (monto > 0),
    moneda             char(3)        NOT NULL,
    canal              text           NOT NULL CHECK (canal IN ('POS', 'ECOMMERCE', 'ATM')),
    pais               char(2)        NOT NULL,
    latitud            numeric(9, 6)  NULL,
    longitud           numeric(9, 6)  NULL,
    fecha              timestamptz    NOT NULL,
    decision           text           NULL CHECK (decision IN ('APROBAR', 'REVISAR', 'RECHAZAR')),
    score              int            NULL,
    respaldo           boolean        NOT NULL DEFAULT false,
    creada_en          timestamptz    NOT NULL DEFAULT now()
);
-- GET /api/v1/clientes/{id}/transacciones (últimas N del cliente).
CREATE INDEX transaccion_cliente_fecha_idx ON transaccion (cliente_id, fecha DESC);
