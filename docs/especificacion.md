# Especificación: detección de fraude bancario en tiempo real

Fuente de verdad del comportamiento del sistema. El orden de construcción está en
`docs/plan-implementacion.md`. Los valores marcados como **(configurable)** se guardan
en base de datos o en `application.yml`, no en el código.

---

## 1. Objetivo

Evaluar cada transacción simulada en línea, devolver una decisión (APROBAR, REVISAR,
RECHAZAR), mantener perfiles de comportamiento por cliente y tarjeta, gestionar los
casos sospechosos en una bandeja de analistas, y medir la calidad de detección contra
fraudes inyectados conocidos.

Usuarios: analista de fraude, supervisor de fraude, auditor, administrador.

## 2. Alcance

**MVP:** generador con escenarios de fraude · API de transacciones · perfiles con Kafka
Streams · motor de reglas con puntaje · listas de bloqueo · bandeja de casos · editor de
reglas · dashboard y métricas · roles con Keycloak.

**Fuera del MVP (no implementar):** machine learning · confirmación del cliente por SMS
o app · análisis de redes de cuentas · integración con un core real · monedas distintas
de PEN.

## 3. Arquitectura

| Componente | Responsabilidad | Comunicación |
|---|---|---|
| generador | Simula clientes y fraudes; escribe `verdad.jsonl` | REST → gateway |
| gateway | Entrada única, valida JWT, límites de uso | REST → servicios |
| transacciones | Valida, aplica idempotencia, pide decisión, persiste, publica | REST → scoring; produce `transacciones.creadas` |
| scoring | Listas de bloqueo, reglas, puntaje, evaluaciones, métricas | Lee Valkey; produce `decisiones.sospechosas`; consume `casos.eventos` |
| perfiles | Agrega comportamiento por ventanas de tiempo | Consume `transacciones.creadas`; escribe Valkey |
| casos | Ciclo de vida de casos, historial, WebSocket | Consume `decisiones.sospechosas`; produce `casos.eventos` |
| frontend | Bandeja, detalle, reglas, listas, dashboard | REST vía gateway; STOMP a casos vía gateway |

### Flujo de una transacción

1. `POST /api/v1/transacciones` llega al gateway y pasa a transacciones.
2. Transacciones valida el cuerpo. Si `idExterno` ya existe, devuelve la respuesta
   original (HTTP 200) sin reevaluar.
3. Guarda la transacción con `decision = null` (pendiente).
4. Llama a `POST /internal/v1/evaluaciones` del scoring con tiempo límite
   (configurable, inicial 200 ms).
5. Si el scoring responde: usa su decisión. Si falla o excede el tiempo: decisión
   `APROBAR` con `respaldo = true` y `score = null`.
6. Actualiza la transacción con decisión, score y respaldo.
7. Publica `TransaccionCreadaEvento` en `transacciones.creadas`.
8. Responde HTTP 201 con la decisión.

El scoring, dentro de su evaluación, publica `DecisionSospechosaEvento` cuando la
decisión es REVISAR o RECHAZAR.

## 4. Stack

| Capa | Tecnologías |
|---|---|
| Backend | Java 21 (Temurin), Spring Boot 3.x, Spring for Apache Kafka, Kafka Streams, Spring Data JPA, Spring Data Redis (Lettuce, contra Valkey), Resilience4j, Spring WebSocket + STOMP, Spring Cloud Gateway, Spring Security OAuth2 Resource Server, Flyway, MapStruct, springdoc-openapi, Micrometer, Maven |
| Generador | Python 3.12, httpx, Faker (es_PE), NumPy, Pydantic |
| Datos | Apache Kafka (KRaft, imagen `apache/kafka`), PostgreSQL 16, Valkey, Kafka UI |
| Seguridad | Keycloak |
| Frontend | React, TypeScript, Vite, React Router, TanStack Query, TanStack Table, Apache ECharts, Leaflet + OpenStreetMap, Tailwind CSS, shadcn/ui, React Hook Form, Zod, STOMP.js, keycloak-js |
| Pruebas | JUnit 5, Mockito, TopologyTestDriver, Testcontainers, k6, Vitest, Testing Library, pytest |
| DevOps | Docker Compose (infraestructura), GitHub Actions |

## 5. Modelo de datos

### 5.1 Esquema `transacciones`

**cliente**: `id uuid pk`, `nombre text`, `segmento text` (ESTANDAR, PREMIUM, JOVEN),
`ciudad_residencia text`, `fecha_alta timestamptz`.

