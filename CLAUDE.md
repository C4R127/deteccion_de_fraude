# Detección de fraude bancario en tiempo real

Plataforma de portafolio que evalúa transacciones bancarias simuladas en línea, decide
APROBAR / REVISAR / RECHAZAR con reglas, y lleva los casos sospechosos a una bandeja
de analistas. Todos los datos son ficticios.

- Especificación completa: `docs/especificacion.md` (fuente de verdad del QUÉ).
- Plan de implementación: `docs/plan-implementacion.md` (fuente de verdad del ORDEN).
- Si algo no está en estos documentos, pregunta antes de inventarlo.

## Cómo trabajar en este repositorio

- Trabaja **una fase a la vez** del plan. No adelantes trabajo de fases futuras.
- Antes de escribir código de una tarea, lee su sección en el plan y los apartados
  de la especificación que cita.
- Desarrollo guiado por pruebas: escribe la prueba, verifica que falla, implementa,
  verifica que pasa, haz commit.
- Al terminar cada tarea, marca su casilla `- [ ]` → `- [x]` en el plan.
- Al terminar una fase, ejecuta su verificación de cierre y resume qué quedó hecho.
- Nunca declares algo terminado sin haber ejecutado la prueba o el comando que lo
  demuestra. Pega el resultado relevante.
- Si una decisión de diseño no está cubierta, propón opciones y espera respuesta;
  si se aprueba, regístrala como ADR en `docs/adr/`.

## Arquitectura (resumen)

```
generador (Python) ──REST──▶ gateway ──▶ transacciones ──REST──▶ scoring ──lee──▶ Valkey
                                              │                     │
                                              ▼                     ▼
                                   Kafka: transacciones.creadas   Kafka: decisiones.sospechosas
                                              │                     │
                                              ▼                     ▼
                                   perfiles (Kafka Streams) ──▶ Valkey     casos ──WebSocket──▶ frontend
```

- La decisión es **síncrona**: transacciones llama a scoring con tiempo límite.
- Perfiles y casos se actualizan de forma **asíncrona** por Kafka.
- PostgreSQL con **un esquema por servicio**: `transacciones`, `scoring`, `casos`.
  Ningún servicio lee ni escribe el esquema de otro.

## Estructura

```
services/common/         eventos (records), errores Problem Details, seguridad compartida
services/transacciones/  API de entrada, idempotencia, publica transacciones.creadas
services/scoring/        reglas, listas de bloqueo, evaluaciones, métricas de detección
services/perfiles/       Kafka Streams → Valkey
services/casos/          ciclo de vida de casos, WebSocket/STOMP
services/gateway/        Spring Cloud Gateway, validación JWT, límites de uso
generador/               Python: clientes ficticios, escenarios de fraude, verdad.jsonl
frontend/                React + TypeScript + Vite
infra/                   docker-compose, realm de Keycloak, script de tópicos
pruebas-carga/           scripts k6
docs/                    especificación, plan, ADRs
```

## Stack

Java 21 (Temurin) · Spring Boot 3.x · Maven multimódulo · Apache Kafka (KRaft) ·
Kafka Streams · PostgreSQL 16 · Valkey · Keycloak · Python 3.12 · React + TypeScript +
Vite · Testcontainers · k6. Detalle en `docs/especificacion.md` §4.

## Comandos

```bash
# Infraestructura (Kafka, Kafka UI, PostgreSQL, Valkey, Keycloak)
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml down -v      # borra datos

# Backend
./mvnw clean verify                                     # build + todas las pruebas
./mvnw -pl services/scoring -am test                    # pruebas de un servicio
./mvnw -pl services/scoring spring-boot:run             # ejecutar un servicio

# Generador
cd generador && python -m venv .venv && . .venv/bin/activate && pip install -e ".[dev]"
python -m generador historia --dias 30                  # calentamiento con fechas pasadas
python -m generador vivo --tps 20                       # tráfico en vivo
pytest

# Frontend
cd frontend && npm install && npm run dev               # http://localhost:5173
npm test

# Carga
k6 run pruebas-carga/scoring.js
```

## Puertos locales

| Servicio | Puerto |
|---|---|
| gateway | 8080 |
| transacciones | 8081 |
| scoring | 8082 |
| perfiles (actuator) | 8083 |
| casos | 8084 |
| frontend | 5173 |
| PostgreSQL | 5432 |
| Kafka (host) | 9092 |
| Kafka UI | 8090 |
| Valkey | 6379 |
| Keycloak | 8180 |

## Convenciones obligatorias

- **Paquete base:** `com.fraude.<servicio>`; organiza por funcionalidad, no por capa.
- **Dinero:** `BigDecimal` en Java, `numeric(15,2)` en PostgreSQL. Nunca `double`/`float`.
- **Fechas:** `Instant` en UTC en todo el backend. La zona `America/Lima` solo se usa
  para la regla de horario (R07) y en la interfaz.
- **Tarjetas:** solo token y últimos 4 dígitos. Nunca un número de tarjeta completo,
  ni siquiera en datos de prueba o logs.
- **Idempotencia:** toda creación recibe `idExterno`; los consumidores Kafka ignoran
  mensajes ya procesados.
- **Eventos Kafka:** records en `services/common`, con campo `version`; clave del
  mensaje = `tarjetaId`.
- **APIs:** REST bajo `/api/v1` (públicas, vía gateway) y `/internal/v1` (entre
  servicios). Errores en Problem Details (RFC 7807). Swagger con springdoc.
- **Migraciones:** Flyway, una carpeta por servicio, nunca editar una migración ya
  aplicada; crea una nueva.
- **Auditoría:** `scoring.evaluacion` y `casos.historial_caso` solo admiten INSERT.
- **Idioma:** dominio en español (`Transaccion`, `Caso`, `Regla`), términos técnicos
  en inglés (`Repository`, `Controller`, `Config`). Sin tildes en identificadores.
- **Commits:** Conventional Commits con alcance del servicio,
  ej. `feat(scoring): agregar regla R03 viaje imposible`.
- **Versiones:** fija versiones de dependencias e imágenes Docker; nunca `latest`.
- **Secretos:** en `.env` (ignorado por git); entrega un `.env.example`.

## Trampas conocidas

- Kafka Streams debe usar el **tiempo del evento** (`fecha` del payload), no el reloj
  del sistema: el generador produce historia con fechas pasadas.
- Los perfiles se actualizan **después** de la decisión: al evaluar una transacción,
  Valkey todavía no la incluye. Las reglas cuentan solo transacciones previas.
- Las transacciones `ECOMMERCE` no tienen ubicación física: R03 no aplica a ellas.
- Un cliente con poca historia no tiene perfil confiable: ver mínimos en la
  especificación §7.
- El scoring nunca lee `verdad.jsonl` ni la tabla `etiqueta_verdad` al evaluar; solo
  el cálculo de métricas los usa.
- Testcontainers necesita Docker (o Podman configurado) en ejecución.
