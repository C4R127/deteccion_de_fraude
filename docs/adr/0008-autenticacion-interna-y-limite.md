# ADR 0008: autenticación de rutas internas y clave del límite de uso

- Estado: aceptada
- Fecha: 2026-11-07

## Contexto

§10 y §11 fijan los roles por endpoint, pero no cómo se protegen las rutas
`/internal/v1` (que no pasan por el gateway) ni a quién se aplica el límite de
200 solicitudes por segundo de `POST /api/v1/transacciones`.

## Decisión

1. El gateway valida el JWT de todas las rutas `/api/v1`, y **cada servicio vuelve a
   validarlo** (defensa en profundidad) con la configuración común de `services/common`
   (roles del realm → `ROLE_*`, audiencia `servicios`).
2. `POST /internal/v1/siembra` exige un JWT con rol `SISTEMA` en transacciones.
3. `POST /internal/v1/evaluaciones` no exige token: solo es alcanzable dentro de la red de
   servicios y el gateway no la expone.
4. El límite de uso se aplica **por cliente OAuth**: la clave es el claim `azp` del token
   (por ejemplo `generador`).

## Consecuencias

- Un token válido del frontend (rol ANALISTA) no puede sembrar ni crear transacciones,
  ni por el gateway ni llamando directo al servicio.
- La llamada síncrona transacciones → scoring no paga la obtención ni validación de un
  token. Si en el futuro los servicios se exponen fuera de una red privada, habrá que
  proteger también `/internal/v1/evaluaciones`.
- Cada integrador tiene su propio cupo; con un solo generador equivale a un límite global.
