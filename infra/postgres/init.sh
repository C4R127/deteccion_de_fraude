#!/usr/bin/env bash
# Crea un esquema y un usuario por servicio (§ "un esquema por servicio").
# Cada usuario solo puede usar y crear objetos en su propio esquema.
# Lo ejecuta la imagen de PostgreSQL una sola vez, al inicializar el volumen.
set -euo pipefail

: "${TRANSACCIONES_DB_PASSWORD:?falta en .env}"
: "${SCORING_DB_PASSWORD:?falta en .env}"
: "${CASOS_DB_PASSWORD:?falta en .env}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v POSTGRES_DB="$POSTGRES_DB" \
  -v pw_transacciones="$TRANSACCIONES_DB_PASSWORD" \
  -v pw_scoring="$SCORING_DB_PASSWORD" \
  -v pw_casos="$CASOS_DB_PASSWORD" <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE :"POSTGRES_DB" FROM PUBLIC;

CREATE ROLE transacciones LOGIN PASSWORD :'pw_transacciones';
CREATE ROLE scoring       LOGIN PASSWORD :'pw_scoring';
CREATE ROLE casos         LOGIN PASSWORD :'pw_casos';

GRANT CONNECT ON DATABASE :"POSTGRES_DB" TO transacciones, scoring, casos;

CREATE SCHEMA transacciones AUTHORIZATION transacciones;
CREATE SCHEMA scoring       AUTHORIZATION scoring;
CREATE SCHEMA casos         AUTHORIZATION casos;

ALTER ROLE transacciones SET search_path = transacciones;
ALTER ROLE scoring       SET search_path = scoring;
ALTER ROLE casos         SET search_path = casos;
SQL