**tarjeta**: `id uuid pk`, `cliente_id uuid fk`, `token text unique`, `ultimos4 char(4)`,
`estado text` (ACTIVA, BLOQUEADA), `fecha_emision timestamptz`.

**comercio**: `id text pk` (ej. COM-00421), `nombre text`, `mcc char(4)`, `pais char(2)`,
`ciudad text`, `latitud numeric(9,6) null`, `longitud numeric(9,6) null`, `online boolean`.

**dispositivo**: `huella text pk`, `cliente_id uuid fk`, `creado_en timestamptz`
(ver `docs/adr/0005-tabla-dispositivo.md`).

**transaccion**: `id uuid pk`, `id_externo uuid unique`, `tarjeta_id uuid fk`,
`cliente_id uuid fk`, `comercio_id text fk`, `dispositivo_huella text null`,
`monto numeric(15,2)`, `moneda char(3)`, `canal text` (POS, ECOMMERCE, ATM),
`pais char(2)`, `latitud numeric(9,6) null`, `longitud numeric(9,6) null`,
`fecha timestamptz`, `decision text null`, `score int null`, `respaldo boolean default false`,
`creada_en timestamptz`.

Los clientes, tarjetas y comercios se cargan con un endpoint de siembra que usa el
generador (`POST /internal/v1/siembra`), no con datos fijos en migraciones.

### 5.2 Esquema `scoring`

**regla**: `id uuid pk`, `codigo text` (R01…R07), `nombre text`, `descripcion text`,
`parametros jsonb`, `puntos int`, `activa boolean`, `version int`, `vigente boolean`,
`creada_en timestamptz`, `creada_por text`. Editar una regla inserta una fila nueva con
`version + 1` y marca la anterior `vigente = false`. Solo una fila vigente por código.

**evaluacion** (solo INSERT): `id uuid pk`, `transaccion_id uuid unique`, `score int`,
`decision text`, `reglas_disparadas jsonb`, `versiones_reglas jsonb`, `latencia_ms int`,
`creada_en timestamptz`.

**lista_bloqueo**: `id uuid pk`, `tipo text` (TARJETA, DISPOSITIVO, COMERCIO),
`valor text`, `motivo text`, `origen text` (MANUAL, CASO), `vigente_hasta timestamptz null`,
`creada_en timestamptz`, `creada_por text`. Única por (`tipo`, `valor`) vigente.

**etiqueta_verdad**: `transaccion_id_externo uuid pk`, `escenario text`. Solo la usa el
cálculo de métricas (§9). La evaluación de reglas nunca la consulta.

### 5.3 Esquema `casos`

**caso**: `id uuid pk`, `transaccion_id uuid unique`, `tarjeta_id uuid`, `cliente_id uuid`,
`dispositivo_huella text null`, `monto numeric(15,2)`, `score int`, `decision text`,
`reglas_disparadas jsonb`, `prioridad text` (ALTA, MEDIA), `estado text`,
`analista_id text null`, `creado_en timestamptz`, `cerrado_en timestamptz null`,
`resolucion text null` (FRAUDE_CONFIRMADO, FALSO_POSITIVO).

**nota_caso**: `id uuid pk`, `caso_id uuid fk`, `autor_id text`, `texto text`, `creada_en`.

**historial_caso** (solo INSERT): `id uuid pk`, `caso_id uuid fk`, `estado_anterior text null`,
`estado_nuevo text`, `usuario_id text`, `fecha timestamptz`, `comentario text null`.

### 5.4 Valkey (escribe perfiles, lee scoring)

| Clave | Tipo | Contenido |
|---|---|---|
| `perfil:{clienteId}` | hash | `n30d`, `promedio30d`, `desviacion30d`, `paises` (CSV), `mccs` (CSV), `horas` (24 contadores CSV, hora de Lima), `actualizado` |
| `velocidad:{tarjetaId}` | sorted set | miembro = transaccionId, score = epoch ms del evento; se poda lo mayor a 1 h |
| `ultima:{tarjetaId}` | hash | `lat`, `lon`, `fecha` (epoch ms), `canal` |
| `dispositivos:{clienteId}` | set | huellas conocidas |

Semántica de `promedio30d` y `desviacion30d`: montos de transacciones con decisión
APROBAR o REVISAR del cliente con `fecha` en los 30 días previos a la última
transacción procesada (tiempo de evento). En cambio, `velocidad` y `ultima` incluyen
todas las transacciones, también las RECHAZADAS: los intentos fallidos de un
defraudador cuentan para detectar ráfagas. `dispositivos` solo agrega huellas de
transacciones APROBAR, para que el dispositivo de un intento sospechoso no pase a
ser "conocido".

