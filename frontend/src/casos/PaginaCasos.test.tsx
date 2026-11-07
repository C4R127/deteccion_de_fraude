import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiFalsa } from '@/pruebas/apiFalsa';
import { renderizar } from '@/pruebas/renderizar';
import { RutasApp } from '@/rutas';
import { casoDePrueba, detalleDePrueba } from './datosPrueba';
import { CasosEnVivoContext, type SuscriptorCasos } from './envivo';
import { PaginaCasos } from './PaginaCasos';
import type { Caso } from './tipos';

/** Suscriptor STOMP falso: la prueba decide cuándo llega un caso. */
function suscriptorFalso() {
  let emitir: ((caso: Caso) => void) | null = null;
  const suscribir: SuscriptorCasos = (alRecibir) => {
    emitir = alRecibir;
    return () => {
      emitir = null;
    };
  };
  return { suscribir, emitir: (caso: Caso) => act(() => emitir?.(caso)) };
}

describe('bandeja de casos (§8.3)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('muestra los casos de la API ordenados como los entrega', async () => {
    apiFalsa({
      'GET /api/v1/casos': {
        contenido: [
          casoDePrueba({ id: 'a', prioridad: 'ALTA', score: 85, decision: 'RECHAZAR' }),
          casoDePrueba({ id: 'b', prioridad: 'MEDIA', score: 45 }),
        ],
        pagina: 0, tamano: 50, total: 2,
      },
    });
    const { suscribir } = suscriptorFalso();

    renderizar(
      <CasosEnVivoContext.Provider value={suscribir}>
        <PaginaCasos />
      </CasosEnVivoContext.Provider>,
    );

    expect(await screen.findByText('2 casos')).toBeInTheDocument();
    const filas = screen.getAllByRole('row');
    expect(filas).toHaveLength(3); // encabezado + 2
    expect(within(filas[1]!).getByText('ALTA')).toBeInTheDocument();
    expect(within(filas[2]!).getByText('MEDIA')).toBeInTheDocument();
  });

  it('un caso recibido por STOMP aparece en la tabla', async () => {
    apiFalsa({ 'GET /api/v1/casos': { contenido: [], pagina: 0, tamano: 50, total: 0 } });
    const vivo = suscriptorFalso();
    renderizar(
      <CasosEnVivoContext.Provider value={vivo.suscribir}>
        <PaginaCasos />
      </CasosEnVivoContext.Provider>,
    );
    expect(await screen.findByText('No hay casos con estos filtros.')).toBeInTheDocument();

    vivo.emitir(casoDePrueba({ id: 'nuevo-1', prioridad: 'ALTA', score: 90, decision: 'RECHAZAR', monto: 980.5 }));

    const fila = await screen.findByRole('row', { name: /nuevo/i });
    expect(within(fila).getByText('ALTA')).toBeInTheDocument();
    expect(within(fila).getByText('90')).toBeInTheDocument();
    expect(within(fila).getByRole('link')).toHaveAttribute('href', '/casos/nuevo-1');
  });

  it('Ver abre el detalle del caso', async () => {
    apiFalsa({
      'GET /api/v1/casos': { contenido: [casoDePrueba({ id: 'caso-1' })], pagina: 0, tamano: 50, total: 1 },
      'GET /api/v1/casos/caso-1': detalleDePrueba(),
      'GET /api/v1/clientes/cliente-1': { id: 'cliente-1', nombre: 'Juan Perez', segmento: 'ESTANDAR',
        ciudadResidencia: 'Lima', fechaAlta: '2024-01-01T00:00:00Z' },
      'GET /api/v1/clientes/cliente-1/transacciones': [],
    });
    const { suscribir } = suscriptorFalso();
    renderizar(
      <CasosEnVivoContext.Provider value={suscribir}>
        <RutasApp />
      </CasosEnVivoContext.Provider>,
      { roles: ['ANALISTA'], ruta: '/casos' },
    );

    await userEvent.click(await screen.findByRole('link', { name: 'Ver' }));

    expect(await screen.findByRole('heading', { name: /Caso S\/\s?2,450.00/ }, { timeout: 3000 })).toBeInTheDocument();
  });

  it('un caso en vivo que no cumple el filtro de estado no se agrega', async () => {
    apiFalsa({ 'GET /api/v1/casos': { contenido: [], pagina: 0, tamano: 50, total: 0 } });
    const vivo = suscriptorFalso();
    renderizar(
      <CasosEnVivoContext.Provider value={vivo.suscribir}>
        <PaginaCasos filtrosIniciales={{ estado: 'CERRADO' }} />
      </CasosEnVivoContext.Provider>,
    );
    await screen.findByText('No hay casos con estos filtros.');

    vivo.emitir(casoDePrueba({ id: 'nuevo-2' }));

    expect(screen.getByText('No hay casos con estos filtros.')).toBeInTheDocument();
  });
});
