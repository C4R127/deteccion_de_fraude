import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiFalsa } from '@/pruebas/apiFalsa';
import { renderizar } from '@/pruebas/renderizar';
import { PaginaDashboard } from './PaginaDashboard';

// ECharts necesita canvas/SVG reales; en jsdom el gráfico se reemplaza por su título.
vi.mock('./Grafico', () => ({ Grafico: ({ titulo }: { titulo: string }) => <div>gráfico: {titulo}</div> }));

const SCORING = {
  desde: '2026-11-01T11:00:00Z', hasta: '2026-11-01T12:00:00Z', evaluaciones: 110, evaluacionesTardias: 1,
  transaccionesPorSegundo: 0.5, latenciaMs: { p50: 4, p95: 14, p99: 28 },
  deteccion: { fraudes: 10, fraudesMarcados: 8, marcadas: 10, legitimas: 100, legitimasMarcadas: 2, deteccion: 80,
    precision: 80, falsosPositivos: 2, montoFraudeEvitado: 2516, montoFraudeNoDetectado: 1203 },
  porEscenario: [],
};
const TRANSACCIONES = {
  desde: '2026-11-01T11:00:00Z', hasta: '2026-11-01T12:00:00Z', total: 1800, respaldos: 3, tarjetaBloqueada: 2,
  pendientes: 0, porDecision: { APROBAR: 1760, REVISAR: 30, RECHAZAR: 10 }, porMinuto: [],
};
const CASOS = {
  desde: '2026-10-25T12:00:00Z', hasta: '2026-11-01T12:00:00Z', abiertos: 7,
  abiertosPorEstado: { NUEVO: 4, ASIGNADO: 2, EN_INVESTIGACION: 1 }, abiertosPorPrioridad: { ALTA: 5, MEDIA: 2 },
  abiertosPorAnalista: [{ analista: 'analista1', abiertos: 3 }, { analista: null, abiertos: 4 }], cerrados: 12,
  cerradosPorResolucion: { FRAUDE_CONFIRMADO: 9, FALSO_POSITIVO: 3 }, tiempoMedioResolucionMinutos: 42.5,
};

function indicador(nombre: string) {
  return within(screen.getByRole('group', { name: nombre }));
}

describe('dashboard', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('muestra los indicadores de los tres servicios', async () => {
    apiFalsa({
      'GET /api/v1/metricas': SCORING,
      'GET /api/v1/metricas/transacciones': TRANSACCIONES,
      'GET /api/v1/metricas/casos': CASOS,
    });

    renderizar(<PaginaDashboard />, { roles: ['AUDITOR'] });

    expect(await screen.findByText('1,800')).toBeInTheDocument();
    expect(indicador('Transacciones').getByText('1,800')).toBeInTheDocument();
    expect(indicador('Respaldos').getByText('3')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('group', { name: 'Detección' })).toHaveTextContent('80.0 %'));
    expect(indicador('Latencia p95').getByText('14 ms')).toBeInTheDocument();
    expect(indicador('Casos abiertos').getByText('7')).toBeInTheDocument();
    expect(indicador('Casos abiertos').getByText('5 ALTA · 2 MEDIA')).toBeInTheDocument();
    expect(screen.getByText('gráfico: Transacciones por minuto')).toBeInTheDocument();
  });

  it('si un servicio falla, muestra el resto', async () => {
    apiFalsa({
      'GET /api/v1/metricas': SCORING,
      'GET /api/v1/metricas/transacciones': new Response(JSON.stringify({ status: 503, detail: 'caído' }),
        { status: 503, headers: { 'Content-Type': 'application/problem+json' } }),
      'GET /api/v1/metricas/casos': CASOS,
    });

    renderizar(<PaginaDashboard />, { roles: ['ANALISTA'] });

    await waitFor(() => expect(screen.getByRole('group', { name: 'Detección' })).toHaveTextContent('80.0 %'));
    expect(await screen.findByText(/No se pudieron cargar las métricas de transacciones/)).toBeInTheDocument();
    expect(indicador('Casos abiertos').getByText('7')).toBeInTheDocument();
  });
});
