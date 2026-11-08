# Plan de implementación: detección de fraude bancario

> **Para agentes:** implementa este plan **una fase a la vez** y tarea por tarea, en
> orden. Cada tarea sigue el ciclo: prueba que falla → implementación mínima → prueba
> que pasa → commit. Marca las casillas `- [ ]` al completarlas. Si tienes disponible
> la skill `superpowers:executing-plans` o `superpowers:subagent-driven-development`,
> úsala. Antes de empezar una fase, puedes detallar sus pasos en
> `docs/plan/fase-N.md` y pedir revisión.

**Objetivo:** plataforma que decide en línea sobre transacciones simuladas, mantiene
perfiles con Kafka Streams y gestiona casos sospechosos en una bandeja de analistas.

**Arquitectura:** cinco servicios Spring Boot (gateway, transacciones, scoring,
perfiles, casos), un generador en Python y un frontend React. Decisión síncrona por
REST con tiempo límite; perfiles y casos asíncronos por Kafka.

**Stack:** Java 21, Spring Boot 3.x, Kafka (KRaft) + Kafka Streams, PostgreSQL 16,
Valkey, Keycloak, Python 3.12, React + TypeScript, Testcontainers, k6.

**Especificación:** `docs/especificacion.md` (las referencias §N apuntan ahí).

## Restricciones globales

- Java 21, Spring Boot 3.x, Maven multimódulo con wrapper `./mvnw`.
- Paquete base `com.fraude.<servicio>`.
- Dinero: `BigDecimal` / `numeric(15,2)`. Fechas: `Instant` UTC.
- Tarjetas solo por token y últimos 4 dígitos; nunca números completos.
- Un esquema de PostgreSQL por servicio; ningún servicio accede al esquema de otro.
- Eventos Kafka: records de `services/common`, `version = 1`, clave `tarjetaId`.
- APIs públicas `/api/v1`, internas `/internal/v1` (no expuestas en el gateway).
- Errores en Problem Details (RFC 7807).
- `scoring.evaluacion` y `casos.historial_caso`: solo INSERT.
- Versiones fijas de dependencias e imágenes; nunca `latest`.
- Valores configurables (§7.2, §7.3, tiempos límite) fuera del código.
- Commits con Conventional Commits y alcance del servicio.

## Foco de revisión

Situaciones que la especificación implica y que más probablemente fallarían; cada
una tiene su prueba en la tarea indicada:

1. Dos solicitudes simultáneas con el mismo `idExterno` deben producir una sola
   transacción y la misma respuesta (Tarea 2.2).
2. Si el scoring está caído o lento, los pagos siguen: decisión APROBAR con
   `respaldo = true` dentro del tiempo límite (Tarea 2.2).
3. Un cliente sin historia no debe disparar R01, R04 ni R07 por falta de perfil
   (Tarea 4.3).
4. R03 no debe dividir por cero ni aplicarse a ECOMMERCE o a transacciones sin
   coordenadas (Tarea 4.3).
5. Un evento de Kafka entregado dos veces no debe crear dos casos ni dos bloqueos
   (Tareas 4.5 y 5.1).

---

## Fase 1: Base

### Tarea 1.1: Monorepo y build

**Archivos:**
- Crear: `pom.xml` (padre), `mvnw`, `.mvn/`, `.gitignore`, `.env.example`, `README.md`
- Crear: `services/{common,transacciones,scoring,perfiles,casos,gateway}/pom.xml`
- Crear: clase `*Application` y `application.yml` en cada servicio con el puerto de
  `CLAUDE.md`
- Prueba: `*ApplicationTests` (carga de contexto) en cada servicio

- [x] Prueba de carga de contexto en cada servicio (falla: no existe la aplicación).
- [x] Crear el pom padre con `dependencyManagement` (Spring Boot BOM, Spring Cloud BOM,
      Testcontainers BOM) y los módulos.
