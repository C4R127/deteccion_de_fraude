# Detección de fraude bancario en tiempo real

Plataforma de portafolio que evalúa transacciones bancarias simuladas en línea, decide
APROBAR / REVISAR / RECHAZAR con reglas y lleva los casos sospechosos a una bandeja de
analistas. Todos los datos son ficticios.

- Especificación: [docs/especificacion.md](docs/especificacion.md)
- Plan de implementación: [docs/plan-implementacion.md](docs/plan-implementacion.md)

## Requisitos

- Java 21 (Temurin). Maven no hace falta: usa `./mvnw` (o `mvnw.cmd` en Windows).
- Docker (para la infraestructura y Testcontainers).
- Python 3.12 (generador) y Node.js (frontend).

## Infraestructura local

```bash
cp .env.example .env        # ajusta las contraseñas si quieres
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml down -v   # borra datos
```

Levanta Kafka (KRaft, `localhost:9092`), Kafka UI (http://localhost:8090), PostgreSQL 16
(`localhost:5432`, un esquema y un usuario por servicio), Valkey (`localhost:6379`) y
Keycloak (http://localhost:8180, realm `fraude`). Los tópicos se crean solos con el
servicio `kafka-topicos`. Todos los secretos salen de `.env`: el realm de Keycloak usa
marcadores `${VAR}` y PostgreSQL los lee en `infra/postgres/init.sh`.

## Build

```bash
./mvnw clean verify
```

## Módulos

| Módulo | Puerto |
|---|---|
| `services/gateway` | 8080 |
| `services/transacciones` | 8081 |
| `services/scoring` | 8082 |
| `services/perfiles` (actuator) | 8083 |
| `services/casos` | 8084 |
| `services/common` | librería |

Proyecto en construcción: ver el plan para el estado de cada fase.
