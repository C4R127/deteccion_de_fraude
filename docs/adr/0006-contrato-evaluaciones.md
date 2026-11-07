# ADR 0006: contrato de `POST /internal/v1/evaluaciones`

- Estado: aceptada
- Fecha: 2026-11-07

## Contexto

Transacciones pide la decisión al scoring de forma síncrona (§3, paso 4), pero la
especificación no definía el cuerpo de la llamada. El scoring no puede leer el esquema
`transacciones`, y las métricas (§9) necesitan cruzar por `idExterno` y sumar montos.

## Decisión

Solicitud (records `EvaluacionSolicitud` / `EvaluacionResultado` en `services/common`):

```json
{"transaccionId": "...", "idExterno": "...", "tarjetaId": "...", "clienteId": "...",
 "comercioId": "COM-00421", "mcc": "5411", "dispositivoHuella": null,
 "monto": 189.90, "moneda": "PEN", "canal": "POS", "pais": "PE",
 "latitud": -12.0931, "longitud": -77.0465, "fecha": "2026-11-06T14:30:12Z"}
```

Respuesta: `{"score": 55, "decision": "REVISAR", "reglasDisparadas": [{"codigo": "R01", "puntos": 40, "detalle": "..."}]}`.

## Consecuencias

- El scoring recibe todo lo que usan las reglas sin acceder a otro esquema.
- `idExterno` y `monto` llegan al scoring y podrán guardarse en `evaluacion` para las
  métricas de la Fase 6.
- El contrato vive en `common`, así ambos servicios compilan contra el mismo tipo.