- [x] Crear cada aplicación mínima con actuator.
- [x] Verificar: `./mvnw clean verify` → BUILD SUCCESS.
- [x] Commit `chore: estructura del monorepo`.

### Tarea 1.2: Infraestructura local

**Archivos:**
- Crear: `infra/docker-compose.yml`, `infra/postgres/init.sql`,
  `infra/keycloak/realm-fraude.json`, `infra/kafka/crear-topicos.sh`

**Contenido:**
- Servicios: `apache/kafka` en modo KRaft (listener para el host en 9092), Kafka UI
  (8090), PostgreSQL 16 (5432), Valkey (6379), Keycloak (8180) importando el realm.
- `init.sql`: esquemas `transacciones`, `scoring`, `casos` y un usuario por servicio
  con permisos solo sobre su esquema.
- Realm `fraude`: roles y usuarios de §10, cliente `frontend` (público, PKCE),
  cliente `generador` (confidencial, client credentials, rol SISTEMA), cliente
  `servicios` para el audience de los tokens.
- Tópicos de §5.5 con sus particiones.
- Healthchecks en todos los contenedores.

- [x] Escribir los archivos.
- [x] Verificar: `docker compose -f infra/docker-compose.yml up -d` y todos `healthy`.
- [x] Verificar: el script lista los 3 tópicos con sus particiones.
- [x] Verificar: `curl` de token con client credentials de `generador` devuelve
      `access_token`.
- [x] Commit `chore(infra): docker compose con kafka, postgres, valkey y keycloak`.

### Tarea 1.3: Módulo común

**Archivos:**
- Crear en `services/common`: eventos y enums de §12, `ProblemDetailsHandler`
  (`@RestControllerAdvice` reutilizable), configuración de Jackson (`Instant` ISO-8601,
  `BigDecimal` como número), constantes de tópicos `Topicos`.
- Prueba: `EventosSerializacionTest`

**Interfaces producidas:** `TransaccionCreadaEvento`, `DecisionSospechosaEvento`,
`CasoCerradoEvento`, `ReglaDisparada`, `Decision`, `Canal`, `Resolucion`,
`Topicos.TRANSACCIONES_CREADAS`, `Topicos.DECISIONES_SOSPECHOSAS`, `Topicos.CASOS_EVENTOS`.

- [x] Prueba: cada evento serializa y deserializa sin pérdida; `monto` `189.90` se
      conserva con escala 2; `fecha` se serializa como `"2026-11-06T14:30:12Z"`.
- [x] Implementar y verificar: `./mvnw -pl services/common test` → PASS.
- [x] Commit `feat(common): eventos y manejo de errores compartido`.

### Tarea 1.4: Generador base

**Archivos:**
- Crear: `generador/pyproject.toml`, `generador/config.yaml`,
  `generador/generador/{__main__,config,modelos,clientes,comercios,normal}.py`
- Prueba: `generador/tests/test_normal.py`

**Interfaces producidas:**
- `Config` (Pydantic) con los campos de §6.4.
- `crear_poblacion(config) -> Poblacion` (clientes, tarjetas, comercios, dispositivos).
- `generar_normal(poblacion, desde, hasta, rng) -> Iterator[TransaccionSalida]` donde
  `TransaccionSalida` tiene exactamente los campos del cuerpo de §11.
- CLI `python -m generador seco --dias 1` que imprime JSONL sin llamar a la API.

- [x] Pruebas: misma semilla → misma salida; `monto > 0` con 2 decimales; ECOMMERCE sin
      coordenadas y POS/ATM con coordenadas; la mayoría de transacciones POS de un
      cliente ocurren en su ciudad de residencia; los tokens no parecen números de
      tarjeta (prefijo `tok_`).
- [x] Implementar y verificar: `pytest` → PASS.
- [x] Commit `feat(generador): poblacion y transacciones normales`.

**Cierre de fase 1:** `./mvnw clean verify`, `pytest` y `docker compose up` sin errores.

