# ADR 0017: cada servicio calcula sus métricas; el dashboard las junta

- Estado: aceptada
- Fecha: 2026-11-07

## Contexto

§9 pide calcular las métricas en scoring. Varias necesitan datos de otros esquemas:

- Los respaldos están en `transacciones.transaccion`. Una transacción aprobada por respaldo
  porque el scoring no respondió no tiene fila en `scoring.evaluacion`.
- Los casos abiertos por prioridad y analista y el tiempo de resolución están en
  `casos.caso`.

Ningún servicio puede leer el esquema de otro (CLAUDE.md).

Además, §9 pide detección, precisión y falsos positivos "globales y por escenario". Una
transacción legítima no pertenece a ningún escenario, así que la precisión y los falsos
positivos por escenario no tienen una definición natural.

## Decisión

Cada servicio expone las métricas de sus propios datos, para los roles de lectura:

| Endpoint | Servicio | Contenido |
|---|---|---|
| `GET /api/v1/metricas?desde=&hasta=` | scoring | Detección, precisión, falsos positivos, desglose por escenario, latencia p50/p95/p99 y transacciones por segundo de las evaluaciones, evaluaciones tardías, monto de fraude evitado y no detectado |
| `GET /api/v1/metricas/transacciones?desde=&hasta=` | transacciones | Volumen por minuto, decisiones (incluidas las de tarjeta bloqueada) y respaldos |
| `GET /api/v1/metricas/casos` | casos | Casos abiertos por estado, prioridad y analista; tiempo medio de resolución |

El dashboard del frontend consulta los tres. El gateway enruta las dos rutas específicas
antes que la ruta general `/api/v1/metricas/**` de scoring.

Definiciones de scoring, sobre las evaluaciones con `creada_en` en `[desde, hasta)` (por
defecto, las últimas 24 horas):

- **Fraude real**: la evaluación tiene fila en `etiqueta_verdad` por `id_externo`; las
  demás son legítimas.
- **Marcada**: decisión REVISAR o RECHAZAR y no tardía. Una evaluación tardía cuenta como
  respaldo (ADR 0012): la transacción ya se aprobó.
- **Detección** = fraudes marcados / fraudes × 100. **Precisión** = fraudes marcados /
  marcadas × 100. **Falsos positivos** = legítimas marcadas / legítimas × 100. Si el
  denominador es 0, el valor es nulo.
- **Por escenario**: fraudes, marcados, detección y montos. La precisión y los falsos
  positivos se informan solo de forma global.
- **Fraude evitado**: suma del monto de los fraudes RECHAZAR no tardíos. **No detectado**:
  fraudes APROBAR o tardíos.
- Las transacciones de tarjeta bloqueada no tienen evaluación (ADR 0007) y no entran en estas
  métricas.

`POST /api/v1/metricas/verdad` (ADMIN, SISTEMA) recibe un arreglo
`[{"idExterno": "...", "escenario": "..."}]` y guarda o actualiza cada etiqueta, así que
subir dos veces el mismo archivo no duplica nada. El comando `metricas` del generador sube
`verdad.jsonl` por lotes.

## Consecuencias

- No se agrega acoplamiento entre servicios ni eventos nuevos. Si un servicio está caído, el
  dashboard muestra el resto.
- Las cifras de cada servicio usan su propio reloj y su propia ventana; el dashboard pide la
  misma ventana a scoring y a transacciones.
- El motor de evaluación nunca lee `etiqueta_verdad`; solo `MetricasService` la usa.
