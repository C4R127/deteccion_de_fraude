-- Revisión de código: tomarPendientes busca, por clave, eventos anteriores reprogramados para
-- no adelantar los siguientes (orden por tarjeta). Este índice evita recorrer todo el outbox.
CREATE INDEX outbox_clave_pendientes_idx ON outbox (clave, creado_en) WHERE enviado_en IS NULL;