---

## Fase 2: Entrada de transacciones

### Tarea 2.1: Datos maestros y siembra

**Archivos:**
- Crear: `services/transacciones/src/main/resources/db/migration/V1__esquema.sql`
  (tablas de §5.1)
- Crear: entidades y repositorios de `cliente`, `tarjeta`, `comercio`, `dispositivo`,
  `transaccion`
- Crear: `SiembraController` en `/internal/v1/siembra`
- Modificar: generador, comando `siembra`
- Prueba: `SiembraIT` (Testcontainers PostgreSQL)

- [x] Prueba: enviar la siembra dos veces no duplica registros (upsert por id/token).
- [x] Implementar y verificar.
- [x] Commit `feat(transacciones): esquema y siembra de datos maestros`.

### Tarea 2.2: API de transacciones con idempotencia y respaldo

**Archivos:**
- Crear: `TransaccionController`, `RegistroTransaccionService`, `ScoringClient`,
  DTOs `TransaccionRequest`, `TransaccionResponse`
- Prueba: `TransaccionApiIT` (Testcontainers PostgreSQL + WireMock para el scoring)

**Interfaces:**
- Consume: `POST /internal/v1/evaluaciones` (se implementa en 4.4; aquí se simula con
  WireMock) que devuelve `{score, decision, reglasDisparadas}`.
- Produce: `POST /api/v1/transacciones` según §11.

**Comportamiento:** §3 pasos 2 a 6 y §11 validaciones. `ScoringClient` con
Resilience4j `TimeLimiter` (200 ms configurable) y respaldo
`APROBAR, respaldo = true, score = null`.

- [x] Pruebas:
  - `crea_transaccion_y_devuelve_decision_del_scoring` → 201 con la decisión simulada.
  - `id_externo_repetido_devuelve_la_misma_respuesta` → segunda llamada 200, mismo
    cuerpo, una sola fila.
  - `id_externo_concurrente_crea_una_sola_transaccion` → 10 hilos con el mismo
    `idExterno`, una fila, todas las respuestas iguales.
  - `scoring_lento_aplica_respaldo` → WireMock con retraso de 1 s, respuesta en menos
    de 500 ms con `respaldo = true`.
  - `scoring_caido_aplica_respaldo`.
  - `validaciones_devuelven_422` (monto 0, 3 decimales, moneda USD, ECOMMERCE con
    coordenadas, POS sin coordenadas).
  - `tarjeta_bloqueada_se_rechaza_sin_llamar_al_scoring`.
- [x] Implementar y verificar.
- [x] Commit `feat(transacciones): api con idempotencia y decision de respaldo`.

### Tarea 2.3: Publicación de eventos

**Archivos:**
- Crear: `PublicadorTransacciones`
- Prueba: `PublicacionKafkaIT` (Testcontainers Kafka + PostgreSQL)

**Comportamiento:** publica `TransaccionCreadaEvento` con clave `tarjetaId` solo
después del commit de base de datos (`@TransactionalEventListener(AFTER_COMMIT)`).
Una transacción repetida por idempotencia no se publica de nuevo.

- [x] Pruebas: un evento por transacción nueva; ninguno en la repetida; la clave es el
      `tarjetaId`; si la base de datos hace rollback, no se publica.
- [x] Implementar y verificar.
- [x] Commit `feat(transacciones): publicar transacciones creadas en kafka`.

### Tarea 2.4: Gateway, seguridad y envío desde el generador

**Archivos:**
- Crear en `services/gateway`: rutas a transacciones, scoring y casos (solo `/api/v1`
  y el endpoint STOMP), validación JWT, `RequestRateLimiter` con Valkey en
  `POST /api/v1/transacciones`
- Crear en `services/common`: configuración de Resource Server y conversor de roles
  del realm a `ROLE_*`
- Modificar: generador, comandos `historia` y `vivo` con token de client credentials
  y `httpx` asíncrono
