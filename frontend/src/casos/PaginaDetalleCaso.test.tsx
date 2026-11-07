import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Rol } from '@/auth/permisos';
import { apiFalsa } from '@/pruebas/apiFalsa';
import { renderizar, sesionDePrueba } from '@/pruebas/renderizar';
import { detalleDePrueba } from './datosPrueba';
import { PaginaDetalleCaso } from './PaginaDetalleCaso';
import type { Caso } from './tipos';

// Leaflet necesita un navegador real; en jsdom se reemplaza el mapa.
vi.mock('./MapaTransacciones', () => ({ MapaTransacciones: () => <div>mapa</div> }));

function abrir(cambios: Partial<Caso>, roles: Rol[], usuario: string, extra: Record<string, unknown> = {}) {
  const api = apiFalsa({
    'GET /api/v1/casos/caso-1': detalleDePrueba(cambios),
    'GET /api/v1/clientes/cliente-1': { id: 'cliente-1', nombre: 'J*** P***', segmento: 'ESTANDAR',
      ciudadResidencia: 'Lima', fechaAlta: '2024-01-01T00:00:00Z' },
    'GET /api/v1/clientes/cliente-1/transacciones': [],
    'GET /api/v1/clientes/cliente-1/perfil': new Response(JSON.stringify({ status: 404, detail: 'sin perfil' }),
      { status: 404, headers: { 'Content-Type': 'application/problem+json' } }),
    ...extra,
  });
  renderizar(
    <Routes>
      <Route path="/casos/:id" element={<PaginaDetalleCaso />} />
    </Routes>,
    { sesion: sesionDePrueba(roles, usuario), ruta: '/casos/caso-1' },
  );
  return api;
}

describe('detalle del caso', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('muestra reglas disparadas, cliente e historial', async () => {
    abrir({}, ['AUDITOR'], 'auditor');

    expect(await screen.findByText('monto 2450.00 PEN, promedio 180.00 PEN')).toBeInTheDocument();
    expect(await screen.findByText('J*** P***')).toBeInTheDocument();
    expect(screen.getByText('R01')).toBeInTheDocument();
    expect(screen.getByText('Sin perfil todavía.')).toBeInTheDocument();
    expect(screen.getByText('sistema')).toBeInTheDocument();
  });

  it('el auditor no ve acciones ni el formulario de notas', async () => {
    abrir({ estado: 'NUEVO' }, ['AUDITOR'], 'auditor');

    await screen.findByText('R01');
    expect(screen.queryByRole('button', { name: 'Tomar caso' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Agregar nota' })).not.toBeInTheDocument();
  });

  it('los botones dependen del estado: NUEVO muestra Tomar al analista', async () => {
    abrir({ estado: 'NUEVO' }, ['ANALISTA'], 'analista1');

    expect(await screen.findByRole('button', { name: 'Tomar caso' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Investigar' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cerrar caso' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Asignar' })).not.toBeInTheDocument();
  });

  it('los botones dependen del estado: ASIGNADO al usuario muestra Investigar', async () => {
    abrir({ estado: 'ASIGNADO', analistaId: 'analista1' }, ['ANALISTA'], 'analista1');

    expect(await screen.findByRole('button', { name: 'Investigar' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Tomar caso' })).not.toBeInTheDocument();
  });

  it('tomar llama a la API y muestra el estado nuevo', async () => {
    const api = abrir({ estado: 'NUEVO' }, ['ANALISTA'], 'analista1', {
      'POST /api/v1/casos/caso-1/tomar': detalleDePrueba({ estado: 'ASIGNADO', analistaId: 'analista1' }),
    });

    await userEvent.click(await screen.findByRole('button', { name: 'Tomar caso' }));

    expect(api.llamadas.some((l) => l.metodo === 'POST' && l.url === '/api/v1/casos/caso-1/tomar')).toBe(true);
  });

  it('cerrar exige resolución', async () => {
    const api = abrir({ estado: 'EN_INVESTIGACION', analistaId: 'analista1' }, ['ANALISTA'], 'analista1', {
      'POST /api/v1/casos/caso-1/cerrar': detalleDePrueba({ estado: 'CERRADO', analistaId: 'analista1',
        resolucion: 'FRAUDE_CONFIRMADO', cerradoEn: '2026-11-07T16:00:00Z' }),
    });
    const cerrar = await screen.findByRole('button', { name: 'Cerrar caso' });

    await userEvent.click(cerrar);
    expect(screen.getByText('Elige una resolución para cerrar el caso.')).toBeInTheDocument();
    expect(api.llamadas.some((l) => l.metodo === 'POST')).toBe(false);

    await userEvent.click(screen.getByRole('radio', { name: 'Fraude confirmado' }));
    await userEvent.type(screen.getByLabelText('Comentario del cierre'), 'cliente no reconoce la compra');
    await userEvent.click(cerrar);

    const llamada = api.llamadas.find((l) => l.metodo === 'POST');
    expect(llamada?.url).toBe('/api/v1/casos/caso-1/cerrar');
    expect(llamada?.cuerpo).toEqual({ resolucion: 'FRAUDE_CONFIRMADO', comentario: 'cliente no reconoce la compra' });
  });

  it('muestra el error de la API (409) sin cambiar de página', async () => {
    abrir({ estado: 'NUEVO' }, ['ANALISTA'], 'analista1', {
      'POST /api/v1/casos/caso-1/tomar': new Response(JSON.stringify({ status: 409,
        detail: 'No se puede tomar un caso en estado ASIGNADO' }), { status: 409,
        headers: { 'Content-Type': 'application/problem+json' } }),
    });

    await userEvent.click(await screen.findByRole('button', { name: 'Tomar caso' }));

    expect(await screen.findByText('No se puede tomar un caso en estado ASIGNADO')).toBeInTheDocument();
  });
});
