// Prueba de carga del flujo en línea (Tarea 4.6, §7.4): POST /api/v1/transacciones por el
// gateway a tasa constante. Mide la latencia de punta a punta y cuenta los respaldos; el
// objetivo de §7.4 (p95 < 50 ms) es del timer del scoring `scoring.evaluacion.latencia`,
// que se lee aparte en /actuator/metrics del scoring.
//
// Datos: transacciones normales del generador (tarjetas y comercios de la siembra), con
// idExterno nuevo y fecha actual en cada envío:
//   generador/.venv/Scripts/python -m generador seco --dias 1 > pruebas-carga/datos/transacciones.jsonl
//
// Token: Keycloak firma el issuer con el host de la solicitud, así que se pide desde la
// máquina (localhost:8180) y se pasa en TOKEN.
//
// Ejecución con Docker (sin instalar k6):
//   docker run --rm -v "$PWD/pruebas-carga:/scripts" -e TOKEN=... \
//     -e GATEWAY=http://host.docker.internal:8080 grafana/k6:1.0.0 run /scripts/scoring.js
//
// Variables: TPS (20), DURACION (2m), GATEWAY, DATOS, TOKEN.
//
// Efecto secundario: cada tarjeta recibe varias transacciones por minuto, así que R02 y R03
// saltan y se generan decisiones sospechosas. Es tráfico para medir latencia, no fraude.

import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { scenario } from 'k6/execution';
import { Rate } from 'k6/metrics';

const GATEWAY = __ENV.GATEWAY || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN;
const TPS = Number(__ENV.TPS || 20);
const DURACION = __ENV.DURACION || '2m';

const transacciones = new SharedArray('transacciones', () =>
  open(__ENV.DATOS || './datos/transacciones.jsonl')
    .split('\n')
    .filter((linea) => linea.trim() !== '')
    .map((linea) => JSON.parse(linea)));

const respaldo = new Rate('respaldo');

export const options = {
  scenarios: {
    carga: {
      executor: 'constant-arrival-rate',
      rate: TPS,
      timeUnit: '1s',
      duration: DURACION,
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  if (!TOKEN) {
    throw new Error('Falta TOKEN: pide un token de client credentials del cliente generador');
  }
  if (transacciones.length === 0) {
    throw new Error('Sin datos: genera pruebas-carga/datos/transacciones.jsonl con el generador');
  }
}

// UUID v4 aleatorio: basta para que cada envío tenga un idExterno distinto.
function uuid4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export default function () {
  const base = transacciones[scenario.iterationInTest % transacciones.length];
  const cuerpo = Object.assign({}, base, {
    idExterno: uuid4(),
    fecha: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
  });
  const respuesta = http.post(`${GATEWAY}/api/v1/transacciones`, JSON.stringify(cuerpo), {
    headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' },
  });
  const creada = respuesta.status === 201;
  check(respuesta, { 'transacción creada (201)': () => creada });
  if (creada) {
    respaldo.add(respuesta.json('respaldo') === true);
  }
}