- Prueba: `GatewaySeguridadIT`; `generador/tests/test_cliente_api.py` (con `respx`)

- [x] Pruebas: sin token → 401; rol ANALISTA en `POST /api/v1/transacciones` → 403;
      `/internal/v1/**` no es accesible por el gateway → 404; superar el límite → 429.
- [x] Implementar y verificar.
- [x] Verificar de punta a punta: `python -m generador siembra` y
      `python -m generador historia --dias 1`; los mensajes aparecen en Kafka UI.
- [x] Commit `feat(gateway): seguridad, rutas y limite de uso`.

**Cierre de fase 2:** transacciones del generador llegan a PostgreSQL y a Kafka con
decisión de respaldo (el scoring aún no existe).

---

## Fase 3: Perfiles

### Tarea 3.1: Topología de perfil de cliente

**Archivos:**
- Crear en `services/perfiles`: `TopologiaPerfiles`, `ExtractorFechaEvento`
  (`TimestampExtractor` que usa `fecha`), `EscritorPerfil` (procesador que escribe en
  Valkey)
- Prueba: `TopologiaPerfilesTest` (TopologyTestDriver con un escritor falso)

**Comportamiento:** §5.4 `perfil:{clienteId}` con la semántica definida ahí. Las
horas se cuentan en `America/Lima`.

- [x] Pruebas:
  - `promedio_y_desviacion_de_30_dias` con montos conocidos.
  - `excluye_transacciones_de_mas_de_30_dias`.
  - `excluye_transacciones_rechazadas_del_promedio`.
  - `cuenta_horas_en_hora_de_lima` (una transacción a las 08:00Z cuenta en la hora 3).
  - `usa_la_fecha_del_evento_no_el_reloj`.
- [x] Implementar y verificar.
- [x] Commit `feat(perfiles): perfil de cliente con kafka streams`.

### Tarea 3.2: Velocidad, última ubicación y dispositivos

**Archivos:**
- Crear: `EscritorVelocidad`, `EscritorUltima`, `EscritorDispositivos`
- Prueba: `EscritoresValkeyIT` (Testcontainers Valkey)

- [x] Pruebas: `velocidad` poda entradas de más de 1 hora; `velocidad` y `ultima`
      incluyen RECHAZADAS; `dispositivos` solo agrega huellas de APROBAR; reprocesar
      el mismo evento no duplica entradas en `velocidad`.
- [x] Implementar y verificar.
- [x] Verificar: `python -m generador historia --dias 30` y revisar en Valkey que
      existen claves `perfil:*` con `n30d` mayor a 10 para la mayoría de clientes.
- [x] Commit `feat(perfiles): velocidad, ultima ubicacion y dispositivos`.

---

## Fase 4: Scoring

### Tarea 4.1: Reglas versionadas

**Archivos:**
- Crear: `services/scoring/src/main/resources/db/migration/V1__esquema.sql` (tablas de
  §5.2) y `V2__reglas_iniciales.sql` (R01 a R07 de §7.2, versión 1)
- Crear: `ReglaEntity`, `ReglaRepository`, `CatalogoReglas` (caché en memoria de las
  reglas vigentes, se recarga al editar)
- Prueba: `CatalogoReglasIT`

- [x] Pruebas: editar una regla crea versión 2 y deja una sola vigente; el catálogo
      devuelve la versión nueva tras editar; los parámetros jsonb se leen como mapa
      tipado.
- [x] Implementar y verificar.
- [x] Commit `feat(scoring): reglas versionadas`.

### Tarea 4.2: Lectura de perfiles

**Archivos:**
- Crear: `PerfilLector`, record `PerfilContexto(Integer n30d, BigDecimal promedio30d,
  BigDecimal desviacion30d, Set<String> paises, Set<String> mccs, int[] horas,
  long transaccionesUltimos10Min, UltimaUbicacion ultima, Set<String> dispositivos)`
