# ADR 0009: semántica de `n30d`, `paises`, `mccs` y `horas` del perfil

- Estado: aceptada
- Fecha: 2026-11-07

## Contexto

§5.4 fija que `promedio30d` y `desviacion30d` usan las transacciones APROBAR o REVISAR del
cliente con `fecha` en los 30 días previos a la última transacción procesada (tiempo de
evento), pero no lo dice para `n30d`, `paises`, `mccs` ni `horas`.

## Decisión

Todos los campos de `perfil:{clienteId}` usan **la misma muestra**: transacciones APROBAR o
REVISAR del cliente con `fecha` en `(ultimaFecha − 30 días, ultimaFecha]`, donde
`ultimaFecha` es la mayor fecha de evento procesada para ese cliente.

- `n30d`: tamaño de esa muestra (es el que exige el mínimo de 10 de §7.2).
- `paises`, `mccs`: valores distintos de la muestra.
- `horas`: 24 contadores por hora de Lima de la muestra.
- `actualizado`: `ultimaFecha` en epoch ms (tiempo de evento, no reloj del sistema).

## Consecuencias

- Los intentos rechazados no hacen "conocido" un país, un rubro o una hora, ni cuentan
  para el mínimo de perfil confiable.
- Un país o una hora que no se usa en 30 días vuelve a ser nuevo para R04 y R07.