### 5.5 Tópicos de Kafka

| Tópico | Particiones | Clave | Productor | Consumidor |
|---|---|---|---|---|
| `transacciones.creadas` | 6 | tarjetaId | transacciones | perfiles |
| `decisiones.sospechosas` | 6 | tarjetaId | scoring | casos |
| `casos.eventos` | 3 | tarjetaId | casos | scoring |

## 6. Generador

### 6.1 Clientes

Cada cliente tiene: ciudad de residencia (Lima, Arequipa, Trujillo, Cusco, Piura,
Chiclayo), segmento, gasto típico (media y desviación por segmento), franjas horarias
habituales, rubros (MCC) habituales, canales habituales y 1 o 2 dispositivos.
Cantidad de clientes configurable (inicial 500).

### 6.2 Modos

- `historia --dias N`: genera N días de transacciones normales con fechas pasadas, lo
  más rápido posible, para llenar los perfiles. No inyecta fraude.
- `vivo --tps T`: genera tráfico con fecha actual a T transacciones por segundo,
  mezclando fraude según la proporción configurada (inicial 2 %).
- `siembra`: crea clientes, tarjetas, comercios y dispositivos vía `/internal/v1/siembra`.
- `metricas`: sube `verdad.jsonl` a `POST /api/v1/metricas/verdad`.

### 6.3 Escenarios de fraude

| Escenario | Simulación | Regla esperada |
|---|---|---|
| `TARJETA_CLONADA` | Compra POS en ciudad o país lejano, minutos después de una compra local | R03 |
| `CARD_TESTING` | 6 a 12 compras ECOMMERCE de 1 a 10 PEN en menos de 10 minutos | R02 |
| `TOMA_DE_CUENTA` | Dispositivo nuevo y monto mayor a 5 veces el promedio | R05 + R01 |
| `COMPRA_ATIPICA` | Monto muy alto en MCC de riesgo nunca usado | R01 + R06 |
| `USO_NOCTURNO` | Varias operaciones entre 02:00 y 05:00 (Lima) en cliente sin historial a esa hora | R07 + otra |

Cada transacción fraudulenta se escribe en `verdad.jsonl`:
`{"idExterno": "...", "escenario": "CARD_TESTING"}`.

### 6.4 Configuración

Archivo `generador/config.yaml` validado con Pydantic: clientes, distribución de
segmentos, tps, proporción de fraude, peso por escenario, semilla aleatoria, URL del
gateway, credenciales del cliente OAuth `generador`. Misma semilla = misma corrida.

## 7. Motor de reglas

### 7.1 Orden de evaluación

1. Listas de bloqueo vigentes (tarjeta, dispositivo, comercio). Si hay coincidencia:
   `decision = RECHAZAR`, `score = 100`, regla `B01`, sin evaluar el resto.
2. Lectura de perfiles en Valkey (una sola ida y vuelta: pipeline o `MGET`/`HGETALL`).
3. Evaluación de todas las reglas activas; el score es la suma de puntos, con tope 100.
4. Decisión por umbrales (§7.3).

### 7.2 Reglas iniciales (parámetros configurables)

| Código | Nombre | Condición | Puntos |
|---|---|---|---|
| R01 | Monto inusual | `monto > factor × promedio30d`, con `factor = 5`; solo si `n30d >= 10` | 40 |
| R02 | Velocidad | 5 o más transacciones previas de la tarjeta en los 10 minutos anteriores a `fecha` | 30 |
| R03 | Viaje imposible | Velocidad implícita desde la última transacción > 800 km/h. Distancia haversine. Si el tiempo transcurrido es menor a 60 s, se cumple cuando la distancia supera 50 km. No aplica si la actual o la última son ECOMMERCE o no tienen coordenadas | 50 |
| R04 | País nuevo | `pais` no está en `paises` del perfil; solo si `n30d >= 10` | 20 |
| R05 | Dispositivo nuevo | `dispositivoHuella` no nula y no está en `dispositivos:{clienteId}` | 25 |
| R06 | Rubro de riesgo | MCC en la lista configurable (inicial: 7995 apuestas, 6051 cuasi efectivo/cripto, 4829 transferencias de dinero) | 15 |
| R07 | Horario inusual | Hora local de Lima entre 02:00 y 04:59 y el perfil tiene 0 transacciones en esas horas; solo si `n30d >= 10` | 10 |

Sin perfil (cliente nuevo o `n30d < 10`), las reglas que dependen del perfil no se
cumplen; R02, R03, R05 y R06 sí se evalúan.