- Prueba: `PerfilLectorIT` (Testcontainers Valkey)

- [x] Pruebas: lee todo en una sola ida y vuelta (pipeline); cliente sin claves →
      `PerfilContexto` vacío sin excepción; `transaccionesUltimos10Min` cuenta solo
      eventos con fecha en los 10 minutos anteriores a la fecha de la transacción.
- [x] Implementar y verificar.
- [x] Commit `feat(scoring): lectura de perfiles desde valkey`.

### Tarea 4.3: Reglas R01 a R07

**Archivos:**
- Crear: interfaz `Regla` con
  `String codigo()` y
  `Optional<ReglaDisparada> evaluar(ContextoEvaluacion ctx, Map<String, Object> parametros)`
- Crear: `ReglaMontoInusual`, `ReglaVelocidad`, `ReglaViajeImposible`,
  `ReglaPaisNuevo`, `ReglaDispositivoNuevo`, `ReglaRubroRiesgo`,
  `ReglaHorarioInusual`, utilidad `Haversine`
- Prueba: una clase de prueba por regla

**Comportamiento:** exactamente §7.2, incluido el texto de `detalle`.

- [x] Pruebas mínimas por regla (además del caso que dispara y el que no):
  - R01: no dispara con `n30d = 9`; monto igual a 5 × promedio no dispara.
  - R02: 4 previas no dispara; 5 previas dispara.
  - R03: Lima → Cusco en 10 minutos dispara; Lima → Callao en 2 horas no; tiempo 0
    con 100 km dispara sin error; ECOMMERCE no aplica; sin `ultima` no aplica.
  - R04 y R07: no disparan sin perfil suficiente.
  - R05: huella nula no dispara.
  - R07: 03:30 de Lima (08:30Z) dispara si `horas[3] = 0`.
- [x] Implementar y verificar.
- [x] Commit `feat(scoring): reglas R01 a R07`.

### Tarea 4.4: Motor de evaluación y endpoint interno

**Archivos:**
- Crear: `MotorEvaluacion`, `ListaBloqueoService`, `EvaluacionController`
  (`POST /internal/v1/evaluaciones`), `PublicadorDecisiones`
- Prueba: `MotorEvaluacionTest`, `EvaluacionApiIT`

**Comportamiento:** §7.1 y §7.3. Guarda la evaluación con reglas disparadas y versión
de cada regla. Publica `DecisionSospechosaEvento` si REVISAR o RECHAZAR. Una segunda
evaluación del mismo `transaccionId` devuelve la guardada. Timer Micrometer
`scoring.evaluacion.latencia` con percentiles 0.5, 0.95 y 0.99.

- [x] Pruebas: lista de bloqueo → RECHAZAR con 100 y solo B01; suma de puntos con tope
      100; límites 39/40 y 69/70; reglas inactivas no se evalúan; se publica evento
      solo para REVISAR y RECHAZAR; evaluación repetida no duplica fila ni evento.
- [x] Implementar y verificar.
- [x] Verificar de punta a punta: con el scoring levantado, el generador recibe
      decisiones sin `respaldo`.
- [x] Commit `feat(scoring): motor de evaluacion en linea`.

### Tarea 4.5: Bloqueo por casos y APIs de administración

**Archivos:**
- Crear: consumidor de `casos.eventos`, `ReglaController`, `ListaBloqueoController`,
  `PerfilController` (`GET /api/v1/clientes/{id}/perfil`),
  `EvaluacionConsultaController`
- Prueba: `BloqueoPorCasoIT`, `ReglasApiIT`

- [x] Pruebas: `FRAUDE_CONFIRMADO` bloquea tarjeta y dispositivo; `FALSO_POSITIVO` no
      bloquea; el mismo evento dos veces deja un solo bloqueo; editar una regla por API
      crea versión nueva; AUDITOR no puede editar (403).
