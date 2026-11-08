# ADR 0015: escenarios de fraude que llegan al umbral y ritmo realista en `vivo`

- Estado: aceptada
- Fecha: 2026-11-07

## Contexto

Al preparar la Tarea 4.6 se vieron tres problemas:

1. **Escenarios bajo el umbral.** Con los puntos de §7.2, `CARD_TESTING` solo dispara R02
   (30) y `USO_NOCTURNO` solo R07 (10). Ninguno llega a 40, así que nunca se marcarían.
2. **El dispositivo se aprende.** Perfiles agrega a `dispositivos:{clienteId}` la huella de
   toda transacción APROBAR. En `CARD_TESTING` con una sola huella nueva, la 1.ª compra (solo
   R05 = 25) se aprueba y desde la 2.ª el dispositivo ya es conocido.
3. **`vivo` comprime el tiempo.** A 20 tps con 500 clientes cada tarjeta compra cada ~25 s,
   unas 24 veces cada 10 minutos. R02 salta en casi todo el tráfico normal, y R03 por el 5 %
   de compras POS en otra ciudad. Los fraudes quedarían escondidos entre falsos positivos.

## Decisión

Se ajustan los escenarios y el generador. Los puntos y las reglas no cambian.

- **`CARD_TESTING`**: el bot usa una huella nueva en cada intento. Las compras 1 a 5 dan
  R05 = 25 (APROBAR) y desde la 6.ª R02 + R05 = 55 (REVISAR).
- **`USO_NOCTURNO`**: 2 a 4 compras en línea en rubros de riesgo desde un mismo dispositivo
  nuevo. La primera da R07 + R05 + R06 = 50 y las siguientes al menos R05 + R06 = 40; son
  REVISAR, así que el dispositivo no se aprende. Solo se elige en `vivo` cuando en Lima son
  las 02:00–04:59; el resto del día su peso se reparte entre los otros cuatro escenarios.
- **`TARJETA_CLONADA`**: la compra lejana es en otra ciudad de Perú a 400 km o más, de 2 a 10
  minutos después de la compra local. La población no tiene comercios en el extranjero. La
  compra local es legítima y no va a `verdad.jsonl`.
- **Ritmo de `vivo`**:
  - Cada tarjeta espera al menos `espaciado_minutos` (15) entre transacciones.
  - Una compra física fuera de la ciudad solo ocurre si el viaje desde la última compra física
    es posible a 500 km/h; si no, el cliente sigue comprando en la ciudad donde está.
  - Con 500 clientes el máximo real ronda 0,55 tps, así que `tps` en `config.yaml` baja a 0,5.
    Los turnos sin cliente disponible se cuentan como `omitidas`.
  - Un cliente con un escenario en curso no recibe tráfico normal ni otro escenario.
- **Proporción de fraude**: es la fracción de transacciones fraudulentas sobre las enviadas.
  La probabilidad de empezar un escenario en un turno libre se calcula con el tamaño medio de
  cada escenario.
- **`verdad.jsonl`**: solo se escribe un fraude que la API registró (respuesta 201 o 200).

### Actualización (Tarea 6.4): la historia también es coherente con las reglas

Al probar el README desde cero, `historia --dias 30` dejó 236 falsos positivos (1,1 %):

- 147 por R03: compras en otra ciudad sin tiempo para viajar.
- 89 por R01: retiros de cajero de hasta S/ 500 en clientes que gastan unos S/ 60.

Se corrige el generador, no las reglas:

- `historia` usa el mismo registro de viajes que `vivo` (`RegistroViajes`), con el criterio
  de R03 y margen: bajo 60 s, hasta 50 km; desde 60 s, a 500 km/h. Si el viaje no da, la
  compra pasa a la ciudad donde está el cliente. Si aun así no da (dos comercios lejanos de
  la misma ciudad con un minuto de diferencia), se corre a la primera hora posible.
- El retiro de cajero es como máximo 3 veces el gasto medio del cliente (mínimo S/ 50).

## Consecuencias

- Los cinco escenarios pueden alcanzar REVISAR con las reglas iniciales. Las primeras cinco
  compras de `CARD_TESTING` se aprueban, así que su recall por transacción no es 100 %.
- `USO_NOCTURNO` no aparece en corridas de día. La proporción total de fraude se mantiene.
- `vivo` ya no sirve para generar carga. Los 20 tps de la Tarea 4.6 los produce k6, solo para
  medir latencia; esa prueba sí llena los perfiles de velocidad y genera falsos positivos.
- Las compras de fraude cuentan para el espaciado y la ubicación del cliente, igual que en
  Valkey.