Cada regla disparada se registra como
`{"codigo": "R01", "puntos": 40, "detalle": "monto 2450.00 PEN, promedio 180.00 PEN"}`.

### 7.3 Umbrales de decisión (configurables)

| Score | Decisión | Caso |
|---|---|---|
| 0 a 39 | APROBAR | No |
| 40 a 69 | REVISAR (la transacción se aprueba) | Sí, prioridad MEDIA |
| 70 a 100 | RECHAZAR | Sí, prioridad ALTA |

### 7.4 Rendimiento

Objetivo: p95 de la evaluación < 50 ms en local con 20 tps. Medido con el timer
Micrometer `scoring.evaluacion.latencia` y validado con k6.

## 8. Casos

### 8.1 Estados y transiciones permitidas

| Desde | Hacia | Quién |
|---|---|---|
| (creación) | NUEVO | sistema, al consumir `decisiones.sospechosas` |
| NUEVO | ASIGNADO | analista (se lo toma) o supervisor (asigna) |
| ASIGNADO | ASIGNADO | supervisor (reasigna) |
| ASIGNADO | EN_INVESTIGACION | analista asignado |
| EN_INVESTIGACION | CERRADO | analista asignado o supervisor, con `resolucion` obligatoria |

Cualquier otra transición devuelve HTTP 409 con Problem Details. Cada transición
inserta una fila en `historial_caso` en la misma transacción de base de datos.

### 8.2 Efectos del cierre

- `FRAUDE_CONFIRMADO`: publica `CasoCerradoEvento`; el scoring agrega la tarjeta (y el
  dispositivo si existe) a `lista_bloqueo` con `origen = CASO`.
- `FALSO_POSITIVO`: publica `CasoCerradoEvento`; no bloquea nada.

### 8.3 Bandeja y detalle

- Orden: prioridad (ALTA primero), luego `creado_en` ascendente.
- Filtros: estado, prioridad, analista, rango de fechas.
- Casos nuevos llegan en vivo por STOMP en `/topic/casos`.
- Detalle: reglas disparadas con detalle, últimas 20 transacciones del cliente
  (`GET /api/v1/clientes/{id}/transacciones`), mapa con sus ubicaciones, perfil del
  cliente, notas e historial.

## 9. Métricas

Calculadas en el scoring cruzando `evaluacion` con `etiqueta_verdad` (por
`id_externo` de la transacción). "Marcada" = REVISAR o RECHAZAR.

- Detección (recall) = fraudes marcados / fraudes reales × 100
- Precisión = fraudes marcados / transacciones marcadas × 100
- Falsos positivos = legítimas marcadas / legítimas × 100
- Las tres, globales y por escenario.
- Latencia p50/p95/p99, transacciones por segundo, cantidad de decisiones de respaldo.
- Casos abiertos por prioridad y analista, tiempo medio de resolución.
- Monto de fraude evitado (fraude real RECHAZADO) y no detectado (fraude real APROBADO).

Endpoint: `GET /api/v1/metricas?desde=&hasta=`.

## 10. Roles y seguridad

Realm Keycloak `fraude`, exportado en `infra/keycloak/realm-fraude.json`.

| Permiso | ADMIN | SUPERVISOR | ANALISTA | AUDITOR | SISTEMA |
|---|---|---|---|---|---|
| Crear transacciones y siembra | | | | | Sí |
| Subir etiquetas de verdad | Sí | | | | Sí |
| Gestionar reglas | Sí | Sí | | | |
| Gestionar listas de bloqueo | Sí | Sí | | | |
| Asignar y reasignar casos | Sí | Sí | | | |
| Tomar, investigar y cerrar casos | Sí | Sí | Sí | | |
| Agregar notas | Sí | Sí | Sí | | |
| Ver dashboard, métricas, casos e historial | Sí | Sí | Sí | Sí | |

Usuarios de prueba: `admin`, `supervisor`, `analista1`, `analista2`, `auditor`
(contraseñas en `.env.example`). Cliente OAuth `generador` con rol SISTEMA (client
credentials).

Reglas de datos: tarjetas solo por token y últimos 4; nombre del cliente enmascarado
para AUDITOR (ej. "J*** P***"); límite de uso en `POST /api/v1/transacciones`
(configurable, inicial 200 por segundo).

## 11. Contratos REST principales