- [x] Implementar y verificar.
- [x] Commit `feat(scoring): bloqueo por casos y api de reglas`.

### Tarea 4.6: Escenarios de fraude y prueba de carga

**Archivos:**
- Crear: `generador/generador/fraude.py` (escenarios de §6.3), escritura de
  `verdad.jsonl`
- Crear: `pruebas-carga/scoring.js` (k6, 20 tps durante 2 minutos contra el gateway)
- Prueba: `generador/tests/test_fraude.py`

- [x] Pruebas: cada escenario genera transacciones con la forma descrita en §6.3; la
      proporción de fraude se respeta con tolerancia; cada fraude queda en
      `verdad.jsonl`.
- [x] Implementar y verificar. (ADR 0015: escenarios ajustados al umbral y ritmo de `vivo`.)
- [x] Verificar: `k6 run pruebas-carga/scoring.js` y reportar el p95 del timer del
      scoring (objetivo < 50 ms, §7.4). Si no se cumple, reportar el valor; no
      optimizar sin consultar. (2401 solicitudes a 20 tps: p95 del timer 14 ms, p99 28 ms;
      p95 de punta a punta 182 ms; 11 respaldos en el primer segundo; pool de
      transacciones con máximo 8 de 10 conexiones activas y 0 en espera.)
- [x] Commit `feat(generador): escenarios de fraude y prueba de carga`.

**Cierre de fase 4:** cada transacción recibe su decisión en tiempo real por API.

---

## Fase 5: Casos e interfaz

### Tarea 5.1: Servicio de casos

**Archivos:**
- Crear en `services/casos`: migración (tablas de §5.3), consumidor de
  `decisiones.sospechosas`, `MaquinaEstadosCaso`, `CasoController`, `NotaController`,
  `PublicadorCasos`
- Prueba: `MaquinaEstadosCasoTest`, `CasosApiIT`

**Comportamiento:** §8.1 y §8.2; prioridad según §7.3; enmascarado de nombre para
AUDITOR (§10).

- [x] Pruebas: el mismo evento dos veces crea un solo caso; cada transición válida
      inserta historial; transición inválida → 409; cerrar sin `resolucion` → 422; un
      analista no asignado no puede cerrar (403); el cierre publica
      `CasoCerradoEvento`; la bandeja ordena por prioridad y antigüedad.
- [x] Implementar y verificar. (ADR 0016: el nombre enmascarado lo sirve transacciones
      en `GET /api/v1/clientes/{id}`.)
- [x] Commit `feat(casos): ciclo de vida de casos`.

### Tarea 5.2: Casos en vivo

**Archivos:**
- Crear: configuración STOMP en casos (`/ws`, destino `/topic/casos`), autenticación
  del token en el CONNECT; ruta WebSocket en el gateway
- Prueba: `CasosWebSocketIT`

- [x] Pruebas: un cliente STOMP autenticado recibe el caso nuevo; sin token se rechaza
      la conexión.
- [x] Implementar y verificar. (También se rechazan token inválido, roles sin lectura de
      casos y suscripciones a otros destinos.)
- [x] Commit `feat(casos): casos nuevos por websocket`.

### Tarea 5.3: Base del frontend

**Archivos:**
- Crear: proyecto Vite React TypeScript en `frontend/`, Tailwind, shadcn/ui, React
  Router, TanStack Query, `keycloak-js`, cliente HTTP con el token, layout con menú
  según rol
- Prueba: Vitest de la navegación por rol

**Rutas:** `/dashboard`, `/casos`, `/casos/:id`, `/reglas`, `/listas-bloqueo`,
`/metricas`. AUDITOR no ve acciones de edición.

- [x] Pruebas: el menú muestra Reglas solo a ADMIN y SUPERVISOR.
- [x] Implementar y verificar: `npm test` y `npm run build`.
- [x] Commit `feat(frontend): base con login y navegacion por rol`.

