-- Consumidores idempotentes (CLAUDE.md, reglas de Kafka): el id del evento se registra en la
-- misma transacción que su efecto; un evento repetido no vuelve a aplicarse.
CREATE TABLE mensaje_procesado (
    consumidor    text        NOT NULL,
    evento_id     text        NOT NULL,
    procesado_en  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumidor, evento_id)
);