| Método y ruta | Servicio | Rol |
|---|---|---|
| `POST /api/v1/transacciones` | transacciones | SISTEMA |
| `GET /api/v1/transacciones/{id}` | transacciones | lectura |
| `GET /api/v1/clientes/{id}/transacciones?limite=20` | transacciones | lectura |
| `GET /api/v1/clientes/{id}/perfil` | scoring (lee Valkey) | lectura |
| `POST /internal/v1/siembra` | transacciones | SISTEMA |
| `POST /internal/v1/evaluaciones` | scoring | interno |
| `GET/POST/PUT /api/v1/reglas` | scoring | ADMIN, SUPERVISOR |
| `GET/POST/DELETE /api/v1/listas-bloqueo` | scoring | ADMIN, SUPERVISOR |
| `GET /api/v1/evaluaciones/{transaccionId}` | scoring | lectura |
| `POST /api/v1/metricas/verdad` | scoring | ADMIN, SISTEMA |
| `GET /api/v1/metricas` | scoring | lectura |
| `GET /api/v1/casos` | casos | lectura |
| `GET /api/v1/casos/{id}` | casos | lectura |
| `POST /api/v1/casos/{id}/tomar` | casos | ANALISTA, SUPERVISOR, ADMIN |
| `POST /api/v1/casos/{id}/asignar` | casos | SUPERVISOR, ADMIN |
| `POST /api/v1/casos/{id}/investigar` | casos | analista asignado |
| `POST /api/v1/casos/{id}/cerrar` | casos | analista asignado, SUPERVISOR, ADMIN |
| `POST /api/v1/casos/{id}/notas` | casos | ANALISTA, SUPERVISOR, ADMIN |

"lectura" = ADMIN, SUPERVISOR, ANALISTA, AUDITOR. Las rutas `/internal/v1` no se
exponen en el gateway.

### Cuerpo de `POST /api/v1/transacciones`

```json
{
  "idExterno": "7f3c2a1e-0b9d-4c55-9e1a-2d6f8b4e1c70",
  "tarjetaToken": "tok_8c1f42a9",
  "comercioId": "COM-00421",
  "dispositivoHuella": null,
  "monto": 189.90,
  "moneda": "PEN",
  "canal": "POS",
  "pais": "PE",
  "latitud": -12.0931,
  "longitud": -77.0465,
  "fecha": "2026-11-06T14:30:12Z"
}
```

Validaciones (HTTP 422 si fallan): `monto > 0` con máximo 2 decimales; `moneda = PEN`;
`canal` en POS, ECOMMERCE, ATM; coordenadas obligatorias para POS y ATM, nulas para
ECOMMERCE; `tarjetaToken` y `comercioId` existentes (HTTP 404 si no); tarjeta BLOQUEADA
→ se registra y se rechaza sin llamar al scoring.
Se guarda con `decision = RECHAZAR`, `score = null`, `respaldo = false` y se publica el
evento (ADR 0007). El cuerpo de `POST /internal/v1/evaluaciones` está en ADR 0006.

Respuesta 201 (o 200 si es repetida):
`{"transaccionId": "...", "decision": "REVISAR", "score": 55, "respaldo": false}`

## 12. Eventos (records en `services/common`)

```java
record TransaccionCreadaEvento(int version, UUID transaccionId, UUID idExterno,
    UUID tarjetaId, UUID clienteId, String comercioId, String mcc,
    String dispositivoHuella, BigDecimal monto, String moneda, Canal canal,
    String pais, BigDecimal latitud, BigDecimal longitud, Instant fecha,
    Decision decision, Integer score, boolean respaldo) {}

record ReglaDisparada(String codigo, int puntos, String detalle) {}

record DecisionSospechosaEvento(int version, UUID transaccionId, UUID tarjetaId,
    UUID clienteId, String dispositivoHuella, BigDecimal monto, int score,
    Decision decision, List<ReglaDisparada> reglasDisparadas, Instant fecha) {}

record CasoCerradoEvento(int version, UUID casoId, UUID transaccionId,
    UUID tarjetaId, String dispositivoHuella, Resolucion resolucion, Instant fecha) {}

enum Decision { APROBAR, REVISAR, RECHAZAR }
enum Canal { POS, ECOMMERCE, ATM }
enum Resolucion { FRAUDE_CONFIRMADO, FALSO_POSITIVO }
```

Serialización JSON (Jackson), `version = 1`.

## 13. Supuestos tomados al preparar esta guía

Valores que no estaban en el documento original y se fijaron aquí; se pueden cambiar:

- Tiempo límite del scoring: 200 ms. Proporción de fraude: 2 %. Clientes: 500.
- Mínimo de 10 transacciones en 30 días para usar reglas basadas en perfil.
- Solo moneda PEN en el MVP.
- Tabla `scoring.etiqueta_verdad` para que el dashboard muestre métricas de detección.
- Infraestructura en Docker; servicios, generador y frontend se ejecutan desde el IDE.