### Tarea 5.4: Bandeja y detalle de caso

**Archivos:**
- Crear: página `Casos` (TanStack Table, filtros de §8.3, suscripción STOMP), página
  `DetalleCaso` (reglas disparadas, línea de tiempo de transacciones, mapa Leaflet,
  perfil, notas, historial, botones de acción según estado y rol)

- [x] Pruebas (Vitest + Testing Library): un caso recibido por STOMP aparece en la
      tabla; los botones visibles dependen del estado; cerrar exige resolución.
- [x] Implementar y verificar. (Se agregó `GET /api/v1/clientes/{id}/transacciones` de §11
      en transacciones, que ninguna tarea anterior había construido. Verificado en el
      navegador con Keycloak: caso en vivo por STOMP, ciclo completo hasta el bloqueo por
      caso en scoring y nombre enmascarado para AUDITOR.)
- [x] Commit `feat(frontend): bandeja y detalle de casos`.

### Tarea 5.5: Editor de reglas y listas de bloqueo

**Archivos:**
- Crear: páginas `Reglas` y `ListasBloqueo` con React Hook Form + Zod

- [x] Pruebas: el formulario valida parámetros numéricos positivos; guardar muestra la
      nueva versión. (Las horas de R07 admiten 0; las listas como R06 se editan separadas
      por comas.)
- [x] Implementar y verificar.
- [x] Commit `feat(frontend): editor de reglas y listas de bloqueo`.

---

## Fase 6: Métricas y pulido

### Tarea 6.1: Métricas de detección

**Archivos:**
- Crear en scoring: `POST /api/v1/metricas/verdad`, `MetricasService`,
  `GET /api/v1/metricas`
- Modificar: generador, comando `metricas`
- Prueba: `MetricasServiceIT` con datos fijos

- [x] Pruebas: con 10 fraudes reales, 8 marcados y 2 legítimas marcadas entre 100
      legítimas → detección 80 %, precisión 80 %, falsos positivos 2 %; desglose por
      escenario correcto; el motor de evaluación no consulta `etiqueta_verdad`
      (prueba que la tabla vacía no cambia ninguna decisión).
- [x] Implementar y verificar. (ADR 0017: transacciones y casos exponen sus propias métricas;
      el gateway las enruta antes que las de scoring.)
- [x] Commit `feat(scoring): metricas de deteccion`.

### Tarea 6.2: Dashboard

**Archivos:**
- Crear: páginas `Dashboard` y `Metricas` con ECharts (volumen por minuto, decisiones,
  latencia, detección y precisión por escenario, casos por prioridad)

- [ ] Implementar y verificar visualmente con el generador en modo `vivo`.
- [ ] Commit `feat(frontend): dashboard y metricas`.

### Tarea 6.3: Integración continua

**Archivos:**
- Crear: `.github/workflows/ci.yml` (`./mvnw verify`, `pytest`, `npm ci && npm test &&
  npm run build`)

- [ ] Verificar que el workflow pasa en un push.
- [ ] Commit `ci: pipeline de build y pruebas`.

### Tarea 6.4: Documentación final

**Archivos:**
- Modificar: `README.md` (qué es, diagrama, requisitos, arranque paso a paso, usuarios
  de prueba, cómo correr el generador, métricas obtenidas, lugar para el GIF de demo)
- Crear: `docs/adr/0001-decision-en-linea-con-respaldo.md`,
  `0002-valkey-para-perfiles.md`, `0003-kafka-en-lugar-de-rabbitmq.md`,
  `0004-esquema-por-servicio.md`

- [ ] Escribir y revisar que los comandos del README funcionan desde cero.
- [ ] Commit `docs: readme y decisiones de arquitectura`.

### Tarea 6.5 (opcional): Observabilidad

- [ ] Prometheus y Grafana en `infra/` con un panel de latencia y throughput.
- [ ] Commit `chore(infra): prometheus y grafana`.
